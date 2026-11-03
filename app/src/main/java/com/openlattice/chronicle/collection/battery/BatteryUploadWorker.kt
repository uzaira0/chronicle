package com.openlattice.chronicle.collection.battery

import android.content.Context
import android.util.Log
import androidx.work.BackoffPolicy
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.Worker
import androidx.work.WorkerParameters
import com.openlattice.chronicle.collection.BatterySample
import com.openlattice.chronicle.collection.CollectionModuleId
import com.openlattice.chronicle.services.upload.CollectedUploadBody
import com.openlattice.chronicle.services.upload.LocalUploadDiagnosticsStore
import com.openlattice.chronicle.services.upload.LocalUploadModuleFamily
import com.openlattice.chronicle.services.upload.QueueStream
import com.openlattice.chronicle.services.upload.QueueUpload
import com.openlattice.chronicle.services.upload.UPLOAD_NETWORK_CONSTRAINT
import com.openlattice.chronicle.services.upload.DistributionUploadPolicy
import com.openlattice.chronicle.services.upload.UploadQueueSingleFlight
import com.openlattice.chronicle.services.upload.runEnrolledUpload
import com.openlattice.chronicle.services.upload.UploadWorker
import com.openlattice.chronicle.services.upload.deleteFinishedRows
import com.openlattice.chronicle.services.upload.exactActiveEnrollmentServerResolution
import com.openlattice.chronicle.services.upload.handleServerUploadFailure
import com.openlattice.chronicle.services.upload.recordDelivered
import com.openlattice.chronicle.storage.BatterySampleEntry
import com.openlattice.chronicle.storage.ChronicleDb
import com.openlattice.chronicle.storage.UploadQueueTable
import com.openlattice.chronicle.storage.UploadServerEntity
import com.openlattice.chronicle.storage.insertOwnedDay
import java.time.LocalDate
import java.lang.reflect.Type
import java.time.OffsetDateTime
import java.util.UUID
import java.util.concurrent.TimeUnit

private val TAG = BatteryUploadWorker::class.java.simpleName

/** Unique WorkManager name for the periodic battery-telemetry upload work. */
internal const val BATTERY_UPLOAD_WORK_NAME = "battery_telemetry_upload"

/** Upload cadence — 15 min (the WorkManager periodic minimum). */
private const val BATTERY_UPLOAD_INTERVAL_MIN = 15L

/** Run-attempt count above which a failing upload stops retrying. */
private const val BATTERY_UPLOAD_MAX_ATTEMPTS = 5

/**
 * Periodic [Worker] that uploads collected `battery_samples` rows to the server
 * (see `docs/SENSING-EXPANSION-DESIGN.md` §5). Pairs with [BatteryCollectionWorker]:
 * collection writes rows locally, this worker ships them to the
 * `/chronicle/v4/study/.../android/battery` endpoint.
 *
 */
class BatteryUploadWorker(context: Context, workerParameters: WorkerParameters) :
    Worker(context, workerParameters) {

    override fun doWork(): Result {
        return try {
            runEnrolledUpload(applicationContext, TAG) {
                if (!UploadQueueSingleFlight.tryAcquire(BATTERY_UPLOAD_WORK_NAME)) {
                    Log.i(TAG, "Battery upload deferred because the queue is already being drained")
                    Result.retry()
                } else try {
                    val failures = BatteryUploadWorkerDelegate(
                        applicationContext, ChronicleDb.getInstance(applicationContext), stopped = ::isStopped,
                    ).execute()
                    when {
                        failures == 0 -> Result.success()
                        runAttemptCount > BATTERY_UPLOAD_MAX_ATTEMPTS -> Result.failure()
                        else -> Result.retry()
                    }
                } finally {
                    UploadQueueSingleFlight.release(BATTERY_UPLOAD_WORK_NAME)
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Battery upload worker failed", e)
            Result.failure()
        }
    }
}

/**
 * Core battery-upload logic, separated from the [Worker] so it has no `WorkManager`
 * coupling.
 *
 * Pending rows are uploaded only to the exact active-enrollment server and deleted after
 * that destination receives the batch. The server endpoint is idempotent
 * (`ON CONFLICT (study_id, participant_id, sample_id) DO NOTHING`), so when one server
 * fails and the batch is kept, the next run can safely retry it.
 */
class BatteryUploadWorkerDelegate(
    private val context: Context,
    private val db: ChronicleDb,
    private val stopped: () -> Boolean = { false },
) {
    fun execute(): Int {
        if (!DistributionUploadPolicy.allows(CollectionModuleId.BATTERY_TELEMETRY)) return 0
        val destination = exactActiveEnrollmentServerResolution(context, db)
        val server = destination.server
        if (server == null) {
            LocalUploadDiagnosticsStore.of(context).record(LocalUploadModuleFamily.BATTERY, requireNotNull(destination.issue))
            Log.w(TAG, "${UploadQueueTable.BATTERY.name} samples retained without an eligible active-enrollment server")
            return 1
        }
        val dao = db.batterySampleDao()
        val api = UploadWorker.getChronicleStudyApi(server.url)
        val studyId = UUID.fromString(server.studyId)
        val removeFinished = deleteFinishedRows(BatterySampleEntry::id, dao::deleteByIds)
        val stream = object : QueueStream<BatterySampleEntry, BatterySample> {
            override val table = UploadQueueTable.BATTERY
            override val elementType: Type = BatterySample::class.java
            override fun next(limit: Int) = dao.getOldest(limit)
            override fun rowId(item: BatterySampleEntry) = item.id
            override fun toWire(item: BatterySampleEntry) = item.toBatterySample()
            // The route answers per row; a row the participant's decisions refuse is discarded, never re-sent.
            override fun post(headers: Map<String, String>, body: CollectedUploadBody<BatterySample>) =
                api.uploadAndroidBatteryData(studyId, server.participantId, headers, body)
            override fun remove(done: List<BatterySampleEntry>) = removeFinished(done)
            override fun wireId(row: BatterySample): UUID = UUID.fromString(row.id)
            override fun delivered(items: List<BatterySampleEntry>) =
                recordDelivered(db, server, UploadQueueTable.BATTERY, items.size)
        }
        return QueueUpload(context, db, server, stream).execute(stopped, onFailure = { error -> recordFailure(server, error) })
    }

    private fun recordFailure(server: UploadServerEntity, error: Exception) {
        handleServerUploadFailure(context, TAG, error, LocalUploadModuleFamily.BATTERY,
            server.batteryConsecutiveFailures) { failures, _ ->
            db.uploadServerDao().recordBatteryUploadFailure(server.id, OffsetDateTime.now().toString(), failures)
        }
        try {
            val today = LocalDate.now().toString()
            db.uploadStatsDao().insertOwnedDay(server, today)
            db.uploadStatsDao().incrementBatteryFailureCount(server.id, today, 1)
        } catch (statsError: Exception) {
            Log.e(TAG, "Failed to record BATTERY upload failure stats", statsError)
        }
    }
}

/**
 * Schedules the periodic [BatteryUploadWorker]. Idempotent via
 * [ExistingPeriodicWorkPolicy.UPDATE]; constrained to run only with network.
 */
fun scheduleBatteryUploadWork(context: Context) {
    val workRequest = PeriodicWorkRequestBuilder<BatteryUploadWorker>(
        BATTERY_UPLOAD_INTERVAL_MIN,
        TimeUnit.MINUTES,
    )
        .setConstraints(UPLOAD_NETWORK_CONSTRAINT)
        .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
        .build()

    WorkManager.getInstance(context).enqueueUniquePeriodicWork(
        BATTERY_UPLOAD_WORK_NAME,
        ExistingPeriodicWorkPolicy.UPDATE,
        workRequest,
    )
}
