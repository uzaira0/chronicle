package com.openlattice.chronicle.collection.directboot

import com.openlattice.chronicle.android.AndroidSensorType
import com.openlattice.chronicle.collection.core.ModuleResult
import com.openlattice.chronicle.collection.core.NoOpCollectionLog
import com.openlattice.chronicle.collection.sink.SensorSampleWriter
import com.openlattice.chronicle.collection.state.ResearchPersistenceGate
import com.openlattice.chronicle.storage.SensorSampleEntry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

/**
 * The drain-time consent gate: buffered pre-unlock samples must re-pass their sensor's
 * collection gate before touching `sensor_samples` — consent/settings may have changed
 * between the locked-window collection and the drain (withdrawal, DISCARD_AND_STOP, a
 * server-side disable). Mirrors the live runtime's gated flush.
 */
class DirectBootDrainGateTest {

    private fun sample(id: String, sensorType: String) = SensorSampleEntry(
        id = id,
        sensorType = sensorType,
        timestamp = "2026-07-15T18:18:09.282Z",
        timezone = "UTC",
        x = 1f,
        y = 2f,
        z = 3f,
        w = null,
        accuracy = 3,
    )

    @Test
    fun `closed-gate samples wait and unparseable sensors are erased while open-gate samples transfer`() {
        val written = mutableListOf<SensorSampleEntry>()
        val sink = SensorSampleWriter { samples ->
            written.addAll(samples)
            ModuleResult.Ok(samples.size)
        }

        val result = DirectBootDrainWorker.persistGated(
            samples = listOf(
                sample("open", AndroidSensorType.accelerometer.name),
                sample("closed", AndroidSensorType.light.name),
                sample("unknown", "NO_SUCH_SENSOR"),
            ),
            sinkFor = { type -> if (type == AndroidSensorType.accelerometer) sink else SensorSampleWriter { ModuleResult.Skipped("hold") } },
            log = NoOpCollectionLog,
        )

        assertEquals(listOf("open"), written.map { it.id })
        assertEquals(setOf("open"), result.transferredIds)
        assertEquals(setOf("unknown"), result.discardedIds)
    }

    @Test
    fun `all-refused batch transfers no samples`() {
        val sink = SensorSampleWriter { ModuleResult.Skipped("hold") }

        val result = DirectBootDrainWorker.persistGated(
            samples = listOf(sample("closed", AndroidSensorType.light.name)),
            sinkFor = { sink },
            log = NoOpCollectionLog,
        )

        assertEquals(emptySet<String>(), result.transferredIds)
    }

    @Test
    fun `reenrollment changes owner inside persistence lease and blocks insert`() {
        var owner = "enrollment-A"
        var inserted = false
        val guard = ResearchPersistenceGate.expectedOwnerGuard("enrollment-A", { owner }, { true })
        owner = "enrollment-B"

        assertFalse(guard.persist { inserted = true })
        assertFalse(inserted)
    }
}
