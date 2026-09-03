package com.openlattice.chronicle.collection.interaction

import android.content.Context
import android.util.Log
import androidx.work.BackoffPolicy
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.Worker
import androidx.work.WorkerParameters
import com.openlattice.chronicle.collection.state.ResearchPersistenceGate
import com.openlattice.chronicle.crypto.EncryptedPayloadType
import com.openlattice.chronicle.serialization.JsonSerializer
import com.openlattice.chronicle.services.crypto.EncryptionRequiredButUnavailableException
import com.openlattice.chronicle.services.crypto.EncryptionSettingStore
import com.openlattice.chronicle.services.crypto.PayloadSealer
import com.openlattice.chronicle.services.upload.UPLOAD_NETWORK_CONSTRAINT
import com.openlattice.chronicle.services.upload.UploadQueueSingleFlight
import com.openlattice.chronicle.services.upload.UploadWorker
import com.openlattice.chronicle.services.upload.RestrictedUploadApiFactory
import com.openlattice.chronicle.storage.ChronicleDb
import com.openlattice.chronicle.storage.interactionSampleDao
import java.time.OffsetDateTime
import java.util.UUID
import java.util.concurrent.TimeUnit

private val TAG = InteractionUploadWorker::class.java.simpleName

/** Unique WorkManager name for the periodic interaction-events upload work. */
internal const val INTERACTION_UPLOAD_WORK_NAME = "interaction_events_upload"

/** Upload cadence — 15 min (the WorkManager periodic minimum). */
private const val INTERACTION_UPLOAD_INTERVAL_MIN = 15L

/** Single-pass row cap; one request ships the backlog (interaction volume is modest). */
private const val INTERACTION_UPLOAD_MAX_BATCH = 5000

/** Rows older than this are dropped even if never uploaded — bounds table growth. */
private const val INTERACTION_SAMPLE_TTL_DAYS = 14L

/** Run-attempt count above which a failing upload stops retrying. */
private const val INTERACTION_UPLOAD_MAX_ATTEMPTS = 5

/**
 * Periodic [Worker] that uploads collected `interaction_samples` rows to the server
 * (see `docs/SENSING-EXPANSION-DESIGN.md` §6). The rows are produced by
 * [InteractionCollectionService] (an AccessibilityService); this worker ships them to the
 * `/chronicle/v4/study/.../android/interaction` endpoint.
 *
 * Mirrors [com.openlattice.chronicle.collection.battery.BatteryUploadWorkerDelegate]: rows are
 * uploaded to **every** enabled server and deleted only once **all** succeed (the endpoint is
 * idempotent via `ON CONFLICT DO NOTHING`). Encryption routing matches the other Android
 * upload paths — plaintext when a study has no e2ee, a sealed envelope when it does, and
 * fail-closed (retain + retry, never plaintext PHI) when e2ee is required but no key is cached.
 */
class InteractionUploadWorker(context: Context, workerParameters: WorkerParameters) :
    Worker(context, workerParameters) {

    override fun doWork(): Result {
        return try {
            val result = ResearchPersistenceGate.runIfActive(applicationContext) {
                if (!UploadQueueSingleFlight.tryAcquire(INTERACTION_UPLOAD_WORK_NAME)) {
                    Log.i(TAG, "Interaction upload deferred because the queue is already being drained")
                    Result.retry()
                } else try {
                    val failures = InteractionUploadWorkerDelegate(
                        applicationContext, ChronicleDb.getInstance(applicationContext),
                    ).execute()
                    when {
                        failures == 0 -> Result.success()
                        runAttemptCount > INTERACTION_UPLOAD_MAX_ATTEMPTS -> Result.failure()
                        else -> Result.retry()
                    }
                } finally {
                    UploadQueueSingleFlight.release(INTERACTION_UPLOAD_WORK_NAME)
                }
            }
            if (result == null) {
                Log.i(TAG, "Interaction upload skipped without an active study enrollment")
            }
            result ?: Result.success()
        } catch (e: Exception) {
            Log.e(TAG, "Interaction upload worker failed", e)
            Result.failure()
        }
    }
}

class InteractionUploadWorkerDelegate(
    private val context: Context,
    private val db: ChronicleDb,
) {

    /** @return 1 when the active study server failed this run, otherwise 0. */
    fun execute(): Int {
        val dao = db.interactionSampleDao()
        val serverDao = db.uploadServerDao()
        val servers = listOfNotNull(serverDao.getEnabledServer())

        // A fail-closed study (e2ee required, key pending) is deliberately retaining PHI for retry;
        // the age TTL must not silently drop it. Only computed when servers exist.
        val anyFailClosed = servers.isNotEmpty() && run {
            val store = EncryptionSettingStore.of(context)
            servers.any { server ->
                runCatching { UUID.fromString(server.studyId) }.getOrNull()?.let { studyId ->
                    PayloadSealer.routing(
                        store.get(studyId), store.isEncryptionRequired(studyId),
                    ) == PayloadSealer.EncryptionRouting.FAIL_CLOSED
                } ?: false
            }
        }

        try {
            if (anyFailClosed) {
                Log.w(
                    TAG,
                    "Skipping interaction TTL purge: a study is fail-closed (e2ee required, key pending); retaining PHI for retry",
                )
            } else {
                val cutoff = OffsetDateTime.now().minusDays(INTERACTION_SAMPLE_TTL_DAYS).toString()
                val purged = dao.deleteOlderThan(cutoff)
                if (purged > 0) {
                    Log.w(TAG, "Purged $purged interaction sample(s) past the ${INTERACTION_SAMPLE_TTL_DAYS}-day TTL")
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Interaction sample TTL cleanup failed; continuing with upload", e)
        }

        if (servers.isEmpty()) {
            Log.i(TAG, "No enabled upload servers; skipping interaction upload")
            return 0
        }

        val pending = dao.getOldest(INTERACTION_UPLOAD_MAX_BATCH)
        if (pending.isEmpty()) {
            return 0
        }

        // Convert rows to wire DTOs; a corrupt row is skipped (counted), never aborts the batch.
        var malformed = 0
        val events = pending.mapNotNull { entry ->
            try {
                entry.toAndroidInteractionEvent()
            } catch (e: Exception) {
                malformed++
                Log.w(TAG, "Skipping corrupt interaction sample ${entry.id}", e)
                null
            }
        }

        var failureCount = 0
        for (server in servers) {
            try {
                if (events.isNotEmpty()) {
                    val studyId = UUID.fromString(server.studyId)
                    val studyApi = UploadWorker.getChronicleStudyApi(server.url, server.mobileSigningSecretOverride)
                    val restrictedStudyApi = RestrictedUploadApiFactory.get(
                        server.url, server.mobileSigningSecretOverride,
                    )
                    val store = EncryptionSettingStore.of(context)
                    val setting = store.get(studyId)
                    val routing = PayloadSealer.routing(setting, store.isEncryptionRequired(studyId))
                    if (routing == PayloadSealer.EncryptionRouting.FAIL_CLOSED) {
                        // e2ee required but no usable key cached — never upload PHI in plaintext.
                        throw EncryptionRequiredButUnavailableException(studyId)
                    }
                    if (routing == PayloadSealer.EncryptionRouting.ENCRYPT) {
                        val plaintext = JsonSerializer.serializeToBytes(events)
                        val envelope = PayloadSealer.seal(
                            setting = setting!!,
                            studyId = studyId,
                            participantId = server.participantId,
                            payloadType = EncryptedPayloadType.INTERACTION,
                            plaintext = plaintext,
                            sampleCount = events.size,
                        )
                        studyApi.uploadAndroidEncryptedData(
                            studyId, server.participantId, server.sourceDeviceId, server.apiKey, listOf(envelope),
                        )
                    } else {
                        restrictedStudyApi.uploadAndroidInteractionData(
                            studyId, server.participantId, server.sourceDeviceId, server.apiKey, events,
                        )
                    }
                    Log.i(TAG, "[${server.name}] Uploaded ${events.size} interaction event(s)")
                }
            } catch (e: Exception) {
                failureCount++
                Log.e(TAG, "[${server.name}] Interaction upload failed", e)
            }
        }

        // Delete the batch only after the active study server received it (idempotent server-side).
        if (failureCount == 0) {
            dao.deleteByIds(pending.map { it.id })
        }
        Log.i(TAG, "Interaction upload complete: serverFailures=$failureCount, malformedSkipped=$malformed")
        return failureCount
    }
}

/**
 * Schedules the periodic [InteractionUploadWorker]. Idempotent via
 * [ExistingPeriodicWorkPolicy.UPDATE]; constrained to run only with network.
 */
fun scheduleInteractionUploadWork(context: Context) {
    val workRequest = PeriodicWorkRequestBuilder<InteractionUploadWorker>(
        INTERACTION_UPLOAD_INTERVAL_MIN,
        TimeUnit.MINUTES,
    )
        .setConstraints(UPLOAD_NETWORK_CONSTRAINT)
        .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
        .build()

    WorkManager.getInstance(context).enqueueUniquePeriodicWork(
        INTERACTION_UPLOAD_WORK_NAME,
        ExistingPeriodicWorkPolicy.UPDATE,
        workRequest,
    )
}
