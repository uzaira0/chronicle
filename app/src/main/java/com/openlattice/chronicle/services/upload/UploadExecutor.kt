package com.openlattice.chronicle.services.upload

import android.content.Context
import android.os.Bundle
import android.util.Log
import org.json.JSONArray
import com.google.common.util.concurrent.RateLimiter
import com.openlattice.chronicle.constants.TelemetryEvents
import com.openlattice.chronicle.android.ChronicleUsageEvent
import com.openlattice.chronicle.android.ChronicleSample
import com.openlattice.chronicle.api.ChronicleStudyApi
import com.openlattice.chronicle.android.fromInteractionType
import com.openlattice.chronicle.models.ExtractedUsageEvent
import com.openlattice.chronicle.preferences.*
import com.openlattice.chronicle.sensors.APP_NAME
import com.openlattice.chronicle.sensors.GENERAL_NAME
import com.openlattice.chronicle.sensors.IMPORTANCE
import com.openlattice.chronicle.sensors.TIMESTAMP
import com.openlattice.chronicle.sensors.TIMEZONE
import com.openlattice.chronicle.sensors.USER
import com.openlattice.chronicle.serialization.JsonSerializer
import com.openlattice.chronicle.services.crypto.EncryptionSettingStore
import com.openlattice.chronicle.services.sinks.ChronicleUploadSink
import com.openlattice.chronicle.storage.ChronicleDb
import com.openlattice.chronicle.storage.UploadServerEntity
import com.openlattice.chronicle.storage.insertOwnedDay
import com.openlattice.chronicle.telemetry.LocalTelemetry
import com.openlattice.chronicle.utils.Utils.updateUploadInfo
import com.openlattice.chronicle.crypto.EncryptedPayloadType
import java.io.IOException
import java.time.Instant
import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.util.*
import java.util.concurrent.TimeUnit

private val UPLOAD_EXECUTOR_TAG = UploadExecutor::class.java.simpleName

class UploadExecutor(
    private val context: Context,
    private val chronicleDb: ChronicleDb,
    private val propertyTypeIds: Map<org.apache.olingo.commons.api.edm.FullQualifiedName, UUID>,
    private val studyApiFor: (UploadServerEntity) -> ChronicleStudyApi = {
        UploadWorker.getChronicleStudyApi(it.url, it.mobileSigningSecretOverride)
    },
) {
    private val limiter = RateLimiter.create(10.0)

    fun uploadForServer(server: UploadServerEntity) {
        if (!UploadRetryGate.shouldAttempt(context, server, LocalUploadModuleFamily.USAGE_LIFECYCLE, chronicleDb)) return
        val studyId = UUID.fromString(server.studyId)
        val participantId = server.participantId
        val deviceId = server.sourceDeviceId
        val apiKey = server.apiKey
        val studyApi = studyApiFor(server)
        val queue = chronicleDb.queueEntryData()
        val disposition = UploadDispositionPolicy(chronicleDb)
        var safeTimestamp = server.lastUploadedTimestamp
        var safeId = server.lastUploadedQueueId
        fun retainHeldRows(entries: List<com.openlattice.chronicle.storage.QueueEntry>, held: Map<com.openlattice.chronicle.storage.QueueEntry,
            ByteArray>) {
            entries.forEach { entry ->
                val retained = held[entry]
                if (retained == null) queue.deleteEntry(entry)
                else queue.updateData(entry.writeTimestamp, entry.id, retained)
            }
        }

        Log.i(UPLOAD_EXECUTOR_TAG, "Starting upload for server '${server.name}' (authMode=${server.authMode})")

        var cursorTimestamp = server.lastUploadedTimestamp
        var cursorId = server.lastUploadedQueueId
        fun nextBatch() = selectPendingUploadBatch(chronicleDb, server, EncryptedPayloadType.USAGE,
            loadNew = {
                queue.getEntriesAfter(cursorTimestamp, cursorId, UploadRetryGate.batchLimit(
                    context, server, LocalUploadModuleFamily.USAGE_LIFECYCLE, BATCH_SIZE,
                ))
            },
            loadByIds = queue::getByKeys, idOf = { "${it.writeTimestamp}:${it.id}" })
        var nextEntries = nextBatch()
        // A resumed pending batch may skip rows retained by a previous run's hold policy.
        var heldBeforeCursor = nextEntries.firstOrNull()?.let { first ->
            queue.getEntriesAfter(cursorTimestamp, cursorId, 1).any { earlier ->
                earlier.writeTimestamp < first.writeTimestamp ||
                    (earlier.writeTimestamp == first.writeTimestamp && earlier.id < first.id)
            }
        } ?: false
        var latestTimestampUploadedOverall: OffsetDateTime? = null
        // Read the study's cached payload-encryption setting (HIPAA-2028 W2). When e2ee is on
        // for the study the sink seals each batch and posts to the encrypted endpoint; otherwise
        // it uses the existing plaintext usage upload, unchanged.
        val encryptionStore = EncryptionSettingStore.of(context)
        val encryptionSetting = encryptionStore.get(studyId)
        // Fail closed: if the study is known to require e2ee but no usable key is cached, the sink
        // refuses to upload plaintext (the batch is retained + retried) rather than leaking PHI.
        val encryptionRequired = encryptionStore.isEncryptionRequired(studyId)
        val sink = ChronicleUploadSink(
            studyId, participantId, deviceId, apiKey, studyApi, encryptionSetting, encryptionRequired,
            batchVersion = { pendingUploadBatchVersion(chronicleDb, server, EncryptedPayloadType.USAGE) },
            batchIdentity = { pendingUploadBatchIdentity(chronicleDb, server, EncryptedPayloadType.USAGE) },
            onShortWrite = { count ->
                LocalUploadDiagnosticsStore.of(context).recordOperational(
                    LocalUploadModuleFamily.USAGE_LIFECYCLE,
                    LocalOperationalIssue.SAMPLE_QUARANTINED,
                    count,
                )
            },
        )

        var batches = 0
        while (nextEntries.isNotEmpty() && batches++ < MAX_USAGE_BATCHES_PER_RUN) {
            limiter.acquire()
            val w = com.google.common.base.Stopwatch.createStarted()
            val heldRows = linkedMapOf<com.openlattice.chronicle.storage.QueueEntry, ByteArray>()
            val data = nextEntries.flatMap { queueEntry ->
                val sourceId = "${queueEntry.writeTimestamp}:${queueEntry.id}"
                val originalData = try {
                    JsonSerializer.deserializeQueueEntry(queueEntry.data)
                } catch (_: IOException) {
                    null
                }
                if (disposition.sharedHeld.isNotEmpty() && originalData == null) {
                    // Untagged legacy/corrupt data cannot prove it excludes a held module.
                    heldRows[queueEntry] = queueEntry.data
                    return@flatMap emptyList<ChronicleSample>()
                }
                val heldData = originalData.orEmpty().filter { sample ->
                    disposition.sharedHeld.isNotEmpty() && (sample !is ExtractedUsageEvent || disposition.holds(sample))
                }
                if (heldData.isNotEmpty()) heldRows[queueEntry] = JsonSerializer.serializeQueueEntry(heldData)
                val queueData = originalData?.filter { it !in heldData }
                if (queueData?.isEmpty() == true && queueEntry in heldRows) return@flatMap emptyList<ChronicleSample>()
                if (queueData == null) {
                    Log.w(UPLOAD_EXECUTOR_TAG, "Error deserializing. Attempting legacy deserializer")
                    val rawItems = runCatching {
                        val array = JSONArray(queueEntry.data.toString(Charsets.UTF_8))
                        (0 until array.length()).map { array.get(it).toString() }
                    }.getOrNull()
                    if (rawItems == null) {
                        quarantineMalformedSample(
                            chronicleDb, server, "dataQueue", sourceId, queueEntry.data,
                            LocalUploadModuleFamily.USAGE_LIFECYCLE,
                        )
                        emptyList()
                    } else if (rawItems.isEmpty()) {
                        quarantineMalformedSample(
                            chronicleDb, server, "dataQueue", sourceId, queueEntry.data,
                            LocalUploadModuleFamily.USAGE_LIFECYCLE,
                        )
                        emptyList()
                    } else {
                        mapLegacyQueueItems(rawItems, mapItem = { rawItem ->
                                val bytes = "[$rawItem]".toByteArray(Charsets.UTF_8)
                                runCatching {
                                    mapUsageSamplesForUpload(JsonSerializer.deserializeQueueEntry(bytes),
                                        studyId, participantId, queueEntry.writeTimestamp)
                                }.getOrNull()?.takeIf { it.isNotEmpty() }
                                    ?: mapUsageSamplesForUpload(
                                        mapLegacyQueueEntry(JsonSerializer.deserializeLegacyQueueEntry(bytes)),
                                        studyId, participantId, queueEntry.writeTimestamp,
                                    )
                            }, onMalformed = { index, rawItem ->
                            quarantineMalformedSample(
                                chronicleDb, server, "dataQueue", "$sourceId:$index",
                                rawItem.toByteArray(Charsets.UTF_8),
                                LocalUploadModuleFamily.USAGE_LIFECYCLE,
                            )
                        })
                    }
                } else {
                    val mapped = runCatching {
                        mapUsageSamplesForUpload(queueData, studyId, participantId, queueEntry.writeTimestamp)
                    }
                    mapped.fold(onSuccess = { samples ->
                        val omitted = queueData.size - samples.size
                        if (queueData.isEmpty()) quarantineMalformedSample(
                            chronicleDb, server, "dataQueue", sourceId, queueEntry.data,
                            LocalUploadModuleFamily.USAGE_LIFECYCLE,
                        )
                        else if (omitted > 0) quarantineMalformedSample(
                            chronicleDb, server, "dataQueue", sourceId, queueEntry.data,
                            LocalUploadModuleFamily.USAGE_LIFECYCLE, count = omitted,
                        )
                        samples
                    }, onFailure = {
                        quarantineMalformedSample(
                            chronicleDb, server, "dataQueue", sourceId, queueEntry.data,
                            LocalUploadModuleFamily.USAGE_LIFECYCLE,
                        )
                        emptyList()
                    })
                }
            }

            Log.i(UPLOAD_EXECUTOR_TAG, "[${server.name}] Processing ${data.size} items took ${w.elapsed(TimeUnit.MILLISECONDS)}ms")
            w.reset()
            w.start()

            val maxQueueCursor = nextEntries.maxWith(
                compareBy<com.openlattice.chronicle.storage.QueueEntry> { it.writeTimestamp }
                    .thenBy { it.id }
            )
            heldBeforeCursor = heldBeforeCursor || heldRows.isNotEmpty()
            if (data.isEmpty()) {
                // Every omitted item is durably quarantined above; a failed quarantine throws.
                chronicleDb.runInTransaction {
                    if (!heldBeforeCursor) {
                        check(chronicleDb.uploadServerDao().advanceUsageCursor(
                            server.id, maxQueueCursor.writeTimestamp, maxQueueCursor.id) == 1)
                        safeTimestamp = maxQueueCursor.writeTimestamp
                        safeId = maxQueueCursor.id
                    }
                    if (disposition.sharedHeld.isNotEmpty()) retainHeldRows(nextEntries, heldRows)
                    acknowledgePendingUploadBatch(chronicleDb, server, EncryptedPayloadType.USAGE, context)
                }
                cursorTimestamp = maxQueueCursor.writeTimestamp
                cursorId = maxQueueCursor.id
                nextEntries = nextBatch()
                continue
            }
            val result = try { sink.submit(data) } catch (error: Exception) {
                if (!handleRejectedUploadBatch(context, chronicleDb, server, EncryptedPayloadType.USAGE, error, nextEntries.size) { status ->
                    quarantineRejectedRows(chronicleDb, server, EncryptedPayloadType.USAGE, status,
                        "dataQueue", nextEntries, { "${it.writeTimestamp}:${it.id}" },
                        remove = { ids -> queue.deleteEntries(nextEntries.filter { "${it.writeTimestamp}:${it.id}" in ids }) },
                        rawOf = { it.data })
                }) throw error
                nextEntries = nextBatch()
                continue
            }

            if (result[ChronicleUploadSink::class.java.name] == true) {
                val latestTimestampBatch: OffsetDateTime? =
                    data.filterIsInstance<ChronicleUsageEvent>()
                        .map { it.timestamp }
                        .maxOrNull()

                latestTimestampUploadedOverall = listOfNotNull(
                    latestTimestampUploadedOverall,
                    latestTimestampBatch
                ).maxOrNull()

                val today = LocalDate.now().toString()
                try {
                    chronicleDb.runInTransaction {
                        check(chronicleDb.uploadServerDao().recordUsageUploadSuccess(
                            server.id, OffsetDateTime.now().toString(),
                            if (heldBeforeCursor) safeTimestamp else maxQueueCursor.writeTimestamp,
                            if (heldBeforeCursor) safeId else maxQueueCursor.id, data.size,
                        ) == 1)
                        acknowledgePendingUploadBatch(chronicleDb, server, EncryptedPayloadType.USAGE, context)
                        val statsDao = chronicleDb.uploadStatsDao()
                        statsDao.insertOwnedDay(server, today)
                        statsDao.incrementUsageCount(server.id, today, data.size)
                        if (disposition.sharedHeld.isNotEmpty()) retainHeldRows(nextEntries, heldRows)
                        if (!heldBeforeCursor) {
                            safeTimestamp = maxQueueCursor.writeTimestamp
                            safeId = maxQueueCursor.id
                        }
                    }
                } catch (error: Exception) {
                    Log.e(UPLOAD_EXECUTOR_TAG, "Server accepted usage batch but local cursor/stat commit failed", error)
                    throw error
                }
                cursorTimestamp = maxQueueCursor.writeTimestamp
                cursorId = maxQueueCursor.id

                Log.i(UPLOAD_EXECUTOR_TAG, "[${server.name}] Uploaded ${data.size} items in ${w.elapsed(TimeUnit.MILLISECONDS)}ms")
                nextEntries = nextBatch()

                LocalTelemetry.logEvent(TelemetryEvents.UPLOAD_SUCCESS, Bundle().apply {
                    putInt("size", data.size)
                })
            } else {
                throw Exception("Upload to server '${server.name}' returned failure")
            }
        }

        if (latestTimestampUploadedOverall != null) {
            updateUploadInfo(context, latestTimestampUploadedOverall)
        }
    }

    private fun mapLegacyQueueEntry(data: List<com.google.common.collect.SetMultimap<UUID, Any>>): List<ChronicleSample> {
        return data.map { datum ->
            val appPackageName = getFirstValueOrNull(datum, GENERAL_NAME)
            val interactionType = getFirstValueOrNull(datum, IMPORTANCE)
            val timestamp = getFirstValueOrNull(datum, TIMESTAMP)
            val timezone = getFirstValueOrNull(datum, TIMEZONE)
            val user = getFirstValueOrNull(datum, USER)
            val applicationLabel = getFirstValueOrNull(datum, APP_NAME)

            if (appPackageName != null && interactionType != null && timestamp != null && timezone != null && user != null &&
                applicationLabel != null) {
                // Legacy SetMultimap queue entries carry no raw event-type int; leave eventType at
                // its -1 default so mapToModel falls back to deriving it from interactionType.
                ExtractedUsageEvent(
                    appPackageName = appPackageName,
                    interactionType = interactionType,
                    timestamp = OffsetDateTime.parse(timestamp),
                    timezone = timezone,
                    user = user,
                    applicationLabel = applicationLabel,
                )
            } else throw IllegalArgumentException("Legacy usage queue item is missing required fields")
        }
    }

    private fun getFirstValueOrNull(
        entity: com.google.common.collect.SetMultimap<UUID, Any>,
        fqn: org.apache.olingo.commons.api.edm.FullQualifiedName
    ): String? {
        val ptId = propertyTypeIds.getValue(fqn)
        entity[ptId].iterator().let {
            if (it.hasNext()) return it.next().toString()
        }
        return null
    }
}

internal const val MAX_USAGE_BATCHES_PER_RUN = 5

/** A malformed array element never hides valid siblings, and quarantine failure aborts the row. */
internal fun <T> mapLegacyQueueItems(
    rawItems: List<String>,
    mapItem: (String) -> List<T>,
    onMalformed: (Int, String) -> Unit,
): List<T> {
    val valid = mutableListOf<T>()
    rawItems.forEachIndexed { index, rawItem ->
        val mapped = runCatching { mapItem(rawItem) }.getOrNull()
        if (mapped.isNullOrEmpty()) onMalformed(index, rawItem)
        else valid += mapped
    }
    return valid
}

/**
 * Adds upload identity and the durable Android queue-write time without replacing the framework
 * event timestamp. Keeping these as separate fields lets researchers distinguish occurrence,
 * collection, and server receipt latency.
 */
internal fun mapUsageSamplesForUpload(
    data: List<ChronicleSample>,
    studyId: UUID,
    participantId: String,
    queueWriteTimestamp: Long,
): List<ChronicleSample> {
    val collectedAt = OffsetDateTime.ofInstant(Instant.ofEpochMilli(queueWriteTimestamp), ZoneOffset.UTC)
    return data.mapNotNull { datum ->
        when (datum) {
            is ExtractedUsageEvent -> ChronicleUsageEvent(
                studyId = studyId,
                participantId = participantId,
                appPackageName = datum.appPackageName,
                interactionType = datum.interactionType,
                // Prefer the authoritative raw Android event-type int carried on the event;
                // fall back to deriving from interactionType for legacy queued rows.
                eventType = if (datum.eventType >= 0) {
                    datum.eventType
                } else {
                    fromInteractionType(datum.interactionType)
                },
                timestamp = datum.timestamp,
                timezone = datum.timezone,
                user = datum.user,
                applicationLabel = datum.applicationLabel,
                activityClass = datum.activityClass,
                collectedAt = collectedAt,
            )
            else -> null
        }
    }
}
