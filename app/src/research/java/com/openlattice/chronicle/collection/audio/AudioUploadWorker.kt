package com.openlattice.chronicle.collection.audio

import android.content.Context
import android.util.Log
import androidx.work.BackoffPolicy
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.Worker
import androidx.work.WorkerParameters
import com.openlattice.chronicle.collection.AndroidAudioActivityEvent
import com.openlattice.chronicle.collection.AndroidAudioContentEvent
import com.openlattice.chronicle.collection.AndroidNotificationActivityEvent
import com.openlattice.chronicle.storage.UploadQueueTable
import com.openlattice.chronicle.services.upload.DedicatedUploadBatch
import com.openlattice.chronicle.services.upload.DedicatedUploadSource
import com.openlattice.chronicle.services.upload.RestrictedUploadApiFactory
import com.openlattice.chronicle.services.upload.UPLOAD_NETWORK_CONSTRAINT
import com.openlattice.chronicle.services.upload.UploadQueueSingleFlight
import com.openlattice.chronicle.services.upload.runEnrolledUpload
import com.openlattice.chronicle.storage.ChronicleDb
import com.openlattice.chronicle.storage.UploadServerEntity
import com.openlattice.chronicle.storage.audioActivitySampleDao
import com.openlattice.chronicle.storage.audioContentSampleDao
import com.openlattice.chronicle.storage.notificationActivitySampleDao
import java.util.concurrent.TimeUnit

private val TAG = AudioUploadWorker::class.java.simpleName

internal const val AUDIO_UPLOAD_WORK_NAME = "app_audio_upload"
private const val AUDIO_UPLOAD_INTERVAL_MIN = 15L
private const val AUDIO_UPLOAD_MAX_ATTEMPTS = 5

/**
 * Periodic [Worker] that uploads the `audio_activity_samples`, `audio_content_samples`, and
 * `notification_activity_samples` buffers to the server (see `docs/SENSING-EXPANSION-DESIGN.md` §4).
 * Rows are produced by [AudioCaptureController] / [com.openlattice.chronicle.services.notifications.NotificationListener].
 *
 * Mirrors [com.openlattice.chronicle.collection.interaction.InteractionUploadWorkerDelegate]: each
 * stream is uploaded to **every** enabled server and its batch deleted only once **all** succeed
 * (endpoints are idempotent via `ON CONFLICT DO NOTHING`). Each run also takes one Tier-1 [AudioCaptureController.snapshot] so baseline device-audio
 * state is recorded even between transitions.
 */
class AudioUploadWorker(context: Context, workerParameters: WorkerParameters) :
    Worker(context, workerParameters) {

    override fun doWork(): Result {
        return try {
            runEnrolledUpload(applicationContext, TAG) {
                if (!UploadQueueSingleFlight.tryAcquire(AUDIO_UPLOAD_WORK_NAME)) {
                    Log.i(TAG, "Audio upload deferred because the queue is already being drained")
                    Result.retry()
                } else try {
                    runCatching { AudioCaptureController(applicationContext).snapshot() }
                    val failures = AudioUploadWorkerDelegate(
                        applicationContext, ChronicleDb.getInstance(applicationContext), stopped = ::isStopped,
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
        } catch (e: Exception) {
            Log.e(TAG, "Audio upload worker failed", e)
            Result.failure()
        }
    }
}

class AudioUploadWorkerDelegate(
    private val context: Context,
    private val db: ChronicleDb,
    private val stopped: () -> Boolean = { false },
) {

    /** @return number of (stream, server) upload failures this run; `0` = everything succeeded. */
    fun execute(): Int {
        val servers = listOfNotNull(db.uploadServerDao().getEnabledServer())
        if (servers.isEmpty()) {
            Log.i(TAG, "No enabled upload servers; skipping audio upload")
            return 0
        }

        return streamsFor(servers).values.sumOf { it() }
    }

    private fun streamsFor(servers: List<UploadServerEntity>): Map<UploadQueueTable, () -> Int> {
        val streams = linkedMapOf<UploadQueueTable, () -> Int>()
        streams[UploadQueueTable.AUDIO_ACTIVITY] = {
            uploadStream(
                servers,
                source = DedicatedUploadSource(
                    elementType = AndroidAudioActivityEvent::class.java,
                    getOldest = { db.audioActivitySampleDao().getOldest(it) },
                    idOf = { it.id },
                    toDto = { it.toAndroidAudioActivityEvent() },
                    table = UploadQueueTable.AUDIO_ACTIVITY,
                    deleteByIds = { db.audioActivitySampleDao().deleteByIds(it) },
                    plainUpload = { studyId, server, headers, body ->
                        RestrictedUploadApiFactory.get(server.url).uploadAndroidAudioActivityData(studyId, server.participantId, headers, body)
                    },
                ),
            )
        }
        streams[UploadQueueTable.AUDIO_CONTENT] = {
            uploadStream(
                servers,
                source = DedicatedUploadSource(
                    elementType = AndroidAudioContentEvent::class.java,
                    getOldest = { db.audioContentSampleDao().getOldest(it) },
                    idOf = { it.id },
                    toDto = { it.toAndroidAudioContentEvent() },
                    table = UploadQueueTable.AUDIO_CONTENT,
                    deleteByIds = { db.audioContentSampleDao().deleteByIds(it) },
                    plainUpload = { studyId, server, headers, body ->
                        RestrictedUploadApiFactory.get(server.url).uploadAndroidAudioContentData(studyId, server.participantId, headers, body)
                    },
                ),
            )
        }
        streams[UploadQueueTable.NOTIFICATION_ACTIVITY] = {
            uploadStream(
                servers,
                source = DedicatedUploadSource(
                    elementType = AndroidNotificationActivityEvent::class.java,
                    getOldest = { db.notificationActivitySampleDao().getOldest(it) },
                    idOf = { it.id },
                    toDto = { it.toAndroidNotificationActivityEvent() },
                    table = UploadQueueTable.NOTIFICATION_ACTIVITY,
                    deleteByIds = { db.notificationActivitySampleDao().deleteByIds(it) },
                    plainUpload = { studyId, server, headers, body ->
                        RestrictedUploadApiFactory.get(server.url).uploadAndroidNotificationActivityData(studyId, server.participantId, headers, body)
                    },
                ),
            )
        }
        return streams
    }

    /** Uploads one retained stream and removes only acknowledged, valid rows. */
    internal fun <T : Any, D : Any> uploadStream(
        servers: List<UploadServerEntity>, source: DedicatedUploadSource<T, D>,
    ): Int = DedicatedUploadBatch(context, db, servers.single(), source, stopped).execute()
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
