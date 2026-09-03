package com.openlattice.chronicle.collection.identification

import android.content.Context
import com.openlattice.chronicle.collection.CollectionModuleDiagnostics
import com.openlattice.chronicle.collection.CollectionModuleId
import com.openlattice.chronicle.collection.CollectionPrivacyClass
import com.openlattice.chronicle.collection.core.CollectionClock
import com.openlattice.chronicle.collection.core.CollectionLog
import com.openlattice.chronicle.collection.core.CollectionModuleStatus
import com.openlattice.chronicle.collection.core.CollectionWindow
import com.openlattice.chronicle.collection.core.DataCollectionModule
import com.openlattice.chronicle.collection.core.ModuleResult
import com.openlattice.chronicle.storage.UserQueueEntry
import java.util.NavigableMap
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking

private const val TAG = "UserIdentificationCollectionModule"

/**
 * The user-identification data collection module (design §1A.2 `user_identification`,
 * refactor plan §10, subphase 7A).
 *
 * Phase 7A wraps the pre-existing current-user-queue behaviour behind the
 * [DataCollectionModule] boundary **without changing any observed behaviour**:
 *
 *  - **Privacy class `LOCAL_PARTICIPANT_LABEL`** (design §1A.4) — a participant label the
 *    device operator selects locally. It is pref-controlled and **never enabled
 *    implicitly**: the module reports `DISABLED` and writes nothing unless the existing
 *    `identify_user` preference is on (the [userIdentificationEnabled] seam).
 *  - **`setTargetUser` parity** — [setTargetUser] reproduces
 *    `EnrollmentSettings.setTargetUser` exactly: a `UserQueueEntry` is inserted into the
 *    `userQueue` Room table **and** the `current_user` EncryptedSharedPreferences key is
 *    written, inside the same `runBlocking { launch { } }` shape. The `launch` makes the
 *    queue insert run concurrently with the pref write; that race is preserved verbatim,
 *    not "fixed" — both stores converge and the legacy code has relied on this since the
 *    feature shipped.
 *  - **"Not set" → empty user** — [resolveUserForEvent] is the exact `getTargetUser`
 *    logic from `UsageEventsChronicleSensor`: the nearest-lower-timestamp lookup, with a
 *    missing entry **or** the `Not set` label both resolving to the empty string. Usage
 *    collection keeps calling its own inline copy this phase; this method exists so the
 *    rule has a tested home and so a later phase can route the sensor through it.
 *  - **Timestamp lookup preserved** — [loadUserTimestampMap] exposes the
 *    `UserStorageQueue.getUserTimestamps()` → `TreeMap` read that usage collection uses
 *    for the per-event nearest-lower-timestamp lookup. Phase 7 does not migrate
 *    `UsageModuleCollectionDelegate`'s call site; the method is additive.
 *  - **`DeviceUnlockMonitoringService` behaviour unchanged** — the unlock-prompt flow
 *    (`DeviceUnlockMonitoringService` → `UnlockDeviceReceiver` → `UserIdentificationActivity`
 *    → `setTargetUser`) is untouched; only the `setTargetUser` write is encapsulated, and
 *    only when the migration switch is on.
 *
 * **Diagnostics (refactor plan §10.1 steps 8–9) — redacted.** Diagnostics expose the
 * enabled flag, the epoch-millis of the last target-user update, and the `userQueue`
 * depth (a row count). They **never** expose the raw participant label — not the string,
 * not a hash, not a length. The configurable labels (`Target child`, `Other`) are
 * study-defined participant identifiers; the only safe telemetry is "did an update
 * happen and when" (refactor plan §10.1 guardrails 1 & 3, design §1B.3).
 *
 * This is a plain class holding only its seams ([store], [userIdentificationEnabled],
 * [unassignedUserLabel], clock, log) — no Android [Context]. Each seam resolves whatever
 * `Context` it needs at construction (via [UserIdentificationModuleHolder]) and keeps only
 * a `Context`-free handle, so the module never stores a `Context` (design §1C / refactor
 * plan §6.1 guardrail 2). A single app-scoped instance is shared so the last-update
 * diagnostic accumulates across target-user selections — see [UserIdentificationModuleHolder].
 *
 */
public class UserIdentificationCollectionModule(
    private val store: TargetUserStore,
    /**
     * Enabled-state seam. Production supplies a lambda over the `identify_user`
     * EncryptedSharedPreferences flag (`EnrollmentSettings.isUserIdentificationEnabled()`);
     * tests pass a fixed boolean. A disabled module is a no-op (design §1C.1).
     */
    private val userIdentificationEnabled: () -> Boolean,
    /**
     * The `user_unassigned` label ("Not set"). Resolved from `R.string.user_unassigned`
     * at construction so the module never touches a `Context`. Used as the
     * `getCurrentUser` default and as the sentinel that [resolveUserForEvent] maps to "".
     */
    private val unassignedUserLabel: String,
    private val clock: CollectionClock = CollectionClock.SYSTEM,
    private val log: CollectionLog = CollectionLog.LOGCAT,
) : DataCollectionModule {

    override val id: CollectionModuleId = CollectionModuleId.USER_IDENTIFICATION

    override val privacyClass: CollectionPrivacyClass = id.privacyClass

    init {
        require(privacyClass == id.privacyClass) {
            "UserIdentificationCollectionModule.privacyClass must equal id.privacyClass"
        }
    }

    // ----- module diagnostics state (design §1B.3 — redaction-safe operational telemetry).
    // NOTE: no raw / hashed / length-derived participant label is ever stored here.
    @Volatile private var lastResult: ModuleResult = ModuleResult.Skipped("not yet run")
    @Volatile private var lastTargetUserUpdateEpochMs: Long? = null
    @Volatile private var lastError: String? = null

    /**
     * Sets the device target user, reproducing `EnrollmentSettings.setTargetUser` exactly.
     *
     * Behaviour, identical to the legacy `setTargetUser`:
     *  1. a `UserQueueEntry(user = [user])` is inserted into the `userQueue` Room table;
     *  2. the `current_user` EncryptedSharedPreferences key is written to [user];
     *  3. both run inside `runBlocking { launch { insert }; prefWrite }` — the insert is
     *     launched concurrently with the pref write; this race is preserved verbatim.
     *
     * When user identification is **disabled** this is a no-op ([ModuleResult.Skipped]) —
     * a disabled module writes nothing (design §1C.1). The one current caller that writes
     * while "disabled" is the disable-transition itself, which writes the `user_unassigned`
     * label; that path keeps using the legacy inline `EnrollmentSettings.setTargetUser`
     * body and is unaffected by this module (the migration switch only gates the *enabled*
     * write path — see [UserIdentificationMigration]).
     *
     * A persistence failure surfaces as [ModuleResult.Failed] — logged and recorded in
     * diagnostics, never silently swallowed (design §1C.2). The redacted message names
     * the failing store, never the participant label.
     */
    public fun setTargetUser(user: String): ModuleResult {
        if (!userIdentificationEnabled()) {
            log.info(TAG, "user_identification disabled; setTargetUser is a no-op")
            lastResult = ModuleResult.Skipped("user identification disabled")
            return lastResult
        }
        return try {
            runBlocking {
                launch {
                    store.insertUserQueueEntry(UserQueueEntry(user = user))
                }
                store.writeCurrentUserPref(user)
            }
            lastTargetUserUpdateEpochMs = clock.nowEpochMs()
            lastError = null
            // itemsCollected is the one queue row written; the label itself is never logged.
            lastResult = ModuleResult.Ok(1)
            log.info(TAG, "Target user updated (label redacted)")
            lastResult
        } catch (e: Exception) {
            lastError = "target-user write failed: ${e.javaClass.simpleName}"
            lastResult = ModuleResult.Failed(e, redactedMessage = lastError!!)
            log.error(TAG, "Failed to persist target user (label redacted)", e)
            lastResult
        }
    }

    /**
     * Reads the current target user, identical to `EnrollmentSettings.getCurrentUser()`:
     * the `current_user` pref, defaulting to the `user_unassigned` "Not set" label when
     * unset. This is the device-local participant label — a caller-internal value, never
     * routed into diagnostics.
     */
    public fun currentUser(): String = store.readCurrentUserPref(unassignedUserLabel)

    /** Whether user identification is currently enabled by the `identify_user` preference. */
    public fun isEnabled(): Boolean = userIdentificationEnabled()

    /**
     * Loads the `userQueue` rows as a timestamp → user [NavigableMap], for the per-event
     * nearest-lower-timestamp lookup. Equivalent to the
     * `UserStorageQueue.getUserTimestamps()` read + `TreeMap` association in
     * `UsageModuleCollectionDelegate`. Additive — Phase 7 does not switch that call site.
     */
    public fun loadUserTimestampMap(): NavigableMap<Long, String> = store.userTimestamps()

    /**
     * Resolves the participant label for an event at [eventTimestamp] against [users] —
     * the exact `UsageEventsChronicleSensor.getTargetUser` logic, kept byte-for-byte:
     *
     *  - the nearest entry with a strictly lower timestamp (`users.lowerEntry`);
     *  - a missing entry **or** the `user_unassigned` ("Not set") label both resolve to
     *    the empty string `""`.
     *
     * This is the canonical "Not set → empty user" mapping. Usage collection keeps its
     * own inline copy this phase; this method gives the rule a single tested home.
     */
    public fun resolveUserForEvent(
        eventTimestamp: Long,
        users: NavigableMap<Long, String>,
    ): String {
        val user = users.lowerEntry(eventTimestamp)?.value
        return if (user == null || user == unassignedUserLabel) "" else user
    }

    override fun status(): CollectionModuleStatus = when {
        !userIdentificationEnabled() -> CollectionModuleStatus.DISABLED
        lastResult is ModuleResult.Failed -> CollectionModuleStatus.FAILED
        else -> CollectionModuleStatus.IDLE
    }

    /**
     * Operational telemetry — **redaction-safe** (design §1B.3, refactor plan §10.1).
     *
     * Exposes only: the enabled flag, the epoch-millis of the last target-user update,
     * the `userQueue` depth, the last-result label, and a redacted error message. The
     * raw participant label is never present in any form — not the string, not a hash,
     * not a length. `redactedParticipantRef` is left `null`: the module deliberately
     * derives no participant reference at all.
     */
    override fun diagnostics(): CollectionModuleDiagnostics = CollectionModuleDiagnostics(
        moduleId = id,
        privacyClass = privacyClass,
        lastRunEpochMs = lastTargetUserUpdateEpochMs,
        lastResult = lastResult.label,
        itemsCollected = if (lastResult is ModuleResult.Ok) (lastResult as ModuleResult.Ok).items else 0,
        queueDepth = runCatching { store.userQueueDepth() }.getOrDefault(0),
        lastError = lastError,
        redactedParticipantRef = null,
        // Module-specific telemetry with no first-class DTO field. Only the enabled flag
        // and the last-update timestamp are surfaced — never the participant label.
        notTracked = buildSet {
            add("userIdentificationEnabled=${userIdentificationEnabled()}")
            val ts = lastTargetUserUpdateEpochMs
            if (ts != null) add("lastTargetUserUpdate=$ts") else add("lastTargetUserUpdate")
        },
    )

    /** No-op: user identification is preference/broadcast driven, not a push service. */
    override fun start(context: Context): ModuleResult =
        ModuleResult.Skipped("user_identification is preference-driven; use setTargetUser()")

    /** No-op: user identification is preference/broadcast driven, not a push service. */
    override fun stop(context: Context): ModuleResult =
        ModuleResult.Skipped("user_identification is preference-driven")

    /** No-op: user identification has no pull-style poll window. */
    override fun poll(context: Context, window: CollectionWindow): ModuleResult =
        ModuleResult.Skipped("user_identification is preference-driven; no poll window")

    /** No-op: this module buffers nothing in memory; the write is per-selection. */
    override fun flush(context: Context): ModuleResult =
        ModuleResult.Skipped("user_identification buffers nothing")
}
