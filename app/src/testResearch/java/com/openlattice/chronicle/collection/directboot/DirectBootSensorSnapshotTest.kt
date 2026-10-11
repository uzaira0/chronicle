package com.openlattice.chronicle.collection.directboot

import com.openlattice.chronicle.android.AndroidSensorType
import com.openlattice.chronicle.preferences.DirectBootSensorSnapshot
import com.openlattice.chronicle.preferences.DirectBootSensorSnapshot.SensorConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * JVM coverage for the device-protected direct-boot snapshot — the only collection state
 * readable between reboot and first unlock. Drives it over [InMemorySharedPreferences]
 * with a fixed clock; no Android `Context`.
 */
class DirectBootSensorSnapshotTest {

    private var now = 1_000_000L
    private val prefs = InMemorySharedPreferences()
    private val snapshot = DirectBootSensorSnapshot(prefs) { now }

    @Test
    fun writeAndReadRoundTripsTheCollectableSetAndPerSensorConfig() {
        val written = snapshot.write(
            mapOf(
                AndroidSensorType.accelerometer to SensorConfig(50, 10, 60),
                AndroidSensorType.light to SensorConfig(5, 30, 300),
            ),
            settingsVersion = 7,
        )

        assertTrue(written)
        assertEquals(7, snapshot.settingsVersion())
        assertEquals(
            setOf(AndroidSensorType.accelerometer, AndroidSensorType.light),
            snapshot.collectableSensors(),
        )
        assertEquals(SensorConfig(50, 10, 60), snapshot.config(AndroidSensorType.accelerometer))
        assertEquals(SensorConfig(5, 30, 300), snapshot.config(AndroidSensorType.light))
    }

    @Test
    fun rewriteDropsSensorsAndConfigsNoLongerCollectable() {
        snapshot.write(
            mapOf(
                AndroidSensorType.accelerometer to SensorConfig(50, 10, 60),
                AndroidSensorType.light to SensorConfig(7, 20, 120),
            ),
        )
        snapshot.write(mapOf(AndroidSensorType.light to SensorConfig(5, 30, 300)))
        assertNull(snapshot.settingsVersion())

        assertEquals(setOf(AndroidSensorType.light), snapshot.collectableSensors())
        // The dropped sensor's stale overrides are gone: reads fall back to defaults.
        assertEquals(SensorConfig(), snapshot.config(AndroidSensorType.accelerometer))
    }

    @Test
    fun unwrittenSnapshotIsUnusableAndHasNoAge() {
        assertNull(snapshot.ageMillis())
        assertFalse(snapshot.isUsableFor(Long.MAX_VALUE))
        // Reads still answer with safe defaults.
        assertEquals(emptySet<AndroidSensorType>(), snapshot.collectableSensors())
        assertEquals(SensorConfig(), snapshot.config(AndroidSensorType.gyroscope))
    }

    @Test
    fun emptyCollectableSetIsAValidSnapshotButNeverUsable() {
        snapshot.write(emptyMap())

        assertEquals(0L, snapshot.ageMillis())
        assertFalse(snapshot.isUsableFor(Long.MAX_VALUE))
    }

    @Test
    fun stalenessBoundFailsClosed() {
        snapshot.write(mapOf(AndroidSensorType.accelerometer to SensorConfig()))
        assertTrue(snapshot.isUsableFor(DirectBootSensorSnapshot.MAX_SNAPSHOT_AGE_MILLIS))

        now += DirectBootSensorSnapshot.MAX_SNAPSHOT_AGE_MILLIS + 1

        assertFalse(snapshot.isUsableFor(DirectBootSensorSnapshot.MAX_SNAPSHOT_AGE_MILLIS))
    }

    @Test
    fun clearRemovesEverything() {
        snapshot.write(mapOf(AndroidSensorType.accelerometer to SensorConfig(50, 10, 60)))

        assertTrue(snapshot.clear())

        assertNull(snapshot.ageMillis())
        assertEquals(emptySet<AndroidSensorType>(), snapshot.collectableSensors())
        assertFalse(snapshot.isUsableFor(Long.MAX_VALUE))
    }

    @Test
    fun unknownPersistedSensorNamesAreIgnoredNotFatal() {
        snapshot.write(mapOf(AndroidSensorType.accelerometer to SensorConfig()))
        // Simulate a persisted name from a build with a since-removed enum constant.
        prefs.edit().putStringSet("db_sensors", mutableSetOf("accelerometer", "NO_SUCH_SENSOR")).commit()

        assertEquals(setOf(AndroidSensorType.accelerometer), snapshot.collectableSensors())
    }
}
