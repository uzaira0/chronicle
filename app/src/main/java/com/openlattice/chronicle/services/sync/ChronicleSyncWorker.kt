package com.openlattice.chronicle.services.sync

import android.content.Context
import android.util.Log
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.Data
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.ListenableWorker
import androidx.work.OneTimeWorkRequest
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.Worker
import androidx.work.WorkerParameters
import com.openlattice.chronicle.collection.CollectionModules
import com.openlattice.chronicle.collection.device.EXPANSION_UPLOAD_WORK_NAME
import com.openlattice.chronicle.collection.device.ExpansionUploadWorker
import com.openlattice.chronicle.collection.device.INPUT_COLLECT_EXPANSION_BEFORE_UPLOAD
import com.openlattice.chronicle.collection.state.CollectionSettingsSyncWorker
import com.openlattice.chronicle.services.upload.COMBINED_UPLOAD_WORK_NAME
import com.openlattice.chronicle.services.upload.CombinedUploadWorker
import com.openlattice.chronicle.services.upload.LEGACY_SENSOR_UPLOAD_WORK_NAME
import com.openlattice.chronicle.services.release.scheduleMinimalPlayArtifactBoundary
import com.openlattice.chronicle.services.upload.LEGACY_USAGE_UPLOAD_WORK_NAME
import com.openlattice.chronicle.services.upload.UPLOAD_NETWORK_CONSTRAINT
import com.openlattice.chronicle.services.upload.runCombinedUpload
import com.openlattice.chronicle.services.usage.USAGE_WORK_NAME
import com.openlattice.chronicle.services.usage.collectUsage
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

private const val TAG = "ChronicleSyncWorker"
const val CHRONICLE_SYNC_WORK_NAME = "chronicle_sync"
const val CHRONICLE_SYNC_IMMEDIATE_WORK_NAME = "chronicle_sync_immediate"
internal const val AUXILIARY_UPLOAD_IMMEDIATE_WORK_NAME = "auxiliary_upload_immediate"
const val INPUT_STRATEGY = "strategy"
internal val MANUAL_SYNC_STRATEGY = ChronicleSyncStrategy.COORDINATED_COLLECT_THEN_UPLOAD
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

class ChronicleSyncWorker(context: Context, params: WorkerParameters) : Worker(context, params) {
    override fun doWork(): Result {
        if (!RUNNING.compareAndSet(false, true)) {
            Log.w(TAG, "Sync run deferred because another sync run is active")
            return Result.retry()
        }
        try {
            val configured = SyncRuntimeConfig.load(applicationContext)
            val strategy = ChronicleSyncStrategy.fromConfigValue(
                inputData.getString(INPUT_STRATEGY) ?: configured.strategy.configValue
            )
            Log.i(TAG, "Sync run started strategy=${strategy.configValue}")
            // Drives the runtime CollectionModuleRegistry: builds it lazily (off the startup
            // thread) on first sync and emits a redaction-safe id=status health line. Read-only
            // and never throws — module resolution for collection itself still uses the holders.
            Log.i(TAG, "Collection module health: ${CollectionModules.moduleHealthSummary(applicationContext)}")

            // Refresh the per-module collection settings + acknowledgment/gate state on every
            // coordinated sync (collection loop closure). This is the ACTIVE periodic sync path;
            // the legacy UploadWorker.doWork piggyback never fires once a coordinated strategy is
            // configured (this worker cancels the legacy upload workers), so without this the loop
            // only ever refreshed at enrollment + its own 1h floor — and never on a device enrolled
            // before the feature shipped. enqueueNow coalesces bursts (REPLACE) and is fail-closed.
            CollectionSettingsSyncWorker.enqueueNow(applicationContext)

            val result = when (strategy) {
                ChronicleSyncStrategy.SPLIT_PERIODIC -> runUploadThenCollect()
                ChronicleSyncStrategy.COORDINATED_COLLECT_THEN_UPLOAD -> runCollectThenUpload()
                ChronicleSyncStrategy.COORDINATED_UPLOAD_THEN_COLLECT -> runUploadThenCollect()
            }
            Log.i(TAG, "Sync run finished strategy=${strategy.configValue} result=$result")
            return result
        } finally {
            RUNNING.set(false)
        }
    }

    private fun runCollectThenUpload(): Result {
        val usageResult = runUsageCollection()
        val uploadResult = runCombinedUpload(applicationContext, runAttemptCount)
        return mergeSyncResults(usageResult, uploadResult)
    }

    private fun runUploadThenCollect(): Result {
        val uploadResult = runCombinedUpload(applicationContext, runAttemptCount)
        val usageResult = runUsageCollection()
        return mergeSyncResults(uploadResult, usageResult)
    }

    private fun runUsageCollection(): Result {
        return try {
            // Route through the shared selector so the coordinated strategy enforces the same
            // collection-loop acknowledgment gate as the split-periodic path. Constructing a
            // delegate directly here was the gate-bypass bug: the coordinated path is the one the
            // device actually runs, and it collected usage_events before acknowledgment.
            val collected = collectUsage(applicationContext)
            if (collected) Result.success() else Result.retry()
        } catch (e: Exception) {
            Log.e(TAG, "Usage collection failed", e)
            Result.failure()
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

fun scheduleChronicleSyncWork(context: Context) {
    // Must be enqueued before any collection/upload work. Every writer also consults the
    // corresponding fail-closed state, so scheduling races cannot bypass the boundary.
    scheduleMinimalPlayArtifactBoundary(context)
    val config = SyncRuntimeConfig.load(context)
    val wm = WorkManager.getInstance(context)
    // Battery telemetry is strategy-independent, low-frequency collection + upload —
    // schedule both workers unconditionally so they run under either sync strategy.
    // enqueueUniquePeriodicWork with UPDATE makes this idempotent across re-configuration.
    com.openlattice.chronicle.collection.battery.scheduleBatteryCollectionWork(context)
    com.openlattice.chronicle.collection.battery.scheduleBatteryUploadWork(context)
    // Restricted research upload queues are absent from the public Play/Amazon source graph.
    scheduleRestrictedUploadWork(context)
    // Public device telemetry (connectivity_state and device_settings) plus non-Play research
    // contributions — strategy-independent collection + upload, idempotent.
    com.openlattice.chronicle.collection.device.scheduleExpansionCollectionWork(context)
    com.openlattice.chronicle.collection.device.scheduleExpansionUploadWork(context)
    when (config.strategy) {
        ChronicleSyncStrategy.SPLIT_PERIODIC -> {
            wm.cancelUniqueWork(CHRONICLE_SYNC_WORK_NAME)
            com.openlattice.chronicle.services.upload.scheduleCombinedUploadWork(context)
            com.openlattice.chronicle.services.usage.scheduleUsageMonitoringWork(context)
            Log.i(TAG, "Scheduled strategy=${config.strategy.configValue} as split periodic workers")
        }

        ChronicleSyncStrategy.COORDINATED_COLLECT_THEN_UPLOAD,
        ChronicleSyncStrategy.COORDINATED_UPLOAD_THEN_COLLECT -> {
            wm.cancelUniqueWork(COMBINED_UPLOAD_WORK_NAME)
            wm.cancelUniqueWork(USAGE_WORK_NAME)
            wm.cancelUniqueWork(LEGACY_USAGE_UPLOAD_WORK_NAME)
            wm.cancelUniqueWork(LEGACY_SENSOR_UPLOAD_WORK_NAME)

            val constraints = coordinatedSyncConstraints(config.requiresBatteryNotLow)
            val request = PeriodicWorkRequestBuilder<ChronicleSyncWorker>(
                config.intervalMinutes,
                TimeUnit.MINUTES
            )
                .setConstraints(constraints)
                .setInputData(
                    androidx.work.Data.Builder()
                        .putString(INPUT_STRATEGY, config.strategy.configValue)
                        .build()
                )
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
                .build()

            wm.enqueueUniquePeriodicWork(
                CHRONICLE_SYNC_WORK_NAME,
                ExistingPeriodicWorkPolicy.UPDATE,
                request
            )
            Log.i(TAG, "Scheduled strategy=${config.strategy.configValue} interval=${config.intervalMinutes}m batteryNotLow=${config.requiresBatteryNotLow}")
        }
    }
}

fun triggerImmediateChronicleSync(context: Context) {
    val request = OneTimeWorkRequestBuilder<ChronicleSyncWorker>()
        .setConstraints(UPLOAD_NETWORK_CONSTRAINT)
        .setInputData(
            Data.Builder()
                // Upload Now always samples first, independent of the configured periodic order.
                .putString(INPUT_STRATEGY, MANUAL_SYNC_STRATEGY.configValue)
                .build()
        )
        .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
        .build()

    enqueueGroupedUniqueWork(
        workManager = WorkManager.getInstance(context),
        uniqueWorkName = CHRONICLE_SYNC_IMMEDIATE_WORK_NAME,
        requests = listOf(request) + buildImmediateAuxiliaryUploadRequests(
            collectExpansionBeforeUpload = true,
        ),
    )
}

internal fun enqueueImmediateAuxiliaryUploads(
    workManager: WorkManager,
    collectExpansionBeforeUpload: Boolean = true,
) {
    enqueueGroupedUniqueWork(
        workManager = workManager,
        uniqueWorkName = AUXILIARY_UPLOAD_IMMEDIATE_WORK_NAME,
        requests = buildImmediateAuxiliaryUploadRequests(collectExpansionBeforeUpload),
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
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
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
) {
    require(requests.isNotEmpty()) { "At least one upload request is required" }
    workManager.beginUniqueWork(
        uniqueWorkName, IMMEDIATE_UPLOAD_EXISTING_WORK_POLICY, requests,
    ).enqueue()
}

internal fun coordinatedSyncConstraints(requiresBatteryNotLow: Boolean): Constraints =
    Constraints.Builder()
        .setRequiresBatteryNotLow(requiresBatteryNotLow)
        .build()
