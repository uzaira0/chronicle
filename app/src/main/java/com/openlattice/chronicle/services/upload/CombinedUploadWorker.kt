package com.openlattice.chronicle.services.upload

import android.content.Context
import android.util.Log
import androidx.work.*
import com.openlattice.chronicle.collection.DistributionRestrictedRuntime
import com.openlattice.chronicle.collection.battery.BatteryUploadWorkerDelegate
import com.openlattice.chronicle.collection.battery.collectBatterySample
import com.openlattice.chronicle.collection.core.CollectionLog
import com.openlattice.chronicle.collection.state.ResearchPersistenceGate
import com.openlattice.chronicle.collection.upload.COMBINED_UPLOAD_MAX_ATTEMPTS
import com.openlattice.chronicle.collection.upload.CombinedUploadOutcome
import com.openlattice.chronicle.collection.upload.UPLOAD_DELEGATE_THREW
import com.openlattice.chronicle.collection.upload.UploadTelemetryMigration
import com.openlattice.chronicle.collection.upload.runCombinedUploadCore
import com.openlattice.chronicle.constants.TelemetryEvents
import com.openlattice.chronicle.preferences.SensorSettings
import com.openlattice.chronicle.storage.ChronicleDb
import com.openlattice.chronicle.storage.ServerMigrationHelper
import com.openlattice.chronicle.telemetry.LocalTelemetry
import java.time.LocalDate
import java.time.OffsetDateTime
import java.util.concurrent.TimeUnit

private val COMBINED_UPLOAD_WORKER_TAG = CombinedUploadWorker::class.java.simpleName
private const val COMBINED_UPLOAD_INTERVAL_MIN = 15L
// Owned by :collection-base CollectionConstants (referenced by :collection-upload);
// re-exported here so existing import sites stay unchanged.
const val COMBINED_UPLOAD_WORK_NAME = com.openlattice.chronicle.constants.COMBINED_UPLOAD_WORK_NAME
const val COMBINED_UPLOAD_IMMEDIATE_WORK_NAME =
    com.openlattice.chronicle.constants.COMBINED_UPLOAD_IMMEDIATE_WORK_NAME
const val LEGACY_USAGE_UPLOAD_WORK_NAME = "upload"
const val LEGACY_SENSOR_UPLOAD_WORK_NAME = "sensor_upload"

/**
 * Shared network constraint for all upload work requests.
 */
val UPLOAD_NETWORK_CONSTRAINT: Constraints = Constraints.Builder()
    .setRequiredNetworkType(NetworkType.CONNECTED)
    .build()

/**
 * Single worker that handles both usage data and sensor data uploads.
 * Reduces device wake-ups by coalescing both upload paths into one periodic task.
 *
 * Delegates to [UploadWorkerDelegate] for usage data and
 * the distribution-owned sensor uploader for restricted research data.
 */
class CombinedUploadWorker(context: Context, params: WorkerParameters) : Worker(context, params) {

    override fun doWork(): Result {
        return runCombinedUpload(applicationContext, runAttemptCount)
    }
}

fun runCombinedUpload(context: Context, runAttemptCount: Int): ListenableWorker.Result {
    val result = ResearchPersistenceGate.runIfActive(context) {
        if (!UploadQueueSingleFlight.tryAcquire(COMBINED_UPLOAD_WORK_NAME)) {
            Log.i(COMBINED_UPLOAD_WORKER_TAG, "Combined upload deferred because the queue is already being drained")
            ListenableWorker.Result.retry()
        } else {
            try {
                runCombinedUploadOwned(context, runAttemptCount)
            } finally {
                UploadQueueSingleFlight.release(COMBINED_UPLOAD_WORK_NAME)
            }
        }
    }
    if (result == null) {
        Log.i(COMBINED_UPLOAD_WORKER_TAG, "Combined upload skipped without an active study enrollment")
    }
    return result ?: ListenableWorker.Result.success()
}

private fun runCombinedUploadOwned(context: Context, runAttemptCount: Int): ListenableWorker.Result {
    val chronicleDb = ChronicleDb.getInstance(context)
    ServerMigrationHelper.migrateIfNeeded(context, chronicleDb)

    // ----- Usage upload FIRST. Returns the per-server failure count; UPLOAD_DELEGATE_THREW
    //       (-1) when the delegate threw before returning. Behaviour unchanged from before
    //       the Phase 8B extraction — this is the exact legacy step body.
    val runUsageUpload: () -> Int = {
        try {
            LocalTelemetry.logEvent(TelemetryEvents.UPLOAD_START, null)
            UploadWorkerDelegate(context, chronicleDb).execute()
        } catch (e: Exception) {
            Log.e(COMBINED_UPLOAD_WORKER_TAG, "Usage upload failed", e)
            LocalTelemetry.recordException(e)
            LocalTelemetry.logEvent(TelemetryEvents.UPLOAD_FAILURE, null)
            UPLOAD_DELEGATE_THREW
        }
    }

    // ----- Sensor upload SECOND. Same contract as the usage step.
    val runSensorUpload: () -> Int = {
        try {
            LocalTelemetry.logEvent(TelemetryEvents.SENSOR_UPLOAD_START, null)
            val sensorFailures = DistributionRestrictedRuntime.uploadSensors(context, chronicleDb)
            if (sensorFailures == 0) {
                LocalTelemetry.logEvent(TelemetryEvents.SENSOR_UPLOAD_SUCCESS, null)
                SensorSettings(context).setLastSensorUpload(OffsetDateTime.now().toString())
            } else {
                LocalTelemetry.logEvent(TelemetryEvents.SENSOR_UPLOAD_FAILURE, null)
            }
            sensorFailures
        } catch (e: Exception) {
            Log.e(COMBINED_UPLOAD_WORKER_TAG, "Sensor upload failed", e)
            LocalTelemetry.recordException(e)
            LocalTelemetry.logEvent(TelemetryEvents.SENSOR_UPLOAD_FAILURE, null)
            UPLOAD_DELEGATE_THREW
        }
    }

    val runBatteryUpload: () -> Int = {
        try {
            collectBatterySample(context)
            BatteryUploadWorkerDelegate(context, chronicleDb).execute()
        } catch (e: Exception) {
            Log.e(COMBINED_UPLOAD_WORKER_TAG, "Battery upload failed", e)
            LocalTelemetry.recordException(e)
            UPLOAD_DELEGATE_THREW
        }
    }

    // ----- Cleanup old stats (keep 30 days). Best-effort; a failure never changes the result.
    val cleanupStats: () -> Unit = {
        val cutoff = LocalDate.now().minusDays(30).toString()
        chronicleDb.uploadStatsDao().deleteOlderThan(cutoff)
    }

    // Phase 8B migration switch: both paths produce the IDENTICAL ListenableWorker.Result
    // for identical delegate outcomes. The orchestrator path is the same decision logic
    // extracted into the pure, unit-testable `runCombinedUploadCore`. Defaults to the
    // legacy inline decision (the regression baseline).
    val primaryResult = if (UploadTelemetryMigration.USE_COMBINED_UPLOAD_ORCHESTRATOR) {
        val usageSensorResult = runCombinedUploadCore(
            runAttemptCount = runAttemptCount,
            runUsageUpload = runUsageUpload,
            runSensorUpload = runSensorUpload,
            cleanupStats = cleanupStats,
            log = CollectionLog.LOGCAT,
        ).toWorkerResult()
        val batteryFailures = runBatteryUpload()
        mergeUploadWorkerResult(runAttemptCount, usageSensorResult, batteryFailures)
    } else {
        legacyRunCombinedUpload(runAttemptCount, runUsageUpload, runSensorUpload, runBatteryUpload, cleanupStats)
    }
    val diagnosticFailures = UploadDiagnosticsUploader(context, chronicleDb).execute()
    return mergeDiagnosticUploadResult(runAttemptCount, primaryResult, diagnosticFailures)
}

internal fun mergeDiagnosticUploadResult(
    runAttemptCount: Int,
    primaryResult: ListenableWorker.Result,
    diagnosticFailures: Int,
    logPending: () -> Unit = {
        Log.w(COMBINED_UPLOAD_WORKER_TAG, "Upload diagnostics remain pending for the next scheduled run")
    },
): ListenableWorker.Result {
    if (primaryResult == ListenableWorker.Result.failure() || diagnosticFailures == 0) {
        return primaryResult
    }
    return if (runAttemptCount > COMBINED_UPLOAD_MAX_ATTEMPTS) {
        // Keep the bounded diagnostic queue for the next periodic run. A terminal failure here
        // would stop unique periodic work permanently, preventing a later-recovered server from
        // receiving the historical issue that explains the outage.
        logPending()
        primaryResult
    } else {
        ListenableWorker.Result.retry()
    }
}

/**
 * Maps the pure [CombinedUploadOutcome] decision to a `ListenableWorker.Result`.
 *
 * The one place `ListenableWorker.Result` is constructed for the orchestrator path —
 * kept trivial and exhaustive so the worker never reports `success()` for a
 * non-[CombinedUploadOutcome.SUCCESS] outcome.
 */
private fun CombinedUploadOutcome.toWorkerResult(): ListenableWorker.Result = when (this) {
    CombinedUploadOutcome.SUCCESS -> ListenableWorker.Result.success()
    CombinedUploadOutcome.RETRY -> ListenableWorker.Result.retry()
    CombinedUploadOutcome.FAILURE -> ListenableWorker.Result.failure()
}

/**
 * The legacy inline combined-upload decision (the Phase 8B regression baseline).
 *
 * Runs usage first, sensor second, then best-effort stats cleanup, and returns
 * `success` only when both steps cleanly succeeded — exactly as the worker did before
 * the Phase 8B [runCombinedUploadCore] extraction. Kept verbatim so the migration switch
 * has a true baseline to flip away from.
 */
private fun legacyRunCombinedUpload(
    runAttemptCount: Int,
    runUsageUpload: () -> Int,
    runSensorUpload: () -> Int,
    runBatteryUpload: () -> Int,
    cleanupStats: () -> Unit,
): ListenableWorker.Result {
    // Usage upload FIRST, sensor upload SECOND.
    val usageFailures = runUsageUpload()
    val sensorFailures = runSensorUpload()
    val batteryFailures = runBatteryUpload()

    // Cleanup old stats (keep 30 days) — best-effort, never changes the result.
    try {
        cleanupStats()
    } catch (e: Exception) {
        Log.w(COMBINED_UPLOAD_WORKER_TAG, "Failed to cleanup old stats", e)
    }

    // 0 means the delegate ran cleanly; >0 means at least one server failed;
    // -1 means the delegate threw before returning (treat as failure).
    val usageOk = usageFailures == 0
    val sensorOk = sensorFailures == 0
    val batteryOk = batteryFailures == 0
    Log.i(
        COMBINED_UPLOAD_WORKER_TAG,
        "Combined upload complete: usageFailures=$usageFailures, sensorFailures=$sensorFailures, batteryFailures=$batteryFailures",
    )
    return if (usageOk && sensorOk && batteryOk) {
        ListenableWorker.Result.success()
    } else if (runAttemptCount > COMBINED_UPLOAD_MAX_ATTEMPTS) {
        // Prevent infinite retries after repeated failures
        Log.e(COMBINED_UPLOAD_WORKER_TAG, "Combined upload failed after $runAttemptCount attempts, giving up")
        ListenableWorker.Result.failure()
    } else {
        // Partial or total failure: retry so the failed path gets another attempt
        ListenableWorker.Result.retry()
    }
}

private fun mergeUploadWorkerResult(
    runAttemptCount: Int,
    usageSensorResult: ListenableWorker.Result,
    batteryFailures: Int,
): ListenableWorker.Result {
    // Compare against the factory result rather than the library-restricted Result.Failure subtype
    // (RestrictedApi). usageSensorResult originates from Result.failure()/retry()/success() with no
    // output data, whose equals() is value-based, so this is an exact, supported substitute.
    if (usageSensorResult == ListenableWorker.Result.failure()) return usageSensorResult
    if (batteryFailures == 0) return usageSensorResult
    return if (runAttemptCount > COMBINED_UPLOAD_MAX_ATTEMPTS) {
        Log.e(COMBINED_UPLOAD_WORKER_TAG, "Battery upload failed after $runAttemptCount attempts, giving up")
        ListenableWorker.Result.failure()
    } else {
        ListenableWorker.Result.retry()
    }
}

fun scheduleCombinedUploadWork(context: Context) {
    val wm = WorkManager.getInstance(context)

    // Cancel old separate workers to prevent duplicate uploads
    wm.cancelUniqueWork(LEGACY_USAGE_UPLOAD_WORK_NAME)
    wm.cancelUniqueWork(LEGACY_SENSOR_UPLOAD_WORK_NAME)

    val workRequest = PeriodicWorkRequestBuilder<CombinedUploadWorker>(
        COMBINED_UPLOAD_INTERVAL_MIN, TimeUnit.MINUTES
    ).setConstraints(UPLOAD_NETWORK_CONSTRAINT)
     .build()

    wm.enqueueUniquePeriodicWork(
        COMBINED_UPLOAD_WORK_NAME,
        ExistingPeriodicWorkPolicy.UPDATE,
        workRequest
    )
}
