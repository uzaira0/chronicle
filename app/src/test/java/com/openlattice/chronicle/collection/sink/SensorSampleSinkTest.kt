package com.openlattice.chronicle.collection.sink

import com.openlattice.chronicle.collection.core.ModuleResult
import com.openlattice.chronicle.storage.SensorSampleEntry
import com.openlattice.chronicle.collection.state.CollectionPersistenceGuard
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
        val sink = SensorSampleSink(dao)

        val result = sink.write(listOf(sample("a"), sample("b")))

        assertEquals(ModuleResult.Ok(2), result)
        assertEquals(2, dao.count())
    }

    @Test
    fun emptyWriteIsIdempotentOkZeroAndTouchesNothing() {
        val dao = FakeSensorSampleDao()
        val sink = SensorSampleSink(dao)

        assertEquals(ModuleResult.Ok(0), sink.write(emptyList()))
        assertEquals(0, dao.count())
    }

    @Test
    fun persistenceFailureReturnsFailedNotSwallowed() {
        val dao = FakeSensorSampleDao().apply { failNextInsert = true }
        val sink = SensorSampleSink(dao)

        val result = sink.write(listOf(sample("a")))

        assertTrue("expected Failed, got $result", result is ModuleResult.Failed)
    }

    @Test
    fun guardWriteExceptionReturnsFailedSoRuntimeCanRetry() {
        val dao = FakeSensorSampleDao()
        val sink = SensorSampleSink(dao,
            persistenceGuard = CollectionPersistenceGuard { persist ->
                persist()
                error("commit outcome unknown")
            })
        assertTrue(sink.write(listOf(sample("a"))) is ModuleResult.Failed)
    }

    /** A guard whose capture windows cover the samples named [inside]. */
    private fun windowGuard(vararg inside: String) = object : CollectionPersistenceGuard {
        override fun persist(persist: () -> Unit): Boolean {
            persist()
            return true
        }

        override fun <T> outsideCaptureWindows(rows: List<T>, capturedAt: (T) -> Long?): List<T> =
            rows.filterNot { (it as SensorSampleEntry).id in inside }
    }

    @Test
    fun samplesInsideACaptureWindowAreDroppedByRuleNotRefused() {
        // Skipped would make the runtime requeue the current samples, and a pause never retires them: a flush loop.
        val dao = FakeSensorSampleDao()
        assertEquals(ModuleResult.Ok(0), SensorSampleSink(dao, windowGuard("a", "b")).write(listOf(sample("a"), sample("b"))))
        assertEquals(0, dao.count())

        assertEquals(ModuleResult.Ok(1), SensorSampleSink(dao, windowGuard("a")).write(listOf(sample("a"), sample("c"))))
        assertEquals(listOf("c"), dao.getOldest(10).map { it.id })
    }

    @Test
    fun retiredSamplesOutsideEveryWindowAreStillRefused() {
        val sink = SensorSampleSink(FakeSensorSampleDao(), windowGuard("a"))
        assertTrue(sink.writeCurrent(listOf(sample("a"), sample("b"))) { false } is ModuleResult.Skipped)
    }

    @Test
    fun duplicateWriteIsOkBecauseSensorSamplesIgnoresConflicts() {
        // sensor_samples uses OnConflictStrategy.IGNORE — a duplicate id is silently
        // de-duplicated, NOT a failure (contrast with UsageEventSink).
        val dao = FakeSensorSampleDao()
        val sink = SensorSampleSink(dao)

        assertEquals(ModuleResult.Ok(1), sink.write(listOf(sample("dup"))))
        val duplicate = sink.write(listOf(sample("dup")))

        assertTrue("expected Ok on duplicate id, got $duplicate", duplicate is ModuleResult.Ok)
        assertEquals(1, dao.count())
    }

    @Test
    fun queueDepthReflectsRowCount() {
        val dao = FakeSensorSampleDao()
        val sink = SensorSampleSink(dao)
        assertEquals(0, sink.queueDepth())
        sink.write(listOf(sample("a"), sample("b")))
        assertEquals(2, sink.queueDepth())
    }

    @Test
    fun sensorSampleEntrySerializationIsPreserved() {
        val dao = FakeSensorSampleDao()
        val sink = SensorSampleSink(dao)
        val original = sample("keep")
        sink.write(listOf(original))
        assertEquals(original, dao.getOldest(1).single())
    }
}
