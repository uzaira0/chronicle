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
    fun `write and read round-trips the collectable set and per-sensor config`() {
        val written = snapshot.write(
            mapOf(
                AndroidSensorType.accelerometer to SensorConfig(50, 10, 60),
                AndroidSensorType.light to SensorConfig(5, 30, 300),
            ),
        )

        assertTrue(written)
        assertEquals(
            setOf(AndroidSensorType.accelerometer, AndroidSensorType.light),
            snapshot.collectableSensors(),
        )
        assertEquals(SensorConfig(50, 10, 60), snapshot.config(AndroidSensorType.accelerometer))
        assertEquals(SensorConfig(5, 30, 300), snapshot.config(AndroidSensorType.light))
    }

    @Test
    fun `rewrite drops sensors and configs no longer collectable`() {
        snapshot.write(
            mapOf(
                AndroidSensorType.accelerometer to SensorConfig(50, 10, 60),
                AndroidSensorType.light to SensorConfig(7, 20, 120),
            ),
        )
        snapshot.write(mapOf(AndroidSensorType.light to SensorConfig(5, 30, 300)))

        assertEquals(setOf(AndroidSensorType.light), snapshot.collectableSensors())
        // The dropped sensor's stale overrides are gone: reads fall back to defaults.
        assertEquals(SensorConfig(), snapshot.config(AndroidSensorType.accelerometer))
    }

    @Test
    fun `unwritten snapshot is unusable and has no age`() {
        assertNull(snapshot.ageMillis())
        assertFalse(snapshot.isUsableFor(Long.MAX_VALUE))
        // Reads still answer with safe defaults.
        assertEquals(emptySet<AndroidSensorType>(), snapshot.collectableSensors())
        assertEquals(SensorConfig(), snapshot.config(AndroidSensorType.gyroscope))
    }

    @Test
    fun `empty collectable set is a valid snapshot but never usable`() {
        snapshot.write(emptyMap())

        assertEquals(0L, snapshot.ageMillis())
        assertFalse(snapshot.isUsableFor(Long.MAX_VALUE))
    }

    @Test
    fun `staleness bound fails closed`() {
        snapshot.write(mapOf(AndroidSensorType.accelerometer to SensorConfig()))
        assertTrue(snapshot.isUsableFor(DirectBootSensorSnapshot.MAX_SNAPSHOT_AGE_MILLIS))

        now += DirectBootSensorSnapshot.MAX_SNAPSHOT_AGE_MILLIS + 1

        assertFalse(snapshot.isUsableFor(DirectBootSensorSnapshot.MAX_SNAPSHOT_AGE_MILLIS))
    }

    @Test
    fun `clear removes everything`() {
        snapshot.write(mapOf(AndroidSensorType.accelerometer to SensorConfig(50, 10, 60)))

        assertTrue(snapshot.clear())

        assertNull(snapshot.ageMillis())
        assertEquals(emptySet<AndroidSensorType>(), snapshot.collectableSensors())
        assertFalse(snapshot.isUsableFor(Long.MAX_VALUE))
    }

    @Test
    fun `unknown persisted sensor names are ignored, not fatal`() {
        snapshot.write(mapOf(AndroidSensorType.accelerometer to SensorConfig()))
        // Simulate a persisted name from a build with a since-removed enum constant.
        prefs.edit().putStringSet("db_sensors", mutableSetOf("accelerometer", "NO_SUCH_SENSOR")).commit()

        assertEquals(setOf(AndroidSensorType.accelerometer), snapshot.collectableSensors())
    }
}
