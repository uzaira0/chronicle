package com.openlattice.chronicle.collection.upload

import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins the Phase 8 migration switch value (refactor plan §11.2, design §1C.4 —
 * mirrors `SensorMigrationSwitchesTest` / `UsageWorkerMigrationTest`).
 *
 * The combined-upload orchestrator switch has been deliberately activated after its
 * parity tests passed: it is now `true`, so `runCombinedUpload` routes the delegate
 * outcomes through the extracted [runCombinedUploadCore]. This test fails if the
 * constant is ever reverted to `false` without a deliberate, separately-reviewed change.
 *
 */
class UploadTelemetryMigrationTest {

    @Test
    fun combinedUploadOrchestratorSwitchIsActivated() {
        assertTrue(
            "USE_COMBINED_UPLOAD_ORCHESTRATOR must be true (orchestrator activated after parity)",
            UploadTelemetryMigration.USE_COMBINED_UPLOAD_ORCHESTRATOR,
        )
    }
}
