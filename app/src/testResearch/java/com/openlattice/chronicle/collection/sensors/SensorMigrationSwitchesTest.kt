package com.openlattice.chronicle.collection.sensors

import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins the Phase 6 migration switches' values (refactor plan §9, design §1C.4,
 * mirrors `UsageWorkerMigrationTest` / `LifecycleWorkerMigrationTest`).
 *
 * Each switch is flipped from `false` to `true` as a deliberate, separately-reviewed
 * step once its parity tests pass. This test pins the current activation state so any
 * unreviewed change to a switch fails the build.
 */
class SensorMigrationSwitchesTest {

    @Test
    fun sensorSettingsRefreshSwitchIsActivated() {
        assertTrue(
            "USE_MODULE_MANAGER_SENSOR_SETTINGS_PATH must be true (module path activated after parity)",
            SensorSettingsMigration.USE_MODULE_MANAGER_SENSOR_SETTINGS_PATH,
        )
    }

    @Test
    fun sensorUploadSwitchIsActivated() {
        assertTrue(
            "USE_MODULE_MANAGER_SENSOR_UPLOAD_PATH must be true (module path activated after parity)",
            SensorUploadMigration.USE_MODULE_MANAGER_SENSOR_UPLOAD_PATH,
        )
    }
}
