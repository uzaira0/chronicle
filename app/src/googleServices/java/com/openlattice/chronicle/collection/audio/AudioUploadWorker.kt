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
import com.openlattice.chronicle.services.upload.LocalUploadModuleFamily
import com.openlattice.chronicle.services.upload.quarantineMalformedSample
import com.openlattice.chronicle.storage.ChronicleDb
import com.openlattice.chronicle.storage.UploadServerEntity
import com.openlattice.chronicle.storage.audioActivitySampleDao
import com.openlattice.chronicle.storage.audioContentSampleDao
import com.openlattice.chronicle.storage.notificationActivitySampleDao
import java.util.UUID
import java.util.concurrent.TimeUnit

private val TAG = AudioUploadWorker::class.java.simpleName

internal const val AUDIO_UPLOAD_WORK_NAME = "app_audio_upload"
private const val AUDIO_UPLOAD_INTERVAL_MIN = 15L
private const val AUDIO_UPLOAD_MAX_BATCH = 5000
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
    com.openlattice.chronicle.security.LeaseBoundWorker(context, workerParameters) {

    override fun runWork(): Result {
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

    /** Uploads one retained stream and removes only acknowledged, valid rows. */
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
        if (!com.openlattice.chronicle.services.upload.UploadDispositionPolicy(db).allows(payloadType)) return 0
        val family = com.openlattice.chronicle.services.upload.UploadRetryGate.familyFor(payloadType)
        if (servers.any { !com.openlattice.chronicle.services.upload.UploadRetryGate.shouldAttempt(context, it, family, db) }) return 0
        val pending = getOldest(AUDIO_UPLOAD_MAX_BATCH)
        if (pending.isEmpty()) return 0

        var malformed = 0
        val validIds = mutableListOf<String>()
        val events = pending.mapNotNull { entry ->
            try {
                toDto(entry).also { validIds += idOf(entry) }
            } catch (e: Exception) {
                malformed++
                Log.w(TAG, "Quarantining corrupt $label sample ${idOf(entry)}", e)
                quarantineMalformedSample(db, servers.single(), label, idOf(entry),
                    JsonSerializer.toJson(entry as Any, (entry as Any)::class.java).toByteArray(),
                    when (payloadType) {
                        EncryptedPayloadType.AUDIO_ACTIVITY -> LocalUploadModuleFamily.AUDIO_ACTIVITY
                        EncryptedPayloadType.AUDIO_CONTENT -> LocalUploadModuleFamily.AUDIO_CONTENT
                        else -> LocalUploadModuleFamily.NOTIFICATION
                    }) {
                    deleteByIds(listOf(idOf(entry)))
                }
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
                com.openlattice.chronicle.services.upload.UploadRetryGate.recordFailure(context, server, family, e, db)
                Log.e(TAG, "[${server.name}] $label upload failed", e)
            }
        }

        if (failureCount == 0) deleteByIds(validIds)
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
