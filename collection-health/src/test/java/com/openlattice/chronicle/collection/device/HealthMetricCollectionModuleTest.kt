package com.openlattice.chronicle.collection.device

import com.openlattice.chronicle.collection.HealthMetricType
import com.openlattice.chronicle.collection.core.FixedCollectionClock
import com.openlattice.chronicle.collection.core.ModuleResult
import com.openlattice.chronicle.collection.core.NoOpCollectionLog
import com.openlattice.chronicle.collection.sink.HealthMetricSampleSink
import com.openlattice.chronicle.storage.HealthMetricSampleDao
import com.openlattice.chronicle.storage.HealthMetricSampleEntry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class HealthMetricCollectionModuleTest {
    @Test
    fun successfulPersistenceAcknowledgesSourceRead() {
        val source = FakeSource(listOf(reading()))
        val sink = FakeSink(ModuleResult.Ok(1))
        val module = module(source, sink)

        assertEquals(ModuleResult.Ok(1), module.sample())
        assertEquals(1, source.acknowledgments)
        assertEquals(0, source.rejections)
        assertEquals(1, sink.samples.size)
    }

    @Test
    fun failedPersistenceRejectsSourceReadForRetry() {
        val source = FakeSource(listOf(reading()))
        val failure = IllegalStateException("database unavailable")
        val module = module(source, FakeSink(ModuleResult.Failed(failure, "insert failed")))

        assertTrue(module.sample() is ModuleResult.Failed)
        assertEquals(0, source.acknowledgments)
        assertEquals(1, source.rejections)
    }

    @Test
    fun emptySuccessfulReadStillAcknowledgesWindow() {
        val source = FakeSource(emptyList())

        assertEquals(ModuleResult.Ok(0), module(source, FakeSink(ModuleResult.Ok(0))).sample())
        assertEquals(1, source.acknowledgments)
        assertEquals(0, source.rejections)
    }

    @Test
    fun sampleIdIsStableForRetryAndChangesWithSourceRecord() {
        val first = reading(sourceRecordId = "record-1")
        val retry = reading(sourceRecordId = "record-1")
        val different = reading(sourceRecordId = "record-2")

        assertEquals(stableHealthMetricSampleId(first), stableHealthMetricSampleId(retry))
        assertTrue(stableHealthMetricSampleId(first) != stableHealthMetricSampleId(different))
    }

    private fun module(source: HealthMetricSource, sink: HealthMetricSampleSink) =
        HealthMetricCollectionModule(
            sink = sink,
            source = source,
            enrolled = { true },
            clock = FixedCollectionClock(10_000L),
            log = NoOpCollectionLog,
        )

    private fun reading(sourceRecordId: String = "record-1") = HealthMetricReading(
        metricType = HealthMetricType.STEPS,
        value = 42.0,
        unit = "count",
        startMillis = 1_000L,
        endMillis = 2_000L,
        sourcePackage = "com.example.health",
        sourceRecordId = sourceRecordId,
    )

    private class FakeSource(private val readings: List<HealthMetricReading>) : HealthMetricSource {
        var acknowledgments = 0
        var rejections = 0

        override fun read(): List<HealthMetricReading> = readings
        override fun acknowledgeRead() { acknowledgments += 1 }
        override fun rejectRead() { rejections += 1 }
    }

    private class FakeSink(private val result: ModuleResult) : HealthMetricSampleSink(FakeDao()) {
        var samples: List<HealthMetricSampleEntry> = emptyList()

        override fun write(samples: List<HealthMetricSampleEntry>): ModuleResult {
            this.samples = samples
            return result
        }
    }

    private class FakeDao : HealthMetricSampleDao {
        override fun getOldest(limit: Int): List<HealthMetricSampleEntry> = emptyList()
        override fun insertAll(samples: List<HealthMetricSampleEntry>) = Unit
        override fun deleteByIds(ids: List<String>) = Unit
        override fun count(): Int = 0
        override fun deleteOlderThan(cutoffTimestamp: String): Int = 0
        override fun deleteAll() = Unit
    }
}
