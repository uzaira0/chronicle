package com.openlattice.chronicle.collection.audio

import android.content.Context
import android.util.Log
import androidx.work.BackoffPolicy
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.Worker
import androidx.work.WorkerParameters
import com.openlattice.chronicle.api.RestrictedChronicleStudyApi
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
import com.openlattice.chronicle.storage.UploadServerEntity
import com.openlattice.chronicle.storage.audioActivitySampleDao
import com.openlattice.chronicle.storage.audioContentSampleDao
import com.openlattice.chronicle.storage.notificationActivitySampleDao
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.util.UUID
import java.util.concurrent.TimeUnit

private val TAG = AudioUploadWorker::class.java.simpleName

internal const val AUDIO_UPLOAD_WORK_NAME = "app_audio_upload"
private const val AUDIO_UPLOAD_INTERVAL_MIN = 15L
private const val AUDIO_UPLOAD_MAX_BATCH = 5000
private const val AUDIO_SAMPLE_TTL_DAYS = 14L
private const val AUDIO_UPLOAD_MAX_ATTEMPTS = 5

/**
 * Periodic [Worker] that uploads the `audio_activity_samples`, `audio_content_samples`, and
 * `notification_activity_samples` buffers to the server (see `docs/SENSING-EXPANSION-DESIGN.md` §4).
 * Rows are produced by [AudioCaptureController] / [com.openlattice.chronicle.services.notifications.NotificationListener].
 *
 * Mirrors [com.openlattice.chronicle.collection.interaction.InteractionUploadWorkerDelegate]: each
 * stream is uploaded to **every** enabled server and its batch deleted only once **all** succeed
 * (endpoints are idempotent via `ON CONFLICT DO NOTHING`). Encryption routing matches the other
 * Android upload paths — plaintext, sealed envelope, or fail-closed (retain + retry, never plaintext
 * PHI). Each run also takes one Tier-1 [AudioCaptureController.snapshot] so baseline device-audio
 * state is recorded even between transitions.
 */
class AudioUploadWorker(context: Context, workerParameters: WorkerParameters) :
    Worker(context, workerParameters) {

    override fun doWork(): Result {
        return try {
            val result = ResearchPersistenceGate.runIfActive(applicationContext) {
                if (!UploadQueueSingleFlight.tryAcquire(AUDIO_UPLOAD_WORK_NAME)) {
                    Log.i(TAG, "Audio upload deferred because the queue is already being drained")
                    Result.retry()
                } else try {
                    runCatching { AudioCaptureController(applicationContext).snapshot() }
                    val failures = AudioUploadWorkerDelegate(
                        applicationContext, ChronicleDb.getInstance(applicationContext),
                    ).execute()
                    when {
                        failures == 0 -> Result.success()
                        runAttemptCount > AUDIO_UPLOAD_MAX_ATTEMPTS -> Result.failure()
                        else -> Result.retry()
                    }
                } finally {
                    UploadQueueSingleFlight.release(AUDIO_UPLOAD_WORK_NAME)
                }
            }
            if (result == null) {
                Log.i(TAG, "Audio upload skipped without an active study enrollment")
            }
            result ?: Result.success()
        } catch (e: Exception) {
            Log.e(TAG, "Audio upload worker failed", e)
            Result.failure()
        }
    }
}

class AudioUploadWorkerDelegate(
    private val context: Context,
    private val db: ChronicleDb,
) {

    /** @return number of (stream, server) upload failures this run; `0` = everything succeeded. */
    fun execute(): Int {
        val servers = listOfNotNull(db.uploadServerDao().getEnabledServer())
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

        // Bound table growth even with no enabled server (the interaction template purges before the
        // empty-servers check); a fail-closed study deliberately retaining PHI for retry is exempted.
        purgeAll(anyFailClosed)

        if (servers.isEmpty()) {
            Log.i(TAG, "No enabled upload servers; skipping audio upload")
            return 0
        }

        var failures = 0
        failures += uploadStream(
            servers,
            getOldest = { db.audioActivitySampleDao().getOldest(it) },
            idOf = { it.id },
            toDto = { it.toAndroidAudioActivityEvent() },
            payloadType = EncryptedPayloadType.AUDIO_ACTIVITY,
            deleteByIds = { db.audioActivitySampleDao().deleteByIds(it) },
            plainUpload = { api, studyId, server, events ->
                api.uploadAndroidAudioActivityData(studyId, server.participantId, server.sourceDeviceId, server.apiKey, events)
            },
            label = "audio_activity",
        )
        failures += uploadStream(
            servers,
            getOldest = { db.audioContentSampleDao().getOldest(it) },
            idOf = { it.id },
            toDto = { it.toAndroidAudioContentEvent() },
            payloadType = EncryptedPayloadType.AUDIO_CONTENT,
            deleteByIds = { db.audioContentSampleDao().deleteByIds(it) },
            plainUpload = { api, studyId, server, events ->
                api.uploadAndroidAudioContentData(studyId, server.participantId, server.sourceDeviceId, server.apiKey, events)
            },
            label = "audio_content",
        )
        failures += uploadStream(
            servers,
            getOldest = { db.notificationActivitySampleDao().getOldest(it) },
            idOf = { it.id },
            toDto = { it.toAndroidNotificationActivityEvent() },
            payloadType = EncryptedPayloadType.NOTIFICATION_ACTIVITY,
            deleteByIds = { db.notificationActivitySampleDao().deleteByIds(it) },
            plainUpload = { api, studyId, server, events ->
                api.uploadAndroidNotificationActivityData(studyId, server.participantId, server.sourceDeviceId, server.apiKey, events)
            },
            label = "notification_activity",
        )
        return failures
    }

    /**
     * TTL-purges all three buffers. Skipped when a study is fail-closed (deliberately retaining PHI
     * for retry). The cutoff is in the same UTC `…Z` format the rows are stored in, so the TEXT
     * comparison in `deleteOlderThan` is chronological (a local-offset cutoff would compare lexically
     * wrong against the stored `…Z` strings).
     */
    private fun purgeAll(anyFailClosed: Boolean) {
        if (anyFailClosed) {
            Log.w(TAG, "Skipping audio TTL purge: a study is fail-closed (e2ee required, key pending)")
            return
        }
        val cutoff = OffsetDateTime.now(ZoneOffset.UTC).minusDays(AUDIO_SAMPLE_TTL_DAYS).toString()
        runCatching {
            val purged = db.audioActivitySampleDao().deleteOlderThan(cutoff) +
                db.audioContentSampleDao().deleteOlderThan(cutoff) +
                db.notificationActivitySampleDao().deleteOlderThan(cutoff)
            if (purged > 0) Log.w(TAG, "Purged $purged audio/notification sample(s) past the ${AUDIO_SAMPLE_TTL_DAYS}-day TTL")
        }.onFailure { Log.e(TAG, "Audio TTL cleanup failed; continuing", it) }
    }

    private fun <T, D> uploadStream(
        servers: List<UploadServerEntity>,
        getOldest: (Int) -> List<T>,
        idOf: (T) -> String,
        toDto: (T) -> D,
        payloadType: EncryptedPayloadType,
        deleteByIds: (List<String>) -> Unit,
        plainUpload: (RestrictedChronicleStudyApi, UUID, UploadServerEntity, List<D>) -> Unit,
        label: String,
    ): Int {
        val pending = getOldest(AUDIO_UPLOAD_MAX_BATCH)
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
                    val restrictedStudyApi = RestrictedUploadApiFactory.get(
                        server.url, server.mobileSigningSecretOverride,
                    )
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
                        else -> plainUpload(restrictedStudyApi, studyId, server, events)
                    }
                    Log.i(TAG, "[${server.name}] Uploaded ${events.size} $label sample(s)")
                }
            } catch (e: Exception) {
                failureCount++
                Log.e(TAG, "[${server.name}] $label upload failed", e)
            }
        }

        if (failureCount == 0) deleteByIds(pending.map { idOf(it) })
        Log.i(TAG, "$label upload complete: serverFailures=$failureCount, malformedSkipped=$malformed")
        return failureCount
    }
}

/** Schedules the periodic [AudioUploadWorker]. Idempotent; constrained to run only with network. */
fun scheduleAudioUploadWork(context: Context) {
    val workRequest = PeriodicWorkRequestBuilder<AudioUploadWorker>(
        AUDIO_UPLOAD_INTERVAL_MIN,
        TimeUnit.MINUTES,
    )
        .setConstraints(UPLOAD_NETWORK_CONSTRAINT)
        .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
        .build()

    WorkManager.getInstance(context).enqueueUniquePeriodicWork(
        AUDIO_UPLOAD_WORK_NAME,
        ExistingPeriodicWorkPolicy.UPDATE,
        workRequest,
    )
}
