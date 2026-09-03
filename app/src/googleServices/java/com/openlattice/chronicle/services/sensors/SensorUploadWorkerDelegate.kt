package com.openlattice.chronicle.services.sensors

import android.content.Context
import android.util.Log
import com.openlattice.chronicle.collection.core.ModuleResult
import com.openlattice.chronicle.android.AndroidSensorSample
import com.openlattice.chronicle.collection.sensors.SensorUploadModule
import com.openlattice.chronicle.collection.sensors.SensorUploadResult
import com.openlattice.chronicle.collection.sink.UploadStatsSink
import com.openlattice.chronicle.crypto.EncryptedPayloadType
import com.openlattice.chronicle.serialization.JsonSerializer
import com.openlattice.chronicle.services.crypto.EncryptionRequiredButUnavailableException
import com.openlattice.chronicle.services.crypto.EncryptionSettingStore
import com.openlattice.chronicle.services.crypto.PayloadSealer
import com.openlattice.chronicle.services.upload.UploadWorker
import com.openlattice.chronicle.services.upload.RestrictedUploadApiFactory
import com.openlattice.chronicle.services.upload.LocalUploadModuleFamily
import com.openlattice.chronicle.services.upload.handleServerUploadFailure
import com.openlattice.chronicle.storage.ChronicleDb
import com.openlattice.chronicle.storage.SensorSampleDao
import com.openlattice.chronicle.storage.SensorSampleDeadLetterEntity
import com.openlattice.chronicle.storage.SensorSampleDeliveryEntity
import com.openlattice.chronicle.storage.SensorSampleEntry
import com.openlattice.chronicle.storage.UploadServerEntity
import com.openlattice.chronicle.storage.UploadStatsEntity
import java.time.LocalDate
import java.time.OffsetDateTime
import java.util.*

private val TAG = SensorUploadWorkerDelegate::class.java.simpleName
internal const val SENSOR_UPLOAD_BATCH_SIZE = 500
// Six samples/second is the configured worst-case aggregate duty-cycle envelope:
// 6 * 60 * 60 * 24 * 7 = 3,628,800 samples per seven-day TTL. Four million retains that
// full envelope (and ~15 days at the observed Pixel average near three/second) while keeping
// SQLite bounded. Actual encrypted-database size remains payload-dependent and must be monitored.
internal const val SENSOR_RETENTION_CAP_SAMPLES = 4_000_000
internal const val WORST_CASE_SENSOR_SAMPLES_PER_SECOND = 6
internal const val MAX_DEAD_LETTER_COUNT = 1_000
internal const val SENSOR_CLEANUP_DELETE_CHUNK_SIZE = 10_000
private const val SAMPLE_TTL_DAYS = 7L

data class SensorCleanupResult(
    val retentionExpiredDropCount: Int,
    val capacityForcedDropCount: Int,
    val deadLetterForcedDropCount: Int,
)

internal fun shouldSkipSensorAgeTtl(
    hasEnabledDestination: Boolean,
    hasPausedDestination: Boolean,
    anyFailClosedDestination: Boolean,
): Boolean = !hasEnabledDestination || hasPausedDestination || anyFailClosedDestination

internal fun isCompleteSensorUploadAcceptance(
    encrypted: Boolean,
    submittedSampleCount: Int,
    acceptedCount: Int,
): Boolean = acceptedCount == if (encrypted) 1 else submittedSampleCount

/** Deletes at most [maximumRows] in bounded SQL transactions without materializing row IDs. */
internal fun deleteInSqlChunks(
    maximumRows: Int,
    chunkSize: Int,
    deleteChunk: (Int) -> Int,
): Int {
    require(maximumRows >= 0) { "maximumRows must be non-negative" }
    require(chunkSize > 0) { "chunkSize must be positive" }
    var remaining = maximumRows
    var totalDeleted = 0
    while (remaining > 0) {
        val requested = minOf(remaining, chunkSize)
        val deleted = deleteChunk(requested)
        check(deleted in 0..requested) {
            "Chunk delete returned $deleted for a $requested-row request"
        }
        totalDeleted += deleted
        remaining -= deleted
        if (deleted < requested) break
    }
    return totalDeleted
}

/** Deletes every currently matching row in bounded SQL transactions. */
internal fun deleteAllAvailableInSqlChunks(
    chunkSize: Int,
    deleteChunk: (Int) -> Int,
): Int {
    require(chunkSize > 0) { "chunkSize must be positive" }
    var totalDeleted = 0
    do {
        val deleted = deleteChunk(chunkSize)
        check(deleted in 0..chunkSize) {
            "Chunk delete returned $deleted for a $chunkSize-row request"
        }
        totalDeleted += deleted
    } while (deleted == chunkSize)
    return totalDeleted
}

internal data class SensorBatchDrainResult(
    val failedDestinationCount: Int,
    val malformedSampleCount: Int,
    val heldForUnacknowledgedDestination: Boolean,
)

/**
 * Drains immutable oldest-first batches without using sample timestamps as acknowledgements.
 *
 * A failed batch records no new destination acknowledgements, so its successful deliveries are
 * deliberately replayed on retry. Once every attempted destination succeeds, [acknowledgeAndDelete]
 * persists that batch's receipts and conditionally deletes only its exact IDs. Configured
 * retention/capacity limits remain an explicit forced-drop boundary outside this drain. It may return zero
 * when a disabled destination still holds the batch; in that case the drain stops instead of
 * repeatedly submitting the same data to enabled destinations.
 */
internal fun <Destination, Payload : Any> drainSensorBatches(
    enabledDestinations: List<Destination>,
    loadOldest: (Int) -> List<SensorSampleEntry>,
    isAcknowledged: (Destination, List<String>) -> Boolean,
    mapEntry: (SensorSampleEntry) -> Payload,
    deliver: (Destination, List<SensorSampleEntry>, List<Payload>) -> Boolean,
    acknowledgeAndDelete: (List<Destination>, List<String>) -> Int,
    quarantineMalformed: (List<Pair<SensorSampleEntry, Exception>>) -> Unit,
    batchSize: Int = SENSOR_UPLOAD_BATCH_SIZE,
): SensorBatchDrainResult {
    if (enabledDestinations.isEmpty()) {
        return SensorBatchDrainResult(0, 0, heldForUnacknowledgedDestination = false)
    }

    var malformedCount = 0
    while (true) {
        val batch = loadOldest(batchSize).toList()
        if (batch.isEmpty()) {
            return SensorBatchDrainResult(0, malformedCount, heldForUnacknowledgedDestination = false)
        }

        val mappedEntries = mutableListOf<Pair<SensorSampleEntry, Payload>>()
        val malformedEntries = mutableListOf<Pair<SensorSampleEntry, Exception>>()
        batch.forEach { entry ->
            try {
                mappedEntries += entry to mapEntry(entry)
            } catch (e: Exception) {
                malformedEntries += entry to e
            }
        }
        if (malformedEntries.isNotEmpty()) {
            // Quarantine is a durable local state transition, not a network acknowledgement.
            // If it fails, propagate and leave the active rows untouched for a later retry.
            quarantineMalformed(malformedEntries)
            malformedCount += malformedEntries.size
        }
        if (mappedEntries.isEmpty()) continue

        val uploadBatch = mappedEntries.map { it.first }
        val payload = mappedEntries.map { it.second }
        val batchIds = uploadBatch.map { it.id }
        val pendingDestinations = enabledDestinations.filterNot { destination ->
            isAcknowledged(destination, batchIds)
        }

        // Every enabled destination already accepted this batch. Try the exact-ID trim once; a
        // zero result means a disabled destination is still holding it.
        if (pendingDestinations.isEmpty()) {
            val deleted = acknowledgeAndDelete(emptyList(), batchIds)
            if (deleted == uploadBatch.size) continue
            return SensorBatchDrainResult(0, malformedCount, heldForUnacknowledgedDestination = true)
        }

        val failures = pendingDestinations.count { destination ->
            !deliver(destination, uploadBatch, payload)
        }
        if (failures > 0) {
            return SensorBatchDrainResult(failures, malformedCount, heldForUnacknowledgedDestination = false)
        }

        val deleted = acknowledgeAndDelete(pendingDestinations, batchIds)
        if (deleted != uploadBatch.size) {
            return SensorBatchDrainResult(0, malformedCount, heldForUnacknowledgedDestination = true)
        }
    }
}

/**
 * Core sensor data upload logic, used by both `SensorUploadWorker.doWork()` and
 * `CombinedUploadWorker`.
 *
 * Phase 6C wraps this delegate behind [SensorUploadModule] (see [asModule]). Its upload contract is:
 *  - the `/android/sensors` upload route;
 *  - batch size [SENSOR_UPLOAD_BATCH_SIZE] (500);
 *  - TTL ([SAMPLE_TTL_DAYS]) + retention-aware cap ([SENSOR_RETENTION_CAP_SAMPLES]) cleanup;
 *  - immutable oldest-first batches and, on the normal delivery path, exact-ID deletion only
 *    after every configured destination has a durable receipt;
 *  - disabled destinations retain their undelivered batches until re-enabled or deleted;
 *  - malformed rows move to a bounded encrypted dead-letter table rather than receiving a
 *    false delivery receipt;
 *  - explicit TTL/cap forced-drop boundaries (TTL pauses with any paused destination);
 *  - upload-stats increments and the per-server failure handling.
 *
 * Phase 6C changes, both behaviour-preserving:
 *  - the inline `statsDao.insertDay(...)` + `incrementSensorCount(...)` two-step is routed
 *    through the sanctioned [UploadStatsSink] (design §1C.2) — same idempotent
 *    insert-day-then-increment, same counter semantics;
 *  - a corrupt row never aborts the valid remainder of the batch; it is quarantined and the
 *    number quarantined is surfaced via [SensorUploadResult.malformedSampleCount].
 */
class SensorUploadWorkerDelegate(
    private val context: Context,
    private val chronicleDb: ChronicleDb
) {
    /**
     * Count of corrupt sensor samples skipped during the most recent [execute] run.
     *
     * Per-instance state, not shared: every worker (`SensorUploadWorker`,
     * `CombinedUploadWorker`) constructs a fresh delegate per execution and runs it on a
     * single thread, so a plain `var` is safe — there is no concurrent `execute()` on one
     * delegate instance.
     */
    var lastMalformedSampleCount: Int = 0
        private set

    /**
     * @return number of servers that failed during this run. 0 = full success.
     *   Used by callers to decide retry policy without losing visibility into
     *   partial failures.
     */
    fun execute(): Int {
        lastMalformedSampleCount = 0
        val dao = chronicleDb.sensorSampleDao()
        val serverDao = chronicleDb.uploadServerDao()
        val statsSink = UploadStatsSink(chronicleDb.uploadStatsDao())

        val configuredServers = listOfNotNull(serverDao.getConfiguredServer())
        val servers = configuredServers.filter { it.enabled }
        val hasPausedDestination = configuredServers.any { !it.enabled }

        // Skip the age-based TTL purge while any enabled study is fail-closed (e2ee required, key
        // pending) so deliberately-retained PHI isn't silently dropped; the absolute count cap in
        // cleanupStaleData still bounds storage. Only computed when servers exist, so the no-server
        // path never touches EncryptedSharedPreferences.
        val anyFailClosed = servers.isNotEmpty() && run {
            val encryptionStore = EncryptionSettingStore.of(context)
            servers.any { server ->
                runCatching { UUID.fromString(server.studyId) }.getOrNull()?.let { studyId ->
                    PayloadSealer.routing(
                        encryptionStore.get(studyId), encryptionStore.isEncryptionRequired(studyId),
                    ) == PayloadSealer.EncryptionRouting.FAIL_CLOSED
                } ?: false
            }
        }

        if (anyFailClosed) {
            Log.w(
                TAG,
                "A study is fail-closed (e2ee required, key pending); skipping sensor age-TTL purge to retain PHI for retry",
            )
        }
        if (hasPausedDestination) {
            Log.i(TAG, "Skipping sensor age-TTL purge while a configured destination is paused")
        }
        try {
            cleanupStaleData(
                dao,
                skipAgeTtl = shouldSkipSensorAgeTtl(
                    hasEnabledDestination = servers.isNotEmpty(),
                    hasPausedDestination = hasPausedDestination,
                    anyFailClosedDestination = anyFailClosed,
                ),
            )
        } catch (e: Exception) {
            Log.e(TAG, "Sensor cleanup failed, continuing with upload", e)
        }

        if (servers.isEmpty()) {
            Log.i(TAG, "No enabled upload servers, skipping sensor upload")
            return 0
        }

        val currentFailureCounts = servers.associate { server ->
            server.id to server.sensorConsecutiveFailures
        }.toMutableMap()
        val diagnosticCursors = servers.associate { server ->
            server.id to server.lastUploadedSensorId
        }.toMutableMap()

        val result = drainSensorBatches(
            enabledDestinations = servers,
            loadOldest = dao::getOldest,
            isAcknowledged = { server, sampleIds ->
                dao.countDeliveriesForServer(
                    server.id, server.sensorDeliveryGeneration, sampleIds,
                ) == sampleIds.size
            },
            mapEntry = SensorSampleEntry::toAndroidSensorSample,
            deliver = { server, batch, samples ->
                uploadBatch(
                    server = server,
                    batch = batch,
                    samples = samples,
                    serverDao = serverDao,
                    statsSink = statsSink,
                    currentFailureCounts = currentFailureCounts,
                    diagnosticCursors = diagnosticCursors,
                )
            },
            acknowledgeAndDelete = { acknowledgedServers, sampleIds ->
                if (acknowledgedServers.isEmpty()) {
                    dao.deleteFullyDeliveredByIds(sampleIds)
                } else {
                    val deliveredAt = OffsetDateTime.now().toString()
                    val deliveries = acknowledgedServers.flatMap { server ->
                        sampleIds.map { sampleId ->
                            SensorSampleDeliveryEntity(
                                sampleId,
                                server.id,
                                server.sensorDeliveryGeneration,
                                deliveredAt,
                            )
                        }
                    }
                    dao.acknowledgeAndDeleteFullyDelivered(deliveries, sampleIds)
                }
            },
            quarantineMalformed = { malformed ->
                val quarantinedAt = OffsetDateTime.now().toString()
                malformed.forEach { (entry, error) ->
                    Log.w(TAG, "Quarantining corrupt sensor sample ${entry.id}", error)
                }
                val deadLetters = malformed.map { (entry, error) ->
                    SensorSampleDeadLetterEntity(
                        sampleId = entry.id,
                        sensorType = entry.sensorType,
                        timestamp = entry.timestamp,
                        timezone = entry.timezone,
                        x = entry.x,
                        y = entry.y,
                        z = entry.z,
                        w = entry.w,
                        accuracy = entry.accuracy,
                        valuesJson = entry.valuesJson,
                        quarantinedAt = quarantinedAt,
                        reason = error.javaClass.simpleName.ifBlank { "MappingFailure" },
                    )
                }
                dao.quarantineMalformed(deadLetters, deadLetters.map { it.sampleId })
            },
        )

        lastMalformedSampleCount = result.malformedSampleCount
        if (result.heldForUnacknowledgedDestination) {
            Log.i(TAG, "Sensor upload paused at a batch held for a disabled destination")
        }
        Log.i(
            TAG,
            "Sensor upload complete; skipped ${result.malformedSampleCount} malformed sample(s)",
        )
        return result.failedDestinationCount
    }

    private fun uploadBatch(
        server: UploadServerEntity,
        batch: List<SensorSampleEntry>,
        samples: List<AndroidSensorSample>,
        serverDao: com.openlattice.chronicle.storage.UploadServerDao,
        statsSink: UploadStatsSink,
        currentFailureCounts: MutableMap<Long, Int>,
        diagnosticCursors: MutableMap<Long, String?>,
    ): Boolean {
        return try {
            val studyId = UUID.fromString(server.studyId)
            val participantId = server.participantId
            val deviceId = server.sourceDeviceId
            val studyApi = UploadWorker.getChronicleStudyApi(server.url, server.mobileSigningSecretOverride)
            val restrictedStudyApi = RestrictedUploadApiFactory.get(
                server.url, server.mobileSigningSecretOverride,
            )
            // Study payload-encryption setting (HIPAA-2028 W2). When e2ee is on, each batch
            // is sealed and posted to the encrypted endpoint; otherwise the existing
            // plaintext /android/sensors upload is used, unchanged.
            val encryptionStore = EncryptionSettingStore.of(context)
            val encryptionSetting = encryptionStore.get(studyId)
            val encryptionRouting = PayloadSealer.routing(
                encryptionSetting, encryptionStore.isEncryptionRequired(studyId),
            )
            if (encryptionRouting == PayloadSealer.EncryptionRouting.FAIL_CLOSED) {
                throw EncryptionRequiredButUnavailableException(studyId)
            }

            val encrypted = encryptionRouting == PayloadSealer.EncryptionRouting.ENCRYPT
            val acceptedCount = if (encrypted) {
                // Seal the EXACT bytes the plaintext path would post.
                val plaintext = JsonSerializer.serializeToBytes(samples)
                val envelope = PayloadSealer.seal(
                    setting = encryptionSetting!!,
                    studyId = studyId,
                    participantId = participantId,
                    payloadType = EncryptedPayloadType.SENSOR,
                    plaintext = plaintext,
                    sampleCount = samples.size,
                )
                studyApi.uploadAndroidEncryptedData(
                    studyId, participantId, deviceId, server.apiKey, listOf(envelope),
                )
            } else {
                restrictedStudyApi.uploadAndroidSensorData(
                    studyId, participantId, deviceId, server.apiKey, samples,
                )
            }
            check(isCompleteSensorUploadAcceptance(encrypted, samples.size, acceptedCount)) {
                val expectedCount = if (encrypted) 1 else samples.size
                "Sensor upload acceptance mismatch: expected $expectedCount, received $acceptedCount"
            }
            Log.i(TAG, "[${server.name}] Uploaded ${samples.size} sensor samples")

            // Retain the legacy cursor only as monotonic diagnostics/settings-sync state. It is
            // never used to select or delete queue rows.
            val batchMaxTimestamp = batch.maxOf { it.timestamp }
            val previousCursor = diagnosticCursors[server.id]
            val diagnosticCursor = if (previousCursor == null || batchMaxTimestamp > previousCursor) {
                batchMaxTimestamp
            } else {
                previousCursor
            }
            diagnosticCursors[server.id] = diagnosticCursor
            currentFailureCounts[server.id] = 0
            serverDao.recordSensorUploadSuccess(
                server.id, OffsetDateTime.now().toString(), diagnosticCursor, samples.size,
            )

            val today = LocalDate.now().toString()
            val statsResult = statsSink.recordSensorUploaded(server.id, today, samples.size)
            if (statsResult is ModuleResult.Failed) {
                Log.e(TAG, "[${server.name}] Failed to record sensor upload stats", statsResult.error)
            }
            true
        } catch (e: Exception) {
            var updatedFailureCount = currentFailureCounts[server.id]
                ?: server.sensorConsecutiveFailures
            try {
                handleServerUploadFailure(
                    context,
                    TAG,
                    server,
                    e,
                    LocalUploadModuleFamily.DEVICE_TELEMETRY,
                    updatedFailureCount,
                ) { failures, errorMsg ->
                    updatedFailureCount = failures
                    currentFailureCounts[server.id] = failures
                    serverDao.recordSensorUploadFailure(
                        server.id, OffsetDateTime.now().toString(), errorMsg, failures,
                    )
                }
            } catch (statusError: Exception) {
                Log.e(TAG, "[${server.name}] Failed to persist sensor upload failure status", statusError)
            }
            currentFailureCounts[server.id] = updatedFailureCount
            val today = LocalDate.now().toString()
            try {
                chronicleDb.uploadStatsDao().insertDay(UploadStatsEntity(serverId = server.id, date = today))
                chronicleDb.uploadStatsDao().incrementSensorFailureCount(server.id, today, 1)
            } catch (statsError: Exception) {
                Log.e(TAG, "[${server.name}] Failed to record sensor upload failure stats", statsError)
            }
            false
        }
    }

    /**
     * Adapts this delegate to the Phase 6C [SensorUploadModule] interface. The wrapper
     * runs the identical [execute] logic and packages the per-server failure count plus
     * the malformed-sample count into a [SensorUploadResult].
     */
    fun asModule(): SensorUploadModule = object : SensorUploadModule {
        override fun upload(): SensorUploadResult {
            val failures = execute()
            return SensorUploadResult(
                serverFailureCount = failures,
                malformedSampleCount = lastMalformedSampleCount,
            )
        }
    }

    companion object {
        fun cleanupStaleData(
            dao: SensorSampleDao,
            skipAgeTtl: Boolean = false,
            maxSampleCount: Int = SENSOR_RETENTION_CAP_SAMPLES,
            maxDeadLetterCount: Int = MAX_DEAD_LETTER_COUNT,
            deleteChunkSize: Int = SENSOR_CLEANUP_DELETE_CHUNK_SIZE,
            reportDrop: (String) -> Unit = { message -> Log.w(TAG, message) },
        ): SensorCleanupResult {
            require(maxSampleCount >= 0) { "maxSampleCount must be non-negative" }
            require(maxDeadLetterCount >= 0) { "maxDeadLetterCount must be non-negative" }
            require(deleteChunkSize > 0) { "deleteChunkSize must be positive" }
            // skipAgeTtl: a destination is paused or fail-closed, so its pending PHI is retained
            // for retry. The absolute count cap still applies as an explicit hard DoS bound.
            val deletedByAge = if (!skipAgeTtl) {
                val cutoff = OffsetDateTime.now().minusDays(SAMPLE_TTL_DAYS).toString()
                deleteAllAvailableInSqlChunks(deleteChunkSize) { limit ->
                    dao.deleteOldestBefore(cutoff, limit)
                }.also { dropped ->
                    if (dropped > 0) {
                        reportDrop(
                            "RETENTION DROP: permanently removed $dropped sensor sample(s) " +
                                "older than the configured ${SAMPLE_TTL_DAYS}-day limit; " +
                                "delivery is not guaranteed beyond that limit",
                        )
                    }
                }
            } else 0

            val count = dao.count()
            val droppedByCapacity = if (count > maxSampleCount) {
                val dropped = deleteInSqlChunks(
                    maximumRows = count - maxSampleCount,
                    chunkSize = deleteChunkSize,
                    deleteChunk = dao::deleteOldest,
                )
                reportDrop(
                    "FORCED CAPACITY DROP: permanently removed $dropped oldest sensor " +
                        "sample(s) to enforce the configured $maxSampleCount-row DoS bound; " +
                        "this can include data held for a paused destination",
                )
                dropped
            } else 0

            val deadLetterCount = dao.countDeadLetters()
            val droppedDeadLetters = if (deadLetterCount > maxDeadLetterCount) {
                val dropped = deleteInSqlChunks(
                    maximumRows = deadLetterCount - maxDeadLetterCount,
                    chunkSize = deleteChunkSize,
                    deleteChunk = dao::deleteOldestDeadLetters,
                )
                reportDrop(
                    "FORCED DEAD-LETTER DROP: permanently removed $dropped quarantined sensor " +
                        "sample(s) to enforce the configured $maxDeadLetterCount-row DoS bound",
                )
                dropped
            } else 0

            return SensorCleanupResult(deletedByAge, droppedByCapacity, droppedDeadLetters)
        }
    }
}
