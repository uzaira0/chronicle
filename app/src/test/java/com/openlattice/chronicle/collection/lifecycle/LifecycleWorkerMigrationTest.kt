package com.openlattice.chronicle.collection.lifecycle

import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins the Phase 5B migration switch value (refactor plan §8.2 step 2, decision #20).
 *
 * [LifecycleWorkerMigration.USE_MODULE_MANAGER_LIFECYCLE_PATH] has been deliberately
 * activated after its parity tests passed: it is now `true`, so
 * `DeviceLifecycleEventRecorder.recordAsync` routes recorded events through
 * `DeviceLifecycleCollectionModule` + `LifecycleEventSink` instead of the legacy inline
 * `recordNow`.
 *
 * If this test ever fails because the constant was flipped back to `false`, that revert
 * must be reviewed as its own change, not slipped in with unrelated work.
 */
class LifecycleWorkerMigrationTest {

    @Test
    fun moduleManagerLifecyclePathIsActivated() {
        assertTrue(
            "Phase 5B migration switch must be true (module path activated after parity); " +
                "reverting it requires separate review",
            LifecycleWorkerMigration.USE_MODULE_MANAGER_LIFECYCLE_PATH,
        )
    }
}
