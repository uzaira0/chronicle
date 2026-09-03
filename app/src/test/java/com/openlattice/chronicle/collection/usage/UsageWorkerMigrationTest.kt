package com.openlattice.chronicle.collection.usage

import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins the Phase 4B migration-switch value (refactor plan §7.2 step 8, design §1C.4).
 *
 * The module-manager usage path has passed its parity tests and been deliberately
 * activated: [UsageWorkerMigration.USE_MODULE_MANAGER_USAGE_PATH] is now `true`, so
 * `UsageMonitoringWorker` routes usage collection through `UsageEventsCollectionModule` +
 * `UsageEventSink`. This test fails if the constant is ever flipped back to `false`
 * without a deliberate, separately-reviewed change — it is the lock on the activated
 * module path.
 */
class UsageWorkerMigrationTest {

    @Test
    fun moduleManagerUsagePathIsActivatedSoTheWorkerRunsTheModulePath() {
        assertTrue(
            "UsageWorkerMigration.USE_MODULE_MANAGER_USAGE_PATH must be true — " +
                "the module-manager path is activated after parity (refactor plan §7.2 step 8).",
            UsageWorkerMigration.USE_MODULE_MANAGER_USAGE_PATH,
        )
    }
}
