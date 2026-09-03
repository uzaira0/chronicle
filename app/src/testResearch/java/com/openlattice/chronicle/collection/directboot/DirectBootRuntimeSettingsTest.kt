package com.openlattice.chronicle.collection.directboot

import com.openlattice.chronicle.android.AndroidSensorType
import com.openlattice.chronicle.preferences.DirectBootSensorSnapshot
import com.openlattice.chronicle.preferences.DirectBootSensorSnapshot.SensorConfig
import org.junit.Assert.assertEquals
import org.junit.Test

/** The direct-boot [com.openlattice.chronicle.collection.sensors.SensorRuntimeSettings] mirrors the snapshot. */
class DirectBootRuntimeSettingsTest {

    @Test
    fun `delegates set and per-sensor config to the snapshot`() {
        val snapshot = DirectBootSensorSnapshot(InMemorySharedPreferences()) { 0L }
        snapshot.write(
            mapOf(
                AndroidSensorType.accelerometer to SensorConfig(50, 10, 60),
                AndroidSensorType.light to SensorConfig(5, 30, 300),
            ),
        )
        val settings = DirectBootRuntimeSettings(snapshot)

        assertEquals(
            setOf(AndroidSensorType.accelerometer, AndroidSensorType.light),
            settings.enabledSensors(),
        )
        assertEquals(50, settings.samplingRateHz(AndroidSensorType.accelerometer))
        assertEquals(10, settings.dutyCycleActiveSeconds(AndroidSensorType.accelerometer))
        assertEquals(60, settings.dutyCyclePeriodSeconds(AndroidSensorType.accelerometer))
        // A sensor missing from the snapshot answers the shared defaults, like SensorSettings.
        assertEquals(5, settings.samplingRateHz(AndroidSensorType.gyroscope))
        assertEquals(30, settings.dutyCycleActiveSeconds(AndroidSensorType.gyroscope))
        assertEquals(300, settings.dutyCyclePeriodSeconds(AndroidSensorType.gyroscope))
    }
}
