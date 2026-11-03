package com.openlattice.chronicle.services.upload

import android.util.Log
import com.openlattice.chronicle.EnrollmentTestStores
import com.openlattice.chronicle.collection.state.onPersistenceWorker
import com.openlattice.chronicle.models.ExtractedUsageEvent
import com.openlattice.chronicle.serialization.JsonSerializer
import com.openlattice.chronicle.storage.MAX_PERSISTED_QUEUE_PAYLOAD_BYTES
import com.openlattice.chronicle.storage.QueueEntry
import com.openlattice.chronicle.storage.SensorSampleEntry
import com.openlattice.chronicle.storage.UploadQueueTable
import com.openlattice.chronicle.storage.updateUsageData
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.util.UUID
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.robolectric.shadows.ShadowLog

class UploadSizeRegressionTest : UsageUploadFixture() {
    @Test fun admissionMeasuresStoredUtf8RatherThanEscapedJson() {
        val sample = SensorSampleEntry("quoted", "accelerometer", "2026-09-01T00:00:00Z", "UTC",
            1f, 2f, 3f, null, 3, "\"".repeat(MAX_PERSISTED_QUEUE_PAYLOAD_BYTES / 2))
        db.sensorSampleDao().insertAll(listOf(sample))
        assertEquals(1, db.sensorSampleDao().count())
        assertTrue(sample == db.sensorSampleDao().getOldest(1).single())
    }

    @Test fun staleUpdateCannotRecreateAnErasedUsageRow() {
        val row = validEntry(1)
        db.queueEntryData().insertEntry(row)
        db.queueEntryData().deleteEntry(row)
        assertThrows(IllegalStateException::class.java) { db.updateUsageData(row, ByteArray(MAX_PERSISTED_QUEUE_PAYLOAD_BYTES + 1)) }
        assertEquals(0, db.queueEntryData().getSize())
        assertEquals(0, db.localDataQuarantineDao().count("dataQueue"))
    }

    @Test fun oversizedUsageUpdateSplitsRowsAndKeepsCaptureEvidence() {
        applyUsagePolicy(1)
        val row = validEntry(1)
        db.queueEntryData().insertEntry(row)
        overrideCaptureVersion(9)
        val sample = JsonSerializer.deserializeQueueEntry(row.data).single() as ExtractedUsageEvent
        val raw = JsonSerializer.serializeQueueEntry(List(12) { sample.copy(applicationLabel = "é".repeat(100_000)) })
        db.updateUsageData(row, raw)
        val rows = db.queueEntryData().getNextEntries(100)
        assertTrue(rows.size > 1)
        assertTrue(rows.all { it.data.size <= MAX_PERSISTED_QUEUE_PAYLOAD_BYTES })
        assertEquals(12, rows.sumOf { JsonSerializer.deserializeQueueEntry(it.data).size })
        assertTrue(db.uploadQueueEvidenceDao().get(UploadQueueTable.USAGE.name,
            rows.map { "${it.writeTimestamp}:${it.id}" }).all { it.settingsVersion == 1 })
    }

    @Test fun failedUsageAttemptHasOneErrorLogWithItsCause() = onPersistenceWorker {
        val previous = EnrollmentTestStores.db
        EnrollmentTestStores.db = db
        try {
            val error = IOException("synthetic usage failure")
            db.queueEntryData().insertEntry(validEntry(1))
            val failures = executor { throw error }.uploadForServer(server, { false }) { cause ->
                handleServerUploadFailure(context, "UsageFailureTest", cause,
                    LocalUploadModuleFamily.USAGE_LIFECYCLE, 0, updateStatus = { _, _ -> })
            }
            assertEquals(1, failures)
            val label = LocalUploadModuleFamily.USAGE_LIFECYCLE.uploadLabel
            val errors = ShadowLog.getLogs().filter { it.type == Log.ERROR && it.msg == "Failed to upload $label" }
            assertEquals(1, errors.size)
            assertTrue(generateSequence(errors.single().throwable) { it.cause }.any { it === error })
        } finally { EnrollmentTestStores.db = previous }
    }

    @Test fun indivisibleOversizedSampleIsArchivedByTheDaoWriter() {
        val raw = "é".repeat(MAX_PERSISTED_QUEUE_PAYLOAD_BYTES)
        val sample = SensorSampleEntry(UUID.randomUUID().toString(), "accelerometer", "2026-09-01T00:00:00Z", "UTC",
            1f, 2f, 3f, null, 3, raw)
        db.sensorSampleDao().insertAll(listOf(sample))
        assertEquals(0, db.sensorSampleDao().count())
        assertEquals(raw, JSONObject(quarantineBytes("sensor_samples").toString(Charsets.UTF_8)).getString("valuesJson"))
    }

    private fun quarantineBytes(table: String): ByteArray {
        val sql = db.openHelper.readableDatabase
        val key = sql.query("SELECT rowid, length(rawData) FROM local_data_quarantine WHERE sourceTable=?", arrayOf(table)).use {
            assertTrue(it.moveToFirst()); it.getLong(0) to it.getInt(1)
        }
        val output = ByteArrayOutputStream()
        for (offset in 0 until key.second step 65536) sql.query("SELECT substr(rawData,?,65536) FROM local_data_quarantine WHERE rowid=?",
            arrayOf<Any>(offset + 1, key.first)).use { assertTrue(it.moveToFirst()); output.write(it.getBlob(0)) }
        return output.toByteArray()
    }

    @Test fun oversizedUsageWriterSplitsEncodedSamples() {
        val ordinary = JsonSerializer.deserializeQueueEntry(validEntry(1).data).single() as ExtractedUsageEvent
        val samples = List(12) { ordinary.copy(applicationLabel = "é".repeat(100_000)) }
        db.queueEntryData().insertEntry(QueueEntry(1001, 1, JsonSerializer.serializeQueueEntry(samples)))
        val lengths = db.openHelper.readableDatabase.query("SELECT length(data) FROM dataQueue").use {
            buildList { while (it.moveToNext()) add(it.getInt(0)) }
        }
        assertTrue(lengths.size > 1)
        assertTrue(lengths.all { it <= MAX_PERSISTED_QUEUE_PAYLOAD_BYTES })
        assertEquals(samples.size, db.queueEntryData().getNextEntries(100).sumOf { JsonSerializer.deserializeQueueEntry(it.data).size })
    }

    @Test fun oversizedSampleQuarantineExcludesItsQueueSiblings() {
        val ordinary = JsonSerializer.deserializeQueueEntry(validEntry(1).data)
            .filterIsInstance<ExtractedUsageEvent>().single()
        val oversized = ordinary.copy(applicationLabel = "server-oversized")
        db.queueEntryData().insertEntry(QueueEntry(1001, 1,
            JsonSerializer.serializeQueueEntry(listOf(oversized, ordinary))))

        executor { rows ->
            if (rows.any { it.applicationLabel == oversized.applicationLabel }) httpError(HttpURLConnection.HTTP_ENTITY_TOO_LARGE)
            accepted(rows)
        }.run()

        assertEquals(listOf(2, 1, 1), usagePosts)
        assertEquals(0, db.queueEntryData().getSize())
        val quarantined = db.localDataQuarantineDao().forSource(UploadQueueTable.USAGE.tableName).single()
        val samples = JsonSerializer.deserializeQueueEntry(quarantined.rawData)
        assertEquals("only the oversized sample belongs in quarantine", 1, samples.size)
        assertEquals(oversized.applicationLabel, (samples.single() as ExtractedUsageEvent).applicationLabel)
        val logs = ShadowLog.getLogs()
        assertTrue(logs.any { it.type == Log.INFO && it.msg == "Uploaded 1 ${UploadQueueTable.USAGE.name}" })
    }

    @Test fun fullRowsAndALaterRowDrainWithoutQuarantine() {
        // Rows written by this app carry their client event ID from the first write.
        val ordinary = (JsonSerializer.deserializeQueueEntry(validEntry(1).data).single() as ExtractedUsageEvent)
            .copy(clientEventId = UUID.randomUUID())
        val label = "x".repeat(MAX_PERSISTED_QUEUE_PAYLOAD_BYTES -
            JsonSerializer.serializeQueueEntry(listOf(ordinary.copy(applicationLabel = ""))).size)
        for (index in 1..BATCH_SIZE) db.queueEntryData().insertEntry(QueueEntry(1000L + index, index.toLong(),
            JsonSerializer.serializeQueueEntry(listOf(ordinary.copy(applicationLabel = label, clientEventId = UUID.randomUUID())))))
        db.queueEntryData().insertEntry(QueueEntry(1000L + BATCH_SIZE + 1, BATCH_SIZE.toLong() + 1,
            JsonSerializer.serializeQueueEntry(listOf(ordinary))))
        assertEquals(0, executor().run())
        assertEquals(BATCH_SIZE + 1, usagePosts.sum())
        // Ten full rows are about 10 MiB: they leave as several bodies, none above the row-batch budget.
        assertTrue(bodyBytes.size > 1)
        assertTrue(bodyBytes.all { it <= CollectedUploadBody.ROW_BATCH_BUDGET_BYTES })
        assertEquals(0, db.queueEntryData().getSize())
        assertEquals(0, db.localDataQuarantineDao().forSource(UploadQueueTable.USAGE.tableName).size)
    }
}
