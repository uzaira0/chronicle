package com.openlattice.chronicle.services.sensors

import com.openlattice.chronicle.collection.sink.FakeSensorSampleDao
import com.openlattice.chronicle.services.upload.LocalUploadDiagnosticsPersistence
import com.openlattice.chronicle.services.upload.LocalUploadDiagnosticsStore
import com.openlattice.chronicle.services.upload.LocalUploadIssueBucket
import com.openlattice.chronicle.storage.SensorSampleDeadLetterEntity
import com.openlattice.chronicle.storage.SensorSampleEntry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.LocalDate

/** Dead-lettered sensor samples must reach the operator, not only logcat. */
class SensorUploadWorkerDelegateTest {
    private class MemoryPersistence : LocalUploadDiagnosticsPersistence {
        var buckets: List<LocalUploadIssueBucket> = emptyList()
        override fun load() = buckets
        override fun save(buckets: List<LocalUploadIssueBucket>) {
            this.buckets = buckets
        }
    }

    private fun sample(id: String) = SensorSampleEntry(
        id = id,
        sensorType = "not-a-sensor",
        timestamp = "not-a-timestamp",
        timezone = "UTC",
        x = null, y = null, z = null, w = null,
        accuracy = null,
        valuesJson = "broken",
    )

    @Test
    fun malformedSamplesProduceAPendingSensorDiagnosticBucket() {
        val dao = FakeSensorSampleDao().apply { insertAll(listOf(sample("a"), sample("b"))) }
        val persistence = MemoryPersistence()
        val store = LocalUploadDiagnosticsStore(persistence)

        quarantineMalformedSensorSamples(
            dao,
            store,
            listOf(
                sample("a") to IllegalArgumentException("secret-bearing message"),
                sample("b") to IllegalStateException(),
            ),
        )

        assertEquals(2, dao.countDeadLetters())
        val bucket = store.pending(LocalDate.now()).single()
        assertEquals("SENSOR", bucket.moduleFamily)
        assertEquals("SENSOR_SAMPLE_QUARANTINED", bucket.issue)
        assertEquals(2, bucket.count)
        assertNull("no exception text leaves the device", bucket.errorType)
        assertEquals(1, store.toWireEvents(listOf(bucket)).size)
    }

    @Test
    fun forcedDeadLetterDropIsCountedForTheOperator() {
        val dao = FakeSensorSampleDao()
        dao.insertDeadLetters(
            (1..5).map { index ->
                SensorSampleDeadLetterEntity(
                    sampleId = "bad-$index",
                    sensorType = "broken",
                    timestamp = "x",
                    timezone = "UTC",
                    x = null, y = null, z = null, w = null,
                    accuracy = null,
                    valuesJson = null,
                    quarantinedAt = index.toString(),
                    reason = "IllegalArgumentException",
                )
            },
        )
        val store = LocalUploadDiagnosticsStore(MemoryPersistence())

        SensorUploadWorkerDelegate.cleanupStaleData(
            dao,
            skipAgeTtl = true,
            maxDeadLetterCount = 2,
            reportDrop = {},
            diagnostics = store,
        )

        val bucket = store.pending(LocalDate.now()).single()
        assertEquals("SENSOR", bucket.moduleFamily)
        assertEquals("SENSOR_DEAD_LETTER_DROPPED", bucket.issue)
        assertEquals(3, bucket.count)
    }
}
