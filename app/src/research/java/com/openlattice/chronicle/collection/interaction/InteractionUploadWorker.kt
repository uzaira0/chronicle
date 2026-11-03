package com.openlattice.chronicle.collection.interaction

import android.content.Context
import android.util.Log
import androidx.work.BackoffPolicy
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.Worker
import androidx.work.WorkerParameters
import com.openlattice.chronicle.api.RestrictedChronicleStudyApi
import com.openlattice.chronicle.collection.AndroidInteractionEvent
import com.openlattice.chronicle.collection.CollectionModuleId
import com.openlattice.chronicle.storage.UploadQueueTable
import com.openlattice.chronicle.services.upload.DedicatedUploadBatch
import com.openlattice.chronicle.services.upload.DedicatedUploadSource
import com.openlattice.chronicle.services.upload.RestrictedUploadApiFactory
import com.openlattice.chronicle.services.upload.UPLOAD_NETWORK_CONSTRAINT
import com.openlattice.chronicle.services.upload.DistributionUploadPolicy
import com.openlattice.chronicle.services.upload.UploadQueueSingleFlight
import com.openlattice.chronicle.services.upload.runEnrolledUpload
import com.openlattice.chronicle.storage.ChronicleDb
import com.openlattice.chronicle.storage.InteractionSampleEntry
import com.openlattice.chronicle.storage.UploadServerEntity
import com.openlattice.chronicle.storage.interactionSampleDao
import java.util.concurrent.TimeUnit

private val TAG = InteractionUploadWorker::class.java.simpleName

/** Unique WorkManager name for the periodic interaction-events upload work. */
internal const val INTERACTION_UPLOAD_WORK_NAME = "interaction_events_upload"

/** Upload cadence — 15 min (the WorkManager periodic minimum). */
private const val INTERACTION_UPLOAD_INTERVAL_MIN = 15L


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
 * idempotent via `ON CONFLICT DO NOTHING`).
 */
class InteractionUploadWorker(context: Context, workerParameters: WorkerParameters) :
    Worker(context, workerParameters) {

    override fun doWork(): Result {
        return try {
            runEnrolledUpload(applicationContext, TAG) {
                if (!UploadQueueSingleFlight.tryAcquire(INTERACTION_UPLOAD_WORK_NAME)) {
                    Log.i(TAG, "Interaction upload deferred because the queue is already being drained")
                    Result.retry()
                } else try {
                    val failures = InteractionUploadWorkerDelegate(
                        applicationContext, ChronicleDb.getInstance(applicationContext), stopped = ::isStopped,
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
        } catch (e: Exception) {
            Log.e(TAG, "Interaction upload worker failed", e)
            Result.failure()
        }
    }
}

internal class InteractionUploadWorkerDelegate(
    private val context: Context,
    private val db: ChronicleDb,
    private val stopped: () -> Boolean = { false },
    private val restrictedStudyApiFor: (UploadServerEntity) -> RestrictedChronicleStudyApi = {
        RestrictedUploadApiFactory.get(it.url)
    },
) {

    fun execute(): Int {
        if (!DistributionUploadPolicy.allows(CollectionModuleId.INTERACTION_EVENTS)) return 0
        val server = db.uploadServerDao().getEnabledServer() ?: return 0
        val dao = db.interactionSampleDao()
        return DedicatedUploadBatch(context, db, server, DedicatedUploadSource(
            elementType = AndroidInteractionEvent::class.java,
            table = UploadQueueTable.INTERACTION, getOldest = dao::getOldest,
            idOf = InteractionSampleEntry::id, toDto = InteractionSampleEntry::toAndroidInteractionEvent,
            deleteByIds = dao::deleteByIds,
            plainUpload = { study, target, headers, body ->
                restrictedStudyApiFor(target).uploadAndroidInteractionData(study, target.participantId, headers, body)
            },
        ), stopped).execute()
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
