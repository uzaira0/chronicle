package com.openlattice.chronicle.collection.device

import android.content.Context
import android.util.Log
import androidx.work.BackoffPolicy
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.Worker
import androidx.work.WorkerParameters
import com.openlattice.chronicle.collection.AndroidConnectivityStateEvent
import com.openlattice.chronicle.collection.AndroidDeviceSettingsEvent
import com.openlattice.chronicle.collection.DistributionCollectionContributions
import com.openlattice.chronicle.collection.state.ResearchPersistenceGate
import com.openlattice.chronicle.services.upload.DedicatedUploadBatch
import com.openlattice.chronicle.services.upload.DedicatedUploadSource
import com.openlattice.chronicle.services.upload.LocalUploadDiagnosticsStore
import com.openlattice.chronicle.services.upload.LocalUploadModuleFamily
import com.openlattice.chronicle.services.upload.UPLOAD_NETWORK_CONSTRAINT
import com.openlattice.chronicle.services.upload.UploadQueueSingleFlight
import com.openlattice.chronicle.services.upload.runEnrolledUpload
import com.openlattice.chronicle.services.upload.UploadWorker
import com.openlattice.chronicle.services.upload.exactActiveEnrollmentServerResolution
import com.openlattice.chronicle.storage.ChronicleDb
import com.openlattice.chronicle.storage.UploadQueueTable
import com.openlattice.chronicle.storage.UploadServerEntity
import java.util.concurrent.TimeUnit

private val TAG = ExpansionUploadWorker::class.java.simpleName

internal const val EXPANSION_UPLOAD_WORK_NAME = "expansion_modules_upload"
internal const val INPUT_COLLECT_EXPANSION_BEFORE_UPLOAD = "collect_expansion_before_upload"
private const val EXPANSION_UPLOAD_INTERVAL_MIN = 15L
private const val EXPANSION_UPLOAD_MAX_ATTEMPTS = 5

/**
 * Periodic [Worker] that uploads connectivity and device-settings buffers plus streams contributed
 * by the non-Play research distribution.
 *
 * Mirrors [com.openlattice.chronicle.collection.audio.AudioUploadWorkerDelegate]: each stream is
 * uploaded to the active study server and its batch deleted only once it succeeds (endpoints are
 * idempotent via `ON CONFLICT DO NOTHING`).
 */
class ExpansionUploadWorker(context: Context, workerParameters: WorkerParameters) :
    Worker(context, workerParameters) {

    override fun doWork(): Result {
        return try {
            if (inputData.getBoolean(INPUT_COLLECT_EXPANSION_BEFORE_UPLOAD, false)) {
                ResearchPersistenceGate.collectForCurrentOwner(applicationContext) {
                    collectExpansionSamples(applicationContext)
                }
            }
            runEnrolledUpload(applicationContext, TAG) {
                if (!UploadQueueSingleFlight.tryAcquire(EXPANSION_UPLOAD_WORK_NAME)) {
                    Log.i(TAG, "Expansion upload deferred because the queue is already being drained")
                    Result.retry()
                } else try {
                    val failures = ExpansionUploadWorkerDelegate(
                        applicationContext, ChronicleDb.getInstance(applicationContext), stopped = ::isStopped,
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
        } catch (e: Exception) {
            Log.e(TAG, "Expansion upload worker failed", e)
            Result.failure()
        }
    }
}

class ExpansionUploadWorkerDelegate(
    internal val context: Context,
    internal val db: ChronicleDb,
    private val stopped: () -> Boolean = { false },
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
        val streams = linkedMapOf<UploadQueueTable, () -> Int>()
        streams.putAll(DistributionCollectionContributions.uploadAdditionalStreams(this, servers))
        streams[UploadQueueTable.CONNECTIVITY_STATE] = {
            uploadStream(
                servers,
                source = DedicatedUploadSource(
                    elementType = AndroidConnectivityStateEvent::class.java,
                    getOldest = { db.connectivityStateSampleDao().getOldest(it) },
                    idOf = { it.id },
                    toDto = { it.toAndroidConnectivityStateEvent() },
                    table = UploadQueueTable.CONNECTIVITY_STATE,
                    deleteByIds = { db.connectivityStateSampleDao().deleteByIds(it) },
                    plainUpload = { studyId, server, headers, body ->
                        UploadWorker.getChronicleStudyApi(server.url)
                            .uploadAndroidConnectivityStateData(studyId, server.participantId, headers, body)
                    },
                ),
            )
        }
        streams[UploadQueueTable.DEVICE_SETTINGS] = {
            uploadStream(
                servers,
                source = DedicatedUploadSource(
                    elementType = AndroidDeviceSettingsEvent::class.java,
                    getOldest = { db.deviceSettingsSampleDao().getOldest(it) },
                    idOf = { it.id },
                    toDto = { it.toAndroidDeviceSettingsEvent() },
                    table = UploadQueueTable.DEVICE_SETTINGS,
                    deleteByIds = { db.deviceSettingsSampleDao().deleteByIds(it) },
                    plainUpload = { studyId, server, headers, body ->
                        UploadWorker.getChronicleStudyApi(server.url)
                            .uploadAndroidDeviceSettingsData(studyId, server.participantId, headers, body)
                    },
                ),
            )
        }
        return streams.values.sumOf { it() }
    }

    internal fun <T : Any, D : Any> uploadStream(
        servers: List<UploadServerEntity>, source: DedicatedUploadSource<T, D>,
    ): Int = DedicatedUploadBatch(context, db, servers.single(), source, stopped).execute()
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
