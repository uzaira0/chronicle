package com.openlattice.chronicle.collection.identification

/**
 * Internal migration switch for the user-identification target-user write path (Phase 7,
 * subphase 7A, refactor plan §10.1 / design §1C.4).
 *
 * Phase 7A introduces a second target-user write path inside
 * `EnrollmentSettings.setTargetUser`: the *module path*, which routes the queue insert
 * and the `current_user` pref write through [UserIdentificationCollectionModule] +
 * [TargetUserStore]. While both the legacy inline path and the new module path coexist,
 * exactly one of them runs per `setTargetUser` invocation — `EnrollmentSettings` branches
 * on [USE_MODULE_MANAGER_USER_IDENTIFICATION_PATH]. There is no third path and no
 * double-write.
 *
 * **Activated.** [USE_MODULE_MANAGER_USER_IDENTIFICATION_PATH] is `true`: `setTargetUser`
 * routes the enabled write through [UserIdentificationCollectionModule] now that its parity
 * tests pass. Reverting the flag to `false` (back to the legacy inline body) is a
 * deliberate, separately-reviewed step (refactor plan §10.1 acceptance, decision #20).
 *
 * Both paths preserve identical observable behaviour: the `UserQueueEntry` insert into
 * `userQueue`, the `current_user` EncryptedSharedPreferences write, and the
 * `runBlocking { launch { } }` concurrency shape. They differ only in *which class* owns
 * the write and whether module diagnostics are updated.
 *
 * **Disable transition is unaffected.** The module path gates only the *enabled* write.
 * The disable transition — `SettingsActivity` / `NotificationListener` /
 * `NotificationDismissedReceiver` writing the `user_unassigned` "Not set" label when user
 * identification is turned off — keeps using the legacy inline body regardless of this
 * flag, because a disabled module is a no-op by contract (design §1C.1).
 *
 * This is a compile-time constant, not a server/remote setting — it gates the migration
 * during development only and carries no privacy or wire-shape implication.
 *
 */
public object UserIdentificationMigration {

    /**
     * `false` ⇒ `EnrollmentSettings.setTargetUser` runs the legacy inline
     * `runBlocking { launch { } }` body (the regression baseline).
     * `true` ⇒ it routes through [UserIdentificationCollectionModule.setTargetUser].
     *
     * **Activated (`true`)** after parity was proven. The module path's writes (`userQueue`
     * insert + `current_user` pref) are byte-for-byte identical to the legacy body, and
     * [TargetUserRouter] only routes to the module when user identification is enabled — so
     * the unconditional disable→`user_unassigned` write still lands via the legacy path and
     * no write can be lost.
     */
    public const val USE_MODULE_MANAGER_USER_IDENTIFICATION_PATH: Boolean = true
}
