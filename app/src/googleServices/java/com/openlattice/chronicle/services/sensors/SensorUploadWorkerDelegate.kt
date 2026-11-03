package com.openlattice.chronicle.services.sensors

import android.content.Context
import com.openlattice.chronicle.android.AndroidSensorSample
import com.openlattice.chronicle.api.RestrictedChronicleStudyApi
import com.openlattice.chronicle.collection.sink.SensorSampleWriter
import com.openlattice.chronicle.services.upload.CollectedUploadBody
import com.openlattice.chronicle.services.upload.LocalUploadModuleFamily
import com.openlattice.chronicle.services.upload.QueueStream
import com.openlattice.chronicle.services.upload.QueueUpload
import com.openlattice.chronicle.services.upload.RestrictedUploadApiFactory
import com.openlattice.chronicle.services.upload.DistributionUploadPolicy
import com.openlattice.chronicle.services.upload.deleteFinishedRows
import com.openlattice.chronicle.services.upload.handleServerUploadFailure
import com.openlattice.chronicle.services.upload.recordDelivered
import com.openlattice.chronicle.storage.ChronicleDb
import com.openlattice.chronicle.storage.SensorSampleEntry
import com.openlattice.chronicle.storage.UploadQueueTable
import com.openlattice.chronicle.storage.UploadServerEntity
import com.openlattice.chronicle.storage.insertOwnedDay
import java.lang.reflect.Type
import java.time.LocalDate
import java.time.OffsetDateTime
import java.util.UUID

internal const val SENSOR_UPLOAD_BATCH_SIZE = 500

/**
 * Uploads `sensor_samples` oldest first in batches of [SENSOR_UPLOAD_BATCH_SIZE]. The server answers each
 * sample accepted, discarded or held: accepted and discarded rows are deleted, held rows wait for the next
 * settings revision. Malformed and refused rows move to the local quarantine; nothing is dropped.
 */
class SensorUploadWorkerDelegate internal constructor(
    private val context: Context,
    private val db: ChronicleDb,
    private val stopped: () -> Boolean = { false },
    private val restrictedApiFor: (UploadServerEntity) -> RestrictedChronicleStudyApi = {
        RestrictedUploadApiFactory.get(it.url)
    },
) {
    /** @return 1 when the run failed and its rows stay queued, otherwise 0. */
    fun execute(): Int {
        val server = db.uploadServerDao().getConfiguredServer()
        if (server == null || !server.enabled) return 0
        SensorSampleWriter.flushRuntimes()
        val dao = db.sensorSampleDao()
        val heldTypes = DistributionUploadPolicy.heldSensorTypes
        val api = restrictedApiFor(server)
        val studyId = UUID.fromString(server.studyId)
        val removeFinished = deleteFinishedRows(SensorSampleEntry::id, dao::deleteByIds)
        val stream = object : QueueStream<SensorSampleEntry, AndroidSensorSample> {
            override val table = UploadQueueTable.SENSOR
            override val elementType: Type = AndroidSensorSample::class.java
            override val batchRows = SENSOR_UPLOAD_BATCH_SIZE
            override fun next(limit: Int) = dao.getOldestExcludingTypes(limit, heldTypes)
            override fun rowId(item: SensorSampleEntry) = item.id
            override fun toWire(item: SensorSampleEntry) = item.toAndroidSensorSample()
            override fun post(headers: Map<String, String>, body: CollectedUploadBody<AndroidSensorSample>) =
                api.uploadAndroidSensorDataReceipt(studyId, server.participantId, headers, body)
            override fun remove(done: List<SensorSampleEntry>) = removeFinished(done)
            override fun wireId(row: AndroidSensorSample) = row.id
            override fun delivered(items: List<SensorSampleEntry>) =
                recordDelivered(db, server, UploadQueueTable.SENSOR, items.size, items.maxOf { it.timestamp })
        }
        return QueueUpload(context, db, server, stream).execute(stopped, onFailure = { error -> recordFailure(server, error) })
    }

    private fun recordFailure(server: UploadServerEntity, error: Exception) {
        handleServerUploadFailure(context, javaClass.simpleName, error, LocalUploadModuleFamily.SENSOR,
            server.sensorConsecutiveFailures) { failures, message ->
            db.uploadServerDao().recordSensorUploadFailure(server.id, OffsetDateTime.now().toString(), message, failures)
        }
        val today = LocalDate.now().toString()
        db.uploadStatsDao().insertOwnedDay(server, today)
        db.uploadStatsDao().incrementSensorFailureCount(server.id, today, 1)
    }
}
