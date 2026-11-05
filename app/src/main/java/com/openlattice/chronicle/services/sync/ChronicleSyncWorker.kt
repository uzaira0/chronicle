package com.openlattice.chronicle.services.sync

import com.openlattice.chronicle.EnrolledWork
import com.openlattice.chronicle.collection.battery.scheduleBatteryCollectionWork
import com.openlattice.chronicle.collection.battery.scheduleBatteryUploadWork
import com.openlattice.chronicle.collection.device.scheduleExpansionCollectionWork
import com.openlattice.chronicle.collection.device.scheduleExpansionUploadWork

import android.content.Context
import android.util.Log
import androidx.work.BackoffPolicy
import androidx.work.Data
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.ListenableWorker
import androidx.work.OneTimeWorkRequest
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.Operation
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.Worker
import androidx.work.WorkerParameters
import com.openlattice.chronicle.WorkSchedulingStatus
import com.openlattice.chronicle.collection.device.EXPANSION_UPLOAD_WORK_NAME
import com.openlattice.chronicle.collection.device.ExpansionUploadWorker
import com.openlattice.chronicle.collection.device.INPUT_COLLECT_EXPANSION_BEFORE_UPLOAD
import com.openlattice.chronicle.services.lifecycle.DeviceStateSampler
import com.openlattice.chronicle.services.upload.COMBINED_UPLOAD_WORK_NAME
import com.openlattice.chronicle.services.upload.LocalOperationalIssue
import com.openlattice.chronicle.services.upload.LocalUploadDiagnosticsStore
import com.openlattice.chronicle.services.upload.LocalUploadModuleFamily
import com.openlattice.chronicle.services.upload.UPLOAD_NETWORK_CONSTRAINT
import com.openlattice.chronicle.services.upload.recordCollectionAccessLoss
import com.openlattice.chronicle.services.upload.recordRecentProcessExits
import com.openlattice.chronicle.services.upload.retryOnStorageFailure
import com.openlattice.chronicle.services.upload.runCombinedUpload
import com.openlattice.chronicle.services.usage.USAGE_WORK_NAME
import com.openlattice.chronicle.services.usage.collectUsage
import java.util.concurrent.Executor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.random.Random

private const val TAG = "ChronicleSyncWorker"
const val CHRONICLE_SYNC_WORK_NAME = "chronicle_sync"
const val CHRONICLE_SYNC_IMMEDIATE_WORK_NAME = "chronicle_sync_immediate"
internal const val AUXILIARY_UPLOAD_IMMEDIATE_WORK_NAME = "auxiliary_upload_immediate"
private const val SYNC_INTERVAL_MINUTES = 15L
internal val IMMEDIATE_UPLOAD_EXISTING_WORK_POLICY = ExistingWorkPolicy.KEEP

internal data class AuxiliaryUploadDescriptor(
    /** Shared queue owner and authoritative periodic WorkManager name. */
    val queueOwner: String,
    val workerClass: Class<out ListenableWorker>,
    val collectBeforeManualUpload: Boolean = false,
)

/**
 * Module-specific queues that are intentionally separate from CombinedUploadWorker. Keeping this
 * list explicit makes the Uploads screen's "Upload now" contract testable and prevents a newly
 * added mobile queue from being mistaken for part of the combined usage/sensor/battery drain.
 */
internal val AUXILIARY_UPLOADS: List<AuxiliaryUploadDescriptor> = buildList {
    addAll(restrictedAuxiliaryUploads())
    add(AuxiliaryUploadDescriptor(
        EXPANSION_UPLOAD_WORK_NAME,
        ExpansionUploadWorker::class.java,
        collectBeforeManualUpload = true,
    ))
}

/** Collects usage, then polls the status and drains the upload queues until they are empty or WorkManager stops the run. */
class ChronicleSyncWorker(context: Context, params: WorkerParameters) : Worker(context, params) {
    // A storage failure retries the run; any other failure lost this run's usage poll, which the
    // study sees in the upload diagnostics, not only in WorkManager's log of the throw.
    // The store binds the enrollment at the start, so a run lost across re-enrollment is not reported as the new one's.
    override fun doWork(): Result {
        val diagnostics = runCatching { LocalUploadDiagnosticsStore.of(applicationContext) }
        return retryOnStorageFailure(onLost = { recordLostRun(diagnostics) }, work = ::sync)
    }

    private fun recordLostRun(diagnostics: kotlin.Result<LocalUploadDiagnosticsStore>) {
        diagnostics.mapCatching {
            it.recordOperational(LocalUploadModuleFamily.USAGE_LIFECYCLE, LocalOperationalIssue.LOCAL_WRITE_FAILED)
        }.onFailure { Log.e(TAG, "Unable to record the usage poll failure: ${it.javaClass.simpleName}") }
    }

    private fun sync(): Result {
        if (!RUNNING.compareAndSet(false, true)) {
            Log.w(TAG, "Sync run deferred because another sync run is active")
            return Result.retry()
        }
        try {
            Log.i(TAG, "Sync run started")
            // Crash/ANR counts since the last run go out with the upload diagnostics below.
            recordRecentProcessExits(applicationContext)
            // An accepted module whose Android access was removed (e.g. accessibility on force-stop)
            // collects nothing; report it to the server and tell the participant once per episode.
            recordCollectionAccessLoss(applicationContext)
            // Device state (battery and charging, Battery Saver, network, low memory) is sampled here,
            // at boot and when Chronicle opens; no foreground service is kept running for it, and
            // Android 8+ does not deliver those broadcasts to manifest receivers. The sampler
            // records only states that changed, so a sample per sync adds no duplicates. Boot
            // (StartOnBoot) and opening Chronicle (MainActivity) use the same entry point,
            // DeviceStateSampler.record.
            DeviceStateSampler.record(applicationContext)

            // Refresh module settings and acknowledgment gates on each coordinated sync.
            // enqueueNow coalesces bursts and remains fail-closed.
            EnrolledWork.refreshCollectionSettings(applicationContext)

            // collectUsage enforces the collection-loop acknowledgment gate.
            val collected = if (collectUsage(applicationContext)) Result.success() else Result.retry()
            // The upload runs the status step first (runEnrolledUpload): a stop it learns of drops what was collected
            // inside it, and a failed poll uploads nothing this run.
            val uploaded = runCombinedUpload(applicationContext, runAttemptCount, ::isStopped)
            val result = mergeSyncResults(collected, uploaded)
            Log.i(TAG, "Sync run finished result=$result")
            return result
        } finally {
            RUNNING.set(false)
        }
    }

    companion object {
        private val RUNNING = AtomicBoolean(false)
    }
}

fun mergeSyncResults(first: ListenableWorker.Result, second: ListenableWorker.Result): ListenableWorker.Result {
    // Compare against the factory-produced result instances rather than the library-restricted
    // Result.Failure / Result.Retry subtypes (RestrictedApi). The merged inputs are always the
    // no-output-data results these factories return, whose equals() is value-based, so equality
    // is an exact, supported substitute for the subtype checks.
    val failure = ListenableWorker.Result.failure()
    val retry = ListenableWorker.Result.retry()
    return when {
        first == failure || second == failure -> failure
        first == retry || second == retry -> retry
        else -> ListenableWorker.Result.success()
    }
}

private fun uploadRetryBackoffSeconds(): Long = Random.nextLong(30, 91)

internal fun buildPeriodicChronicleSyncRequest() =
    PeriodicWorkRequestBuilder<ChronicleSyncWorker>(SYNC_INTERVAL_MINUTES, TimeUnit.MINUTES)
        .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, uploadRetryBackoffSeconds(), TimeUnit.SECONDS)
        .build()

internal fun buildImmediateChronicleSyncRequest() =
    OneTimeWorkRequestBuilder<ChronicleSyncWorker>()
        .setConstraints(UPLOAD_NETWORK_CONSTRAINT)
        .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, uploadRetryBackoffSeconds(), TimeUnit.SECONDS)
        .build()

fun scheduleChronicleSyncWork(context: Context) {
    val wm = WorkManager.getInstance(context)
    // Battery telemetry is low-frequency collection + upload on its own workers.
    // enqueueUniquePeriodicWork with UPDATE makes this idempotent across re-configuration.
    scheduleBatteryCollectionWork(context)
    scheduleBatteryUploadWork(context)
    // Restricted research upload queues are absent from the public Play/Amazon source graph.
    scheduleRestrictedUploadWork(context)
    // Public device telemetry (connectivity_state and device_settings) plus non-Play research
    // contributions — their own collection + upload workers, idempotent.
    scheduleExpansionCollectionWork(context)
    scheduleExpansionUploadWork(context)
    // Installs from before the single sync path may still hold the split periodic workers.
    wm.cancelUniqueWork(COMBINED_UPLOAD_WORK_NAME)
    wm.cancelUniqueWork(USAGE_WORK_NAME)
    val request = buildPeriodicChronicleSyncRequest()
    val scheduled = wm.enqueueUniquePeriodicWork(CHRONICLE_SYNC_WORK_NAME, ExistingPeriodicWorkPolicy.UPDATE, request)
    // Initialization failure is a hint, never a permanent scheduling veto. Verify the enqueue.
    val verified = scheduled.result
    verified.addListener({
        try {
            verified.get()
            WorkSchedulingStatus.schedulingSucceeded()
        } catch (error: Exception) {
            WorkSchedulingStatus.initializationFailed(error, context.applicationContext)
        }
    }, Executor { it.run() })
}

fun triggerImmediateChronicleSync(context: Context): Operation {
    val request = buildImmediateChronicleSyncRequest()

    return enqueueGroupedUniqueWork(
        workManager = WorkManager.getInstance(context),
        uniqueWorkName = CHRONICLE_SYNC_IMMEDIATE_WORK_NAME,
        requests = listOf(request) + buildImmediateAuxiliaryUploadRequests(
            collectExpansionBeforeUpload = true,
        ),
    )
}

internal fun buildImmediateAuxiliaryUploadRequests(
    collectExpansionBeforeUpload: Boolean,
): List<OneTimeWorkRequest> = AUXILIARY_UPLOADS.map { upload ->
    OneTimeWorkRequest.Builder(upload.workerClass)
            .setConstraints(UPLOAD_NETWORK_CONSTRAINT)
            .setInputData(
                Data.Builder()
                    .putBoolean(
                        INPUT_COLLECT_EXPANSION_BEFORE_UPLOAD,
                        collectExpansionBeforeUpload && upload.collectBeforeManualUpload,
                    )
                    .build(),
            )
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, uploadRetryBackoffSeconds(), TimeUnit.SECONDS)
            .build()
}

/**
 * Enqueues one unique group so KEEP coalesces the complete manual action. Group members remain
 * independent: a retry/failure in one queue must not prevent the other queues from being drained.
 */
internal fun enqueueGroupedUniqueWork(
    workManager: WorkManager,
    uniqueWorkName: String,
    requests: List<OneTimeWorkRequest>,
): Operation {
    require(requests.isNotEmpty()) { "At least one upload request is required" }
    return workManager.beginUniqueWork(
        uniqueWorkName, IMMEDIATE_UPLOAD_EXISTING_WORK_POLICY, requests,
    ).enqueue()
}

