package com.openlattice.chronicle.collection.sensors

import com.openlattice.chronicle.collection.sink.FakeSensorSampleDao
import com.openlattice.chronicle.services.sensors.SENSOR_RETENTION_CAP_SAMPLES
import com.openlattice.chronicle.services.sensors.SensorUploadWorkerDelegate
import com.openlattice.chronicle.services.sensors.WORST_CASE_SENSOR_SAMPLES_PER_SECOND
import com.openlattice.chronicle.services.sensors.shouldSkipSensorAgeTtl
import com.openlattice.chronicle.storage.SensorSampleDeadLetterEntity
import com.openlattice.chronicle.storage.SensorSampleEntry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

private fun oldSample(id: String): SensorSampleEntry = SensorSampleEntry(
    id = id,
    sensorType = "ACCELEROMETER",
    // Far in the past: lexicographically below any (now - 7d) ISO-8601 cutoff, so the age TTL
    // would purge it if it ran.
    timestamp = "2000-01-01T00:00:00Z",
    timezone = "UTC",
    x = 0.1f, y = 0.2f, z = 0.3f, w = null,
    accuracy = 3,
    valuesJson = null,
)

/**
 * JVM unit coverage for the Phase 6C sensor-upload cleanup logic
 * ([SensorUploadWorkerDelegate.cleanupStaleData]) and the [SensorUploadModule] result
 * shape.
 *
 * The full multi-server `execute()` loop drives `ChronicleStudyApi` network calls and is
 * exercised in the instrumented test; this JVM test proves the pieces that need no
 * Android runtime:
 *  - cleanup on an empty DB is a no-op;
 *  - the [SensorUploadResult] surfaces the per-server failure count and the
 *    malformed-sample count (corrupt rows are quarantined and counted).
 *
 * Real SQLCipher behavior is also covered by [HardwareSensorsModuleInstrumentedTest].
 */
class SensorUploadCleanupTest {

    @Test
    fun cleanupOnAnEmptyDbIsANoOp() {
        // No rows deleted ⇒ no Log.w ⇒ JVM-safe; proves the cleanup tolerates an empty DB.
        val dao = FakeSensorSampleDao()
        SensorUploadWorkerDelegate.cleanupStaleData(dao)
        assertEquals(0, dao.count())
    }

    @Test
    fun skipAgeTtlRetainsSamplesPastTheTtl() {
        // HIPAA-2028 W2 fail-closed: when a study is fail-closed (e2ee required, key pending) its
        // pending PHI is retained for retry, so the age TTL must NOT drop it even though the rows
        // are well past the 7-day cutoff. (the bounded age delete is skipped ⇒ no Log.w ⇒ rows
        // stay below the count cap so that path is also silent.)
        val dao = FakeSensorSampleDao()
        dao.insertAll((1..5).map { oldSample("old-$it") })

        SensorUploadWorkerDelegate.cleanupStaleData(dao, skipAgeTtl = true)

        assertEquals("fail-closed must retain all pending samples, none purged by age", 5, dao.count())
    }

    @Test
    fun pausedDestinationPolicySkipsAgeTtlIncludingWhenAllDestinationsArePaused() {
        assertTrue(
            shouldSkipSensorAgeTtl(
                hasEnabledDestination = false,
                hasPausedDestination = true,
                anyFailClosedDestination = false,
            ),
        )
        assertTrue(
            shouldSkipSensorAgeTtl(
                hasEnabledDestination = false,
                hasPausedDestination = false,
                anyFailClosedDestination = false,
            ),
        )
    }

    @Test
    fun retentionExpiryIsReportedAsPermanentLoss() {
        val dao = FakeSensorSampleDao().apply {
            insertAll((1..5).map { oldSample("expired-$it") })
        }
        val reports = mutableListOf<String>()

        val result = SensorUploadWorkerDelegate.cleanupStaleData(
            dao,
            deleteChunkSize = 2,
            reportDrop = reports::add,
        )

        assertEquals(5, result.retentionExpiredDropCount)
        assertEquals(0, dao.count())
        assertEquals(listOf(2, 2, 2), dao.deleteOldestBeforeRequests)
        assertTrue(reports.single().contains("RETENTION DROP"))
        assertTrue(reports.single().contains("delivery is not guaranteed"))
    }

    @Test
    fun capacityBoundReportsForcedLossOfPotentiallyHeldSamples() {
        val dao = FakeSensorSampleDao().apply {
            insertAll((1..7).map { oldSample("held-$it") })
        }
        val reports = mutableListOf<String>()

        val result = SensorUploadWorkerDelegate.cleanupStaleData(
            dao,
            skipAgeTtl = true,
            maxSampleCount = 2,
            deleteChunkSize = 2,
            reportDrop = reports::add,
        )

        assertEquals(5, result.capacityForcedDropCount)
        assertEquals(2, dao.count())
        assertEquals(listOf(2, 2, 1), dao.deleteOldestRequests)
        assertTrue(reports.single().contains("FORCED CAPACITY DROP"))
        assertTrue(reports.single().contains("paused destination"))
    }

    @Test
    fun defaultCapacityCoversTheConfiguredSevenDayWorstCaseEnvelope() {
        val worstCaseSevenDays = WORST_CASE_SENSOR_SAMPLES_PER_SECOND * 60 * 60 * 24 * 7

        assertEquals(3_628_800, worstCaseSevenDays)
        assertTrue(SENSOR_RETENTION_CAP_SAMPLES >= worstCaseSevenDays)
    }

    @Test
    fun malformedDeadLetterStoreHasItsOwnReportedDosBound() {
        val dao = FakeSensorSampleDao()
        dao.insertDeadLetters((1..7).map { index -> deadLetter("bad-$index", index.toString()) })
        val reports = mutableListOf<String>()

        val result = SensorUploadWorkerDelegate.cleanupStaleData(
            dao,
            skipAgeTtl = true,
            maxDeadLetterCount = 2,
            deleteChunkSize = 2,
            reportDrop = reports::add,
        )

        assertEquals(5, result.deadLetterForcedDropCount)
        assertEquals(2, dao.countDeadLetters())
        assertEquals(listOf(2, 2, 1), dao.deleteOldestDeadLetterRequests)
        assertTrue(reports.single().contains("FORCED DEAD-LETTER DROP"))
    }

    @Test
    fun sensorUploadResultReportsFullSuccessWhenNoServerFailed() {
        val result = SensorUploadResult(serverFailureCount = 0, malformedSampleCount = 0)
        assertTrue(result.isFullSuccess)
    }

    @Test
    fun sensorUploadResultIsNotFullSuccessWhenAServerFailed() {
        val result = SensorUploadResult(serverFailureCount = 2, malformedSampleCount = 0)
        assertEquals(false, result.isFullSuccess)
    }

    @Test
    fun sensorUploadResultCarriesTheMalformedSampleCountForDiagnostics() {
        // Corrupt samples are quarantined; the count is surfaced, not swallowed.
        val result = SensorUploadResult(serverFailureCount = 0, malformedSampleCount = 7)
        assertEquals(7, result.malformedSampleCount)
        assertTrue("quarantined samples do not by themselves fail destination delivery", result.isFullSuccess)
    }

    private fun deadLetter(id: String, quarantinedAt: String) = SensorSampleDeadLetterEntity(
        sampleId = id,
        sensorType = "broken",
        timestamp = "not-a-timestamp",
        timezone = "UTC",
        x = null,
        y = null,
        z = null,
        w = null,
        accuracy = null,
        valuesJson = "broken",
        quarantinedAt = quarantinedAt,
        reason = "IllegalArgumentException",
    )
}
