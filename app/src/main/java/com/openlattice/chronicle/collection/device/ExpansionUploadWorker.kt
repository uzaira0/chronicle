package com.openlattice.chronicle.collection.device

import android.content.Context
import android.util.Log
import androidx.work.BackoffPolicy
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.Worker
import androidx.work.WorkerParameters
import com.openlattice.chronicle.collection.DistributionCollectionContributions
import com.openlattice.chronicle.collection.state.ResearchPersistenceGate
import com.openlattice.chronicle.crypto.EncryptedPayloadType
import com.openlattice.chronicle.serialization.JsonSerializer
import com.openlattice.chronicle.services.crypto.EncryptionRequiredButUnavailableException
import com.openlattice.chronicle.services.crypto.EncryptionSettingStore
import com.openlattice.chronicle.services.crypto.PayloadSealer
import com.openlattice.chronicle.services.upload.UPLOAD_NETWORK_CONSTRAINT
import com.openlattice.chronicle.services.upload.UploadQueueSingleFlight
import com.openlattice.chronicle.services.upload.UploadWorker
import com.openlattice.chronicle.services.upload.LocalUploadDiagnosticsStore
import com.openlattice.chronicle.services.upload.LocalUploadModuleFamily
import com.openlattice.chronicle.services.upload.exactActiveEnrollmentServerResolution
import com.openlattice.chronicle.storage.ChronicleDb
import com.openlattice.chronicle.storage.UploadServerEntity
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.util.UUID
import java.util.concurrent.TimeUnit

private val TAG = ExpansionUploadWorker::class.java.simpleName

internal const val EXPANSION_UPLOAD_WORK_NAME = "expansion_modules_upload"
internal const val INPUT_COLLECT_EXPANSION_BEFORE_UPLOAD = "collect_expansion_before_upload"
private const val EXPANSION_UPLOAD_INTERVAL_MIN = 15L
private const val EXPANSION_UPLOAD_MAX_BATCH = 5000
private const val EXPANSION_SAMPLE_TTL_DAYS = 14L
private const val EXPANSION_UPLOAD_MAX_ATTEMPTS = 5

/**
 * Periodic [Worker] that uploads connectivity and device-settings buffers plus streams contributed
 * by the non-Play research distribution.
 *
 * Mirrors [com.openlattice.chronicle.collection.audio.AudioUploadWorkerDelegate]: each stream is
 * uploaded to the active study server and its batch deleted only once it succeeds (endpoints are
 * idempotent via `ON CONFLICT DO NOTHING`). Encryption routing matches the other Android upload
 * paths — plaintext, sealed envelope, or fail-closed (retain + retry, never plaintext PHI).
 */
class ExpansionUploadWorker(context: Context, workerParameters: WorkerParameters) :
    Worker(context, workerParameters) {

    override fun doWork(): Result {
        return try {
            val result = ResearchPersistenceGate.runIfActive(applicationContext) {
                if (!UploadQueueSingleFlight.tryAcquire(EXPANSION_UPLOAD_WORK_NAME)) {
                    Log.i(TAG, "Expansion upload deferred because the queue is already being drained")
                    Result.retry()
                } else try {
                    if (inputData.getBoolean(INPUT_COLLECT_EXPANSION_BEFORE_UPLOAD, false)) {
                        collectExpansionSamples(applicationContext)
                    }
                    val failures = ExpansionUploadWorkerDelegate(
                        applicationContext, ChronicleDb.getInstance(applicationContext),
                    ).execute()
                    when {
                        failures == 0 -> Result.success()
                        runAttemptCount > EXPANSION_UPLOAD_MAX_ATTEMPTS -> Result.failure()
                        else -> Result.retry()
                    }
                } finally {
                    UploadQueueSingleFlight.release(EXPANSION_UPLOAD_WORK_NAME)
                }
            }
            if (result == null) {
                Log.i(TAG, "Expansion upload skipped without an active study enrollment")
            }
            result ?: Result.success()
        } catch (e: Exception) {
            Log.e(TAG, "Expansion upload worker failed", e)
            Result.failure()
        }
    }
}

class ExpansionUploadWorkerDelegate(
    internal val context: Context,
    internal val db: ChronicleDb,
) {

    /** @return number of (stream, server) upload failures this run; `0` = everything succeeded. */
    fun execute(): Int {
        val destination = exactActiveEnrollmentServerResolution(context, db)
        val server = destination.server
        if (server == null) {
            LocalUploadDiagnosticsStore.of(context).record(
                LocalUploadModuleFamily.DEVICE_TELEMETRY,
                requireNotNull(destination.issue),
            )
            Log.w(TAG, "Active enrollment has no eligible server; retaining expansion samples")
            return 1
        }
        val servers = listOf(server)
        val anyFailClosed = run {
            val store = EncryptionSettingStore.of(context)
            servers.any { server ->
                runCatching { UUID.fromString(server.studyId) }.getOrNull()?.let { studyId ->
                    PayloadSealer.routing(
                        store.get(studyId), store.isEncryptionRequired(studyId),
                    ) == PayloadSealer.EncryptionRouting.FAIL_CLOSED
                } ?: false
            }
        }

        purgeAll(anyFailClosed)

        var failures = 0
        failures += DistributionCollectionContributions.uploadAdditionalStreams(this, servers)
        failures += uploadStream(
            servers,
            getOldest = { db.connectivityStateSampleDao().getOldest(it) },
            idOf = { it.id },
            toDto = { it.toAndroidConnectivityStateEvent() },
            payloadType = EncryptedPayloadType.CONNECTIVITY_STATE,
            deleteByIds = { db.connectivityStateSampleDao().deleteByIds(it) },
            plainUpload = { studyId, server, events ->
                UploadWorker.getChronicleStudyApi(server.url, server.mobileSigningSecretOverride)
                    .uploadAndroidConnectivityStateData(
                        studyId, server.participantId, server.sourceDeviceId, server.apiKey, events,
                    )
            },
            label = "connectivity_state",
        )
        failures += uploadStream(
            servers,
            getOldest = { db.deviceSettingsSampleDao().getOldest(it) },
            idOf = { it.id },
            toDto = { it.toAndroidDeviceSettingsEvent() },
            payloadType = EncryptedPayloadType.DEVICE_SETTINGS,
            deleteByIds = { db.deviceSettingsSampleDao().deleteByIds(it) },
            plainUpload = { studyId, server, events ->
                UploadWorker.getChronicleStudyApi(server.url, server.mobileSigningSecretOverride)
                    .uploadAndroidDeviceSettingsData(
                        studyId, server.participantId, server.sourceDeviceId, server.apiKey, events,
                    )
            },
            label = "device_settings",
        )
        return failures
    }

    private fun purgeAll(anyFailClosed: Boolean) {
        if (anyFailClosed) {
            Log.w(TAG, "Skipping expansion TTL purge: a study is fail-closed (e2ee required, key pending)")
            return
        }
        val cutoff = OffsetDateTime.now(ZoneOffset.UTC).minusDays(EXPANSION_SAMPLE_TTL_DAYS).toString()
        runCatching {
            val purged = DistributionCollectionContributions.purgeAdditionalSamples(db, cutoff) +
                db.connectivityStateSampleDao().deleteOlderThan(cutoff) +
                db.deviceSettingsSampleDao().deleteOlderThan(cutoff)
            if (purged > 0) Log.w(TAG, "Purged $purged expansion sample(s) past the ${EXPANSION_SAMPLE_TTL_DAYS}-day TTL")
        }.onFailure { Log.e(TAG, "Expansion TTL cleanup failed; continuing", it) }
    }

    internal fun <T, D> uploadStream(
        servers: List<UploadServerEntity>,
        getOldest: (Int) -> List<T>,
        idOf: (T) -> String,
        toDto: (T) -> D,
        payloadType: EncryptedPayloadType,
        deleteByIds: (List<String>) -> Unit,
        plainUpload: (UUID, UploadServerEntity, List<D>) -> Unit,
        label: String,
    ): Int {
        val pending = getOldest(EXPANSION_UPLOAD_MAX_BATCH)
        if (pending.isEmpty()) return 0

        var malformed = 0
        val events = pending.mapNotNull { entry ->
            try {
                toDto(entry)
            } catch (e: Exception) {
                malformed++
                Log.w(TAG, "Skipping corrupt $label sample ${idOf(entry)}", e)
                null
            }
        }

        var failureCount = 0
        for (server in servers) {
            try {
                if (events.isNotEmpty()) {
                    val studyId = UUID.fromString(server.studyId)
                    val studyApi = UploadWorker.getChronicleStudyApi(server.url, server.mobileSigningSecretOverride)
                    val store = EncryptionSettingStore.of(context)
                    val setting = store.get(studyId)
                    val routing = PayloadSealer.routing(setting, store.isEncryptionRequired(studyId))
                    when (routing) {
                        PayloadSealer.EncryptionRouting.FAIL_CLOSED ->
                            throw EncryptionRequiredButUnavailableException(studyId)
                        PayloadSealer.EncryptionRouting.ENCRYPT -> {
                            val plaintext = JsonSerializer.serializeToBytes(events)
                            val envelope = PayloadSealer.seal(
                                setting = setting!!,
                                studyId = studyId,
                                participantId = server.participantId,
                                payloadType = payloadType,
                                plaintext = plaintext,
                                sampleCount = events.size,
                            )
                            studyApi.uploadAndroidEncryptedData(
                                studyId, server.participantId, server.sourceDeviceId, server.apiKey, listOf(envelope),
                            )
                        }
                        else -> plainUpload(studyId, server, events)
                    }
                    Log.i(TAG, "[${server.name}] Uploaded ${events.size} $label sample(s)")
                }
            } catch (e: Exception) {
                failureCount++
                LocalUploadDiagnosticsStore.of(context).recordFailure(
                    LocalUploadModuleFamily.DEVICE_TELEMETRY,
                    e,
                )
                Log.e(TAG, "[${server.name}] $label upload failed", e)
            }
        }

        if (failureCount == 0) deleteByIds(pending.map { idOf(it) })
        Log.i(TAG, "$label upload complete: serverFailures=$failureCount, malformedSkipped=$malformed")
        return failureCount
    }
}

/** Schedules the periodic [ExpansionUploadWorker]. Idempotent; constrained to run only with network. */
fun scheduleExpansionUploadWork(context: Context) {
    val workRequest = PeriodicWorkRequestBuilder<ExpansionUploadWorker>(
        EXPANSION_UPLOAD_INTERVAL_MIN,
        TimeUnit.MINUTES,
    )
        .setConstraints(UPLOAD_NETWORK_CONSTRAINT)
        .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
        .build()

    WorkManager.getInstance(context).enqueueUniquePeriodicWork(
        EXPANSION_UPLOAD_WORK_NAME,
        ExistingPeriodicWorkPolicy.UPDATE,
        workRequest,
    )
}
