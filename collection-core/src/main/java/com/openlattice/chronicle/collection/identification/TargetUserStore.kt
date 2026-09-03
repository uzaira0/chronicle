package com.openlattice.chronicle.collection.identification

import com.openlattice.chronicle.storage.UserQueueEntry
import java.util.NavigableMap

/**
 * Context-free persistence seam for the user-identification module (design §1C, refactor
 * plan §10.1).
 *
 * The current-user "queue" is split across two stores in the legacy code:
 *  1. the `userQueue` Room table (`UserQueueEntry`, `writeTimestamp` PK + `user`);
 *  2. the `current_user` key in the `chronicle_encrypted_prefs` EncryptedSharedPreferences.
 *
 * [EnrollmentSettings.setTargetUser] writes **both**; [EnrollmentSettings.getCurrentUser]
 * reads the pref. This interface is the single, mockable boundary over those two stores so
 * [UserIdentificationCollectionModule] can be exercised in JVM unit tests without an
 * Android `Context`, a Room database, or EncryptedSharedPreferences.
 *
 * Implementations hold only `Context`-free handles (a Room DAO, a `SharedPreferences`
 * instance, plain resolved key strings) — never an Android `Context` (design §1C / refactor
 * plan §6.1 guardrail 2). The production implementation is built by
 * [UserIdentificationModuleHolder].
 *
 * **No identity collection.** This store touches only the participant *label* the device
 * operator selects locally (`Target child` / `Other` / `Not set`). It never reads Android
 * account, contact, or device-owner identity (refactor plan §10.1 guardrail 3).
 *
 */
public interface TargetUserStore {

    /**
     * Inserts a [UserQueueEntry] into the `userQueue` Room table. Mirrors the legacy
     * `UserStorageQueue` insert inside `EnrollmentSettings.setTargetUser` — one row
     * appended for the selected target user.
     */
    public fun insertUserQueueEntry(entry: UserQueueEntry)

    /**
     * Writes [user] under the `current_user` EncryptedSharedPreferences key. Mirrors the
     * legacy `settings.edit().putString(current_user, user).apply()` in `setTargetUser`.
     */
    public fun writeCurrentUserPref(user: String)

    /**
     * Reads the `current_user` pref, returning [unassignedDefault] when it is unset —
     * identical to `EnrollmentSettings.getCurrentUser()` (which defaults to the
     * `user_unassigned` "Not set" label).
     */
    public fun readCurrentUserPref(unassignedDefault: String): String

    /** Current number of rows in the `userQueue` table. */
    public fun userQueueDepth(): Int

    /**
     * Returns the `userQueue` rows as a timestamp → user map, newest-first ordering
     * preserved by the DAO. Mirrors the `UserStorageQueue.getUserTimestamps()` read that
     * usage collection associates into a `TreeMap` for the nearest-lower-timestamp lookup.
     */
    public fun userTimestamps(): NavigableMap<Long, String>
}
