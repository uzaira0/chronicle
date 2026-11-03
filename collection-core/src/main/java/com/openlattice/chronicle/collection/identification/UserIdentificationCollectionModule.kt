package com.openlattice.chronicle.collection.identification

import android.util.Log
import com.openlattice.chronicle.collection.core.ModuleResult
import com.openlattice.chronicle.storage.UserQueueEntry

private const val TAG = "UserIdentificationCollectionModule"

/**
 * The user-identification data collection module (design §1A.2 `user_identification`).
 *
 * Wraps the current-user-queue behaviour **without changing any observed behaviour**:
 *
 *  - **Privacy class `LOCAL_PARTICIPANT_LABEL`** (design §1A.4) — a participant label the
 *    device operator selects locally. It is pref-controlled and **never enabled
 *    implicitly**: the module reports `DISABLED` and writes nothing unless the existing
 *    `identify_user` preference is on (the [userIdentificationEnabled] seam).
 *  - **`setTargetUser` parity** — [setTargetUser] reproduces
 *    `EnrollmentSettings.setTargetUser` exactly: a `UserQueueEntry` is inserted into the
 *    `userQueue` Room table **and** the `current_user` EncryptedSharedPreferences key is
 *    mirrored after the Room insert commits. Display and attribution both read committed
 *    history, so a failed insert cannot change the displayed selection.
 *  - **Labels** — which selection labels a usage event is `UsageEventsChronicleSensor`'s rule
 *    alone (session-bounded, over `UserStorageQueue.labelTimeline`).
 *  - **`DeviceUnlockMonitoringService` behaviour unchanged** — the unlock-prompt flow
 *    (`DeviceUnlockMonitoringService` → `UnlockDeviceReceiver` → `UserIdentificationActivity`
 *    → `setTargetUser`) is untouched; only the `setTargetUser` write is encapsulated, and
 *    only when the migration switch is on.
 *
 * **Diagnostics (refactor plan §10.1 steps 8–9) — redacted.** Diagnostics expose the
 * enabled flag, the epoch-millis of the last target-user update, and the `userQueue`
 * depth (a row count). They **never** expose the raw participant label — not the string,
 * not a hash, not a length. The configurable labels (`Study participant`, `Someone else`) are
 * study-defined participant identifiers; the only safe telemetry is "did an update
 * happen and when" (refactor plan §10.1 guardrails 1 & 3, design §1B.3).
 *
 * This is a plain class holding only its seams ([store], [userIdentificationEnabled]) — no
 * Android `Context`. Each seam resolves whatever
 * `Context` it needs at construction (via [UserIdentificationModuleHolder]) and keeps only
 * a `Context`-free handle, so the module never stores a `Context` (design §1C / refactor
 * plan §6.1 guardrail 2); see [UserIdentificationModuleHolder].
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
) {

    /** Room history is authoritative; update the preference only after its insert commits. */
    public fun setTargetUser(user: String): ModuleResult {
        if (!userIdentificationEnabled()) {
            Log.i(TAG, "user_identification disabled; setTargetUser is a no-op")
            return ModuleResult.Skipped("user identification disabled")
        }
        return try {
            store.insertUserQueueEntry(UserQueueEntry(user = user))
            store.writeCurrentUserPref(user)
            // itemsCollected is the one queue row written; the label itself is never logged.
            Log.i(TAG, "Target user updated (label redacted)")
            ModuleResult.Ok(1)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to persist target user (label redacted)", e)
            ModuleResult.Failed(e, redactedMessage = "target-user write failed: ${e.javaClass.simpleName}")
        }
    }

    /** Display the last committed attribution even when updating the preference failed. */
    public fun currentUser(): String = DeviceUser.current(store.readCurrentUserPref(DeviceUser.NOT_SET.stored))

    /** Whether user identification is currently enabled by the `identify_user` preference. */
    public fun isEnabled(): Boolean = userIdentificationEnabled()
}
