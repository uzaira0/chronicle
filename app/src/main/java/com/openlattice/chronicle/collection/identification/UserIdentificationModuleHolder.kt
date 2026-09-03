package com.openlattice.chronicle.collection.identification

import android.content.Context
import android.content.SharedPreferences
import com.openlattice.chronicle.R
import com.openlattice.chronicle.collection.CollectionModuleId
import com.openlattice.chronicle.preferences.EncryptedPrefsHelper
import com.openlattice.chronicle.preferences.PARTICIPANT_ID
import com.openlattice.chronicle.preferences.STUDY_ID
import com.openlattice.chronicle.preferences.configuredStudyModuleEnabled
import com.openlattice.chronicle.storage.ChronicleDb
import com.openlattice.chronicle.storage.UserQueueEntry
import com.openlattice.chronicle.storage.UserStorageQueue
import java.util.NavigableMap
import java.util.TreeMap
import java.util.UUID

/**
 * Holds the single app-scoped [UserIdentificationCollectionModule] instance and constructs
 * it with its production seams (refactor plan §10.1).
 *
 * **Why a holder, not an `object` field of `Context`.** The module carries a mutable
 * diagnostic — the epoch-millis of the last target-user update — that should accumulate
 * across selections. A fresh module per call would reset it. At the same time, design
 * §1C / refactor plan §6.1 guardrail 2 forbid storing an Android `Context` in a singleton
 * field. This holder resolves the tension: it builds the module **lazily on first use**
 * from the application `Context`, resolves the resource-string keys to plain strings,
 * wires its seams (each keeping only `Context`-free handles), and then holds only the
 * module — never a `Context`.
 *
 * The seams wired here mirror the legacy `EnrollmentSettings` user-queue behaviour
 * exactly:
 *  - persistence → [PrefsAndRoomTargetUserStore] over `ChronicleDb.userQueueEntryData()`
 *    and the `chronicle_encrypted_prefs` EncryptedSharedPreferences;
 *  - enabled check → authenticated manifest scope AND the local `identify_user` flag;
 *  - the `user_unassigned` "Not set" label and the `current_user` pref key are resolved
 *    from string resources once, at build time.
 *
 */
public object UserIdentificationModuleHolder {

    @Volatile private var instance: UserIdentificationCollectionModule? = null

    /**
     * Returns the shared [UserIdentificationCollectionModule], building it on first use
     * from the application context of [context]. Subsequent calls return the same
     * instance so its last-update diagnostic accumulates across target-user selections.
     */
    public fun get(context: Context): UserIdentificationCollectionModule {
        instance?.let { return it }
        return synchronized(this) {
            instance ?: build(context.applicationContext).also { instance = it }
        }
    }

    private fun build(appContext: Context): UserIdentificationCollectionModule {
        val db = ChronicleDb.getInstance(appContext)
        val prefs = EncryptedPrefsHelper.getEncryptedPrefs(appContext)
        // Resource-string keys resolved once, here — the module never touches a Context.
        val currentUserKey = appContext.getString(R.string.current_user)
        val identifyUserKey = appContext.getString(R.string.identify_user)
        val unassignedLabel = appContext.getString(R.string.user_unassigned)
        val uploadServerDao = db.uploadServerDao()

        return UserIdentificationCollectionModule(
            store = PrefsAndRoomTargetUserStore(
                userQueueDao = db.userQueueEntryData(),
                prefs = prefs,
                currentUserKey = currentUserKey,
            ),
            // Final write-path check: the local choice may narrow the authenticated manifest but
            // can never authorize USER_IDENTIFICATION on its own. Keep only Context-free handles
            // in this app-scoped holder.
            userIdentificationEnabled = {
                val rawStudyId = prefs.getString(STUDY_ID, null)
                val studyId = rawStudyId?.let { value ->
                    runCatching { UUID.fromString(value) }
                        .getOrNull()
                        ?.takeIf { it.toString() == value }
                }
                val participantId = prefs.getString(PARTICIPANT_ID, null).orEmpty()
                prefs.getBoolean(identifyUserKey, false) &&
                    studyId != null &&
                    participantId.isNotBlank() &&
                    runCatching {
                        configuredStudyModuleEnabled(
                            uploadServerDao.getConfiguredServer(),
                            studyId,
                            participantId,
                            CollectionModuleId.USER_IDENTIFICATION,
                        )
                    }.getOrDefault(false)
            },
            unassignedUserLabel = unassignedLabel,
        )
    }
}

/**
 * Production [TargetUserStore] over the real `userQueue` Room DAO and the
 * `chronicle_encrypted_prefs` EncryptedSharedPreferences.
 *
 * Holds only `Context`-free handles — the Room [UserStorageQueue] DAO, a
 * [SharedPreferences] instance, and the plain resolved `current_user` key string. It is
 * therefore safe for [UserIdentificationModuleHolder] to construct and retain.
 *
 * Every operation is a thin pass-through to the legacy storage calls so behaviour stays
 * identical to `EnrollmentSettings`:
 *  - [insertUserQueueEntry] → `UserStorageQueue.insertEntries` (the non-`suspend`
 *    single-row insert; observably identical to the legacy `suspend insertEntry` — one
 *    `userQueue` row written — and keeps this seam plain-JVM mockable);
 *  - [writeCurrentUserPref] → `prefs.edit().putString(current_user, ...).commit()`;
 *  - [readCurrentUserPref] → `prefs.getString(current_user, default)`;
 *  - [userTimestamps] → `UserStorageQueue.getUserTimestamps()` associated into a
 *    `TreeMap`, exactly as `UsageModuleCollectionDelegate` builds its `users` map.
 *
 */
public class PrefsAndRoomTargetUserStore(
    private val userQueueDao: UserStorageQueue,
    private val prefs: SharedPreferences,
    private val currentUserKey: String,
) : TargetUserStore {

    override fun insertUserQueueEntry(entry: UserQueueEntry) {
        userQueueDao.insertEntries(listOf(entry))
    }

    override fun writeCurrentUserPref(user: String) {
        check(prefs.edit().putString(currentUserKey, user).commit()) {
            "Failed to persist current-user preference"
        }
    }

    override fun readCurrentUserPref(unassignedDefault: String): String =
        prefs.getString(currentUserKey, unassignedDefault) ?: unassignedDefault

    override fun userQueueDepth(): Int = userQueueDao.getUserTimestamps().size

    override fun userTimestamps(): NavigableMap<Long, String> =
        userQueueDao.getUserTimestamps().associateTo(TreeMap<Long, String>()) {
            it.writeTimestamp to it.user
        }
}
