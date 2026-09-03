package com.openlattice.chronicle.collection.sink

import com.openlattice.chronicle.collection.core.ModuleResult
import com.openlattice.chronicle.collection.core.NoOpCollectionLog
import com.openlattice.chronicle.collection.core.RecordingCollectionLog
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

private const val DATE = "2026-05-20"
private const val SERVER = 7L

class UploadStatsSinkTest {

    @Test
    fun recordUsageInsertsDayAndIncrementsCounter() {
        val dao = FakeUploadStatsDao()
        val sink = UploadStatsSink(dao, NoOpCollectionLog)

        val result = sink.recordUsageUploaded(SERVER, DATE, 12)

        assertEquals(ModuleResult.Ok(12), result)
        assertEquals(12, dao.rows[SERVER to DATE]!!.usageEventsUploaded)
    }

    @Test
    fun recordSensorInsertsDayAndIncrementsCounter() {
        val dao = FakeUploadStatsDao()
        val sink = UploadStatsSink(dao, NoOpCollectionLog)

        val result = sink.recordSensorUploaded(SERVER, DATE, 30)

        assertEquals(ModuleResult.Ok(30), result)
        assertEquals(30, dao.rows[SERVER to DATE]!!.sensorSamplesUploaded)
    }

    @Test
    fun repeatedRecordsAreIdempotentOnDayRowAndAccumulateCounter() {
        val dao = FakeUploadStatsDao()
        val sink = UploadStatsSink(dao, NoOpCollectionLog)

        sink.recordUsageUploaded(SERVER, DATE, 5)
        sink.recordUsageUploaded(SERVER, DATE, 7)

        // insertDay is a no-op after the first call; counts accumulate.
        assertEquals(1, dao.rows.size)
        assertEquals(12, dao.rows[SERVER to DATE]!!.usageEventsUploaded)
    }

    @Test
    fun zeroCountIsIdempotentOkZeroAndTouchesNoRow() {
        val dao = FakeUploadStatsDao()
        val sink = UploadStatsSink(dao, NoOpCollectionLog)

        assertEquals(ModuleResult.Ok(0), sink.recordUsageUploaded(SERVER, DATE, 0))
        assertEquals(ModuleResult.Ok(0), sink.recordSensorUploaded(SERVER, DATE, 0))
        assertTrue("a zero count must not create a day row", dao.rows.isEmpty())
    }

    @Test
    fun negativeCountIsRejected() {
        val sink = UploadStatsSink(FakeUploadStatsDao(), NoOpCollectionLog)
        try {
            sink.recordUsageUploaded(SERVER, DATE, -1)
            fail("a negative count must be rejected")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message!!.contains("non-negative"))
        }
    }

    @Test
    fun persistenceFailureReturnsFailedAndIsLoggedNotSwallowed() {
        val dao = FakeUploadStatsDao().apply { failNextWrite = true }
        val log = RecordingCollectionLog()
        val sink = UploadStatsSink(dao, log)

        val result = sink.recordSensorUploaded(SERVER, DATE, 4)

        assertTrue("expected Failed, got $result", result is ModuleResult.Failed)
        assertTrue(log.problems.any { it.level == RecordingCollectionLog.Level.ERROR })
    }
}
