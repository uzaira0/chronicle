package com.openlattice.chronicle.services.upload

import android.content.Context
import com.openlattice.chronicle.collection.core.ModuleResult
import com.openlattice.chronicle.collection.sink.SensorSampleSink
import com.openlattice.chronicle.collection.CollectionModuleId
import com.openlattice.chronicle.collection.DistributionRestrictedRuntime
import com.openlattice.chronicle.collection.state.currentAppVersion
import com.openlattice.chronicle.collection.state.dedicatedModulePayloads
import com.openlattice.chronicle.collection.state.dedicatedQueueFor
import com.openlattice.chronicle.collection.state.dispositionQueueTable
import com.openlattice.chronicle.crypto.EncryptedPayloadType
import com.openlattice.chronicle.serialization.JsonSerializer
import com.openlattice.chronicle.storage.ActivityRecognitionSampleEntry
import com.openlattice.chronicle.storage.AppNetworkUsageSampleEntry
import com.openlattice.chronicle.storage.AudioActivitySampleEntry
import com.openlattice.chronicle.storage.AudioContentSampleEntry
import com.openlattice.chronicle.storage.BatterySampleEntry
import com.openlattice.chronicle.storage.ChronicleDb
import com.openlattice.chronicle.storage.ConnectivityStateSampleEntry
import com.openlattice.chronicle.storage.DeviceSettingsSampleEntry
import com.openlattice.chronicle.storage.HealthMetricSampleEntry
import com.openlattice.chronicle.storage.InteractionSampleEntry
import com.openlattice.chronicle.storage.NotificationActivitySampleEntry
import com.openlattice.chronicle.storage.QueueEntry
import com.openlattice.chronicle.storage.SensorSampleEntry
import com.openlattice.chronicle.storage.SleepSampleEntry
import com.openlattice.chronicle.storage.UploadQueueEvidence
import com.openlattice.chronicle.storage.UploadServerEntity
import com.openlattice.chronicle.collection.device.serializeMalformedRow
import java.net.HttpURLConnection
import java.time.OffsetDateTime
import java.time.ZoneOffset

private const val PAYLOAD_TOO_LARGE = 413
internal const val UNPROCESSABLE_PAYLOAD_STATUS = 422
private val REJECTED_PAYLOAD_STATUSES = setOf(HttpURLConnection.HTTP_BAD_REQUEST, PAYLOAD_TOO_LARGE, UNPROCESSABLE_PAYLOAD_STATUS)
private const val REJECTED_UPLOAD_REASON_PREFIX = "UPLOAD_REJECTED:"
private const val MAX_REJECTED_REPLAY_ROWS = 10
private fun rejectionReason(status: Int) = "$REJECTED_UPLOAD_REASON_PREFIX$status:${currentAppVersion()}"

/** A definitive rejection may split or quarantine; an ambiguous response must retain its batch. */
internal fun handleRejectedUploadBatch(context: Context, db: ChronicleDb, server: UploadServerEntity,
                                       type: EncryptedPayloadType, error: Exception, rowCount: Int,
                                       quarantine: (Int) -> Unit): Boolean {
    val status = uploadHttpStatus(error)
    if (status !in REJECTED_PAYLOAD_STATUSES) return false
    if (rowCount > 1) {
        UploadRetryGate.reduceBatchLimit(context, server, UploadRetryGate.familyFor(type), rowCount)
        db.runInTransaction { acknowledgePendingUploadBatch(db, server, type, context, resetBatchLimit = false) }
    } else db.runInTransaction {
        quarantine(checkNotNull(status))
        acknowledgePendingUploadBatch(db, server, type, context)
    }
    return true
}

internal fun <T> quarantineRejectedRows(db: ChronicleDb, server: UploadServerEntity, type: EncryptedPayloadType,
                                       status: Int,
                                       sourceTable: String, rows: List<T>, idOf: (T) -> String,
                                       remove: (List<String>) -> Unit,
                                       rawOf: (T) -> ByteArray = { serializeMalformedRow(it as Any) }) {
    db.runInTransaction {
        for (row in rows) {
            val id = idOf(row)
            val version = db.uploadQueueEvidenceDao().get(type.name, listOf(id)).singleOrNull()?.settingsVersion
            quarantineMalformedSample(db, server, sourceTable, id, rawOf(row), UploadRetryGate.familyFor(type),
                reason = rejectionReason(status), httpStatus = status, settingsVersion = version) {
                remove(listOf(id))
            }
        }
    }
}

/** Retry isolated HTTP rejections after an app update or daily, including after server upgrades. */
internal fun replayRejectedUploadRows(db: ChronicleDb, server: UploadServerEntity, type: EncryptedPayloadType) {
    val table = when (type) {
        EncryptedPayloadType.USAGE -> dispositionQueueTable(dedicatedQueueFor(CollectionModuleId.USAGE_EVENTS))
        EncryptedPayloadType.SENSOR -> "sensor_samples"
        else -> dedicatedModulePayloads.entries.firstOrNull { it.value == type }?.let {
            dispositionQueueTable(dedicatedQueueFor(it.key))
        }
    } ?: return
    val retryBefore = OffsetDateTime.now(ZoneOffset.UTC).minusDays(1).toString()
    db.runInTransaction {
        val quarantine = db.localDataQuarantineDao()
        val rows = quarantine.retryableRejections(quarantineSourcesForTable(table), server.studyId,
            server.participantId, server.sourceDeviceId, "${server.id}:${server.createdAt}:",
            REJECTED_UPLOAD_REASON_PREFIX, REJECTED_PAYLOAD_STATUSES.map(::rejectionReason), retryBefore, MAX_REJECTED_REPLAY_ROWS)
        for (row in rows) {
            val json = row.rawData.toString(Charsets.UTF_8)
            // App upgrades may make an old representation unreadable; preserve it in quarantine.
            val parsed = runCatching {
                when (type) {
                    EncryptedPayloadType.USAGE -> QueueEntry(db.queueEntryData().nextWriteTimestamp(System.currentTimeMillis()),
                        row.sourceId.substringAfterLast(':').toLong(), row.rawData)
                    EncryptedPayloadType.SENSOR -> JsonSerializer.fromJson<SensorSampleEntry>(json)
                    EncryptedPayloadType.BATTERY -> JsonSerializer.fromJson<BatterySampleEntry>(json)
                    EncryptedPayloadType.INTERACTION -> JsonSerializer.fromJson<InteractionSampleEntry>(json)
                    EncryptedPayloadType.AUDIO_ACTIVITY -> JsonSerializer.fromJson<AudioActivitySampleEntry>(json)
                    EncryptedPayloadType.AUDIO_CONTENT -> JsonSerializer.fromJson<AudioContentSampleEntry>(json)
                    EncryptedPayloadType.NOTIFICATION_ACTIVITY -> JsonSerializer.fromJson<NotificationActivitySampleEntry>(json)
                    EncryptedPayloadType.SLEEP -> JsonSerializer.fromJson<SleepSampleEntry>(json)
                    EncryptedPayloadType.ACTIVITY_RECOGNITION -> JsonSerializer.fromJson<ActivityRecognitionSampleEntry>(json)
                    EncryptedPayloadType.HEALTH_CONNECT -> JsonSerializer.fromJson<HealthMetricSampleEntry>(json)
                    EncryptedPayloadType.CONNECTIVITY_STATE -> JsonSerializer.fromJson<ConnectivityStateSampleEntry>(json)
                    EncryptedPayloadType.APP_NETWORK_USAGE -> JsonSerializer.fromJson<AppNetworkUsageSampleEntry>(json)
                    EncryptedPayloadType.DEVICE_SETTINGS -> JsonSerializer.fromJson<DeviceSettingsSampleEntry>(json)
                }
            }.getOrNull() ?: continue
            when (parsed) {
                is QueueEntry -> db.queueEntryData().insertAllocatedEntries(listOf(parsed))
                is SensorSampleEntry -> when (val result = SensorSampleSink(db.sensorSampleDao()).write(listOf(parsed))) {
                    is ModuleResult.Ok -> Unit
                    is ModuleResult.Failed -> throw result.error
                    else -> continue
                }
                is BatterySampleEntry -> db.batterySampleDao().insertAll(listOf(parsed))
                is ConnectivityStateSampleEntry -> db.connectivityStateSampleDao().insertAll(listOf(parsed))
                is AppNetworkUsageSampleEntry -> db.appNetworkUsageSampleDao().insertAll(listOf(parsed))
                is DeviceSettingsSampleEntry -> db.deviceSettingsSampleDao().insertAll(listOf(parsed))
                else -> if (!DistributionRestrictedRuntime.restoreRejectedSample(db, parsed)) continue
            }
            val restoredId = if (parsed is QueueEntry) "${parsed.writeTimestamp}:${parsed.id}"
                else row.sourceId.removePrefix("${server.id}:${server.createdAt}:")
            db.uploadQueueEvidenceDao().put(listOf(UploadQueueEvidence(type.name, restoredId, row.settingsVersion)))
            quarantine.delete(row)
        }
    }
}
