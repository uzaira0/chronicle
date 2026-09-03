package com.openlattice.chronicle.services.usage

import com.openlattice.chronicle.collection.usage.UsageWorkerMigration
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Regression guard for the collection-loop ack-gate bypass on the coordinated sync path.
 *
 * Both `UsageMonitoringWorker` (split-periodic) and
 * [com.openlattice.chronicle.services.sync.ChronicleSyncWorker] (coordinated — the strategy the
 * device actually runs) route usage collection through [collectUsage], whose single
 * delegate-selection decision is [selectedUsageCollectionPath]. The coordinated path previously
 * hardcoded the legacy **ungated** `UsageCollectionDelegate`, so usage_events (and the
 * device-state rows that ride in the same write) were collected + uploaded before the participant
 * acknowledged the module — bypassing the gate the periodic path enforced.
 *
 * This test pins that the selected path is the ack-gated module path while the migration switch is
 * active, so neither caller can silently diverge back to the ungated delegate.
 */
class UsageCollectionRoutingTest {

    @Test
    fun bothSyncStrategiesSelectTheAckGatedModulePath() {
        assertTrue(
            "Precondition: the module-manager path is activated (UsageWorkerMigrationTest pins this).",
            UsageWorkerMigration.USE_MODULE_MANAGER_USAGE_PATH,
        )
        assertEquals(
            "collectUsage must select the ack-gated module delegate so the coordinated " +
                "ChronicleSyncWorker enforces the same gate as the split-periodic worker.",
            UsageCollectionPath.MODULE_GATED,
            selectedUsageCollectionPath(),
        )
    }
}
