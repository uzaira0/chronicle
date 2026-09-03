package com.openlattice.chronicle.collection.sink

import com.openlattice.chronicle.collection.core.ModuleResult
import com.openlattice.chronicle.collection.core.NoOpCollectionLog
import com.openlattice.chronicle.collection.core.RecordingCollectionLog
import com.openlattice.chronicle.storage.SensorSampleEntry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SensorSampleSinkTest {

    private fun sample(id: String): SensorSampleEntry = SensorSampleEntry(
        id = id,
        sensorType = "ACCELEROMETER",
        timestamp = "2026-05-20T00:00:00Z",
        timezone = "UTC",
        x = 0.1f, y = 0.2f, z = 0.3f, w = null,
        accuracy = 3,
        valuesJson = null,
    )

    @Test
    fun successfulWriteReturnsOkWithCount() {
        val dao = FakeSensorSampleDao()
        val sink = SensorSampleSink(dao, NoOpCollectionLog)

        val result = sink.write(listOf(sample("a"), sample("b")))

        assertEquals(ModuleResult.Ok(2), result)
        assertEquals(2, dao.count())
    }

    @Test
    fun emptyWriteIsIdempotentOkZeroAndTouchesNothing() {
        val dao = FakeSensorSampleDao()
        val sink = SensorSampleSink(dao, NoOpCollectionLog)

        assertEquals(ModuleResult.Ok(0), sink.write(emptyList()))
        assertEquals(0, dao.count())
    }

    @Test
    fun persistenceFailureReturnsFailedAndIsLoggedNotSwallowed() {
        val dao = FakeSensorSampleDao().apply { failNextInsert = true }
        val log = RecordingCollectionLog()
        val sink = SensorSampleSink(dao, log)

        val result = sink.write(listOf(sample("a")))

        assertTrue("expected Failed, got $result", result is ModuleResult.Failed)
        assertTrue(log.problems.any { it.level == RecordingCollectionLog.Level.ERROR })
    }

    @Test
    fun duplicateWriteIsOkBecauseSensorSamplesIgnoresConflicts() {
        // sensor_samples uses OnConflictStrategy.IGNORE — a duplicate id is silently
        // de-duplicated, NOT a failure (contrast with UsageEventSink).
        val dao = FakeSensorSampleDao()
        val sink = SensorSampleSink(dao, NoOpCollectionLog)

        assertEquals(ModuleResult.Ok(1), sink.write(listOf(sample("dup"))))
        val duplicate = sink.write(listOf(sample("dup")))

        assertTrue("expected Ok on duplicate id, got $duplicate", duplicate is ModuleResult.Ok)
        assertEquals(1, dao.count())
    }

    @Test
    fun queueDepthReflectsRowCount() {
        val dao = FakeSensorSampleDao()
        val sink = SensorSampleSink(dao, NoOpCollectionLog)
        assertEquals(0, sink.queueDepth())
        sink.write(listOf(sample("a"), sample("b")))
        assertEquals(2, sink.queueDepth())
    }

    @Test
    fun sensorSampleEntrySerializationIsPreserved() {
        val dao = FakeSensorSampleDao()
        val sink = SensorSampleSink(dao, NoOpCollectionLog)
        val original = sample("keep")
        sink.write(listOf(original))
        assertEquals(original, dao.getOldest(1).single())
    }
}
