package com.openlattice.chronicle.collection.identification

import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins the Phase 7A migration-switch value (refactor plan §10.1 step 20, decision #20).
 *
 * The module-manager user-identification path has passed its parity tests and been
 * deliberately activated: [UserIdentificationMigration.USE_MODULE_MANAGER_USER_IDENTIFICATION_PATH]
 * is now `true`, so [TargetUserRouter] routes the *enabled* target-user write through
 * [UserIdentificationCollectionModule] + [TargetUserStore] instead of the legacy inline
 * `EnrollmentSettings.setTargetUser` body. The writes are byte-for-byte identical (the
 * `userQueue` insert + the `current_user` pref), and the router only routes to the module
 * when user identification is enabled, so the unconditional disable→`user_unassigned`
 * write still lands via the legacy path.
 *
 * If this test ever fails because the constant was flipped back to `false`, that revert
 * must be reviewed as its own change with parity evidence, not slipped in with unrelated
 * work — it is the lock on the activated module path.
 */
class UserIdentificationMigrationTest {

    @Test
    fun moduleManagerUserIdentificationPathIsActivated() {
        assertTrue(
            "Phase 7A migration switch must be true (module path activated after parity); " +
                "reverting it requires separate parity review",
            UserIdentificationMigration.USE_MODULE_MANAGER_USER_IDENTIFICATION_PATH,
        )
    }
}
