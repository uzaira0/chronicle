package com.openlattice.chronicle.collection.sink

import com.openlattice.chronicle.collection.core.ModuleResult
import com.openlattice.chronicle.collection.core.NoOpCollectionLog
import com.openlattice.chronicle.collection.core.RecordingCollectionLog
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import com.openlattice.chronicle.storage.UploadStatsEntity

private const val DATE = "2026-05-20"
private const val SERVER = 7L

class UploadStatsSinkTest {
    private fun ownedDay() = UploadStatsEntity(serverId = SERVER, date = DATE,
        studyId = "study", participantId = "participant", deviceId = "device", enrollmentEpoch = "7:created")
    @Test
    fun recordUsageInsertsDayAndIncrementsCounter() {
        val dao = FakeUploadStatsDao()
        val sink = UploadStatsSink(dao, NoOpCollectionLog)

        val result = sink.recordUsageUploaded(ownedDay(), 12)

        assertEquals(ModuleResult.Ok(12), result)
        assertEquals(12, dao.rows[SERVER to DATE]!!.usageEventsUploaded)
    }

    @Test
    fun recordSensorInsertsDayAndIncrementsCounter() {
        val dao = FakeUploadStatsDao()
        val sink = UploadStatsSink(dao, NoOpCollectionLog)

        val result = sink.recordSensorUploaded(ownedDay(), 30)

        assertEquals(ModuleResult.Ok(30), result)
        assertEquals(30, dao.rows[SERVER to DATE]!!.sensorSamplesUploaded)
        assertEquals("7:created", dao.rows[SERVER to DATE]!!.enrollmentEpoch)
    }

    @Test
    fun repeatedRecordsAreIdempotentOnDayRowAndAccumulateCounter() {
        val dao = FakeUploadStatsDao()
        val sink = UploadStatsSink(dao, NoOpCollectionLog)

        sink.recordUsageUploaded(ownedDay(), 5)
        sink.recordUsageUploaded(ownedDay(), 7)

        // insertDay is a no-op after the first call; counts accumulate.
        assertEquals(1, dao.rows.size)
        assertEquals(12, dao.rows[SERVER to DATE]!!.usageEventsUploaded)
    }

    @Test
    fun zeroCountIsIdempotentOkZeroAndTouchesNoRow() {
        val dao = FakeUploadStatsDao()
        val sink = UploadStatsSink(dao, NoOpCollectionLog)

        assertEquals(ModuleResult.Ok(0), sink.recordUsageUploaded(ownedDay(), 0))
        assertEquals(ModuleResult.Ok(0), sink.recordSensorUploaded(ownedDay(), 0))
        assertTrue("a zero count must not create a day row", dao.rows.isEmpty())
    }

    @Test
    fun negativeCountIsRejected() {
        val sink = UploadStatsSink(FakeUploadStatsDao(), NoOpCollectionLog)
        try {
            sink.recordUsageUploaded(ownedDay(), -1)
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

        val result = sink.recordSensorUploaded(ownedDay(), 4)

        assertTrue("expected Failed, got $result", result is ModuleResult.Failed)
        assertTrue(log.problems.any { it.level == RecordingCollectionLog.Level.ERROR })
    }

    @Test
    fun nullOwnedLegacyDayIsBackfilledAndOtherEnrollmentIsExcluded() {
        val dao = FakeUploadStatsDao()
        dao.insertDay(UploadStatsEntity(serverId = SERVER, date = DATE, sensorSamplesUploaded = 2))
        UploadStatsSink(dao, NoOpCollectionLog).recordSensorUploaded(ownedDay(), 3)
        dao.insertDay(ownedDay().copy(serverId = 8L, enrollmentEpoch = "8:other", sensorSamplesUploaded = 99))

        assertEquals("7:created", dao.rows[SERVER to DATE]!!.enrollmentEpoch)
        assertEquals(5, dao.sensorUploadedOn(DATE, SERVER, "7:created"))
        assertEquals(1, dao.getRecentStats(SERVER, "7:created", 7).size)
        assertTrue(dao.getRecentStats(SERVER, "other", 7).isEmpty())
    }
}
