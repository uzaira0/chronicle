package com.openlattice.chronicle.services.upload

import com.openlattice.chronicle.constants.COMBINED_UPLOAD_IMMEDIATE_WORK_NAME as CONTRACT_COMBINED_UPLOAD_IMMEDIATE_WORK_NAME
import com.openlattice.chronicle.constants.COMBINED_UPLOAD_WORK_NAME as CONTRACT_COMBINED_UPLOAD_WORK_NAME
import android.content.Context
import android.util.Log
import androidx.work.Constraints
import androidx.work.ListenableWorker
import androidx.work.NetworkType
import androidx.work.Worker
import androidx.work.WorkerParameters
import com.openlattice.chronicle.collection.DistributionRestrictedRuntime
import com.openlattice.chronicle.collection.battery.BatteryUploadWorkerDelegate
import com.openlattice.chronicle.collection.battery.BATTERY_UPLOAD_WORK_NAME
import com.openlattice.chronicle.collection.battery.collectBatterySampleIfDue
import com.openlattice.chronicle.collection.upload.COMBINED_UPLOAD_MAX_ATTEMPTS
import com.openlattice.chronicle.collection.upload.CombinedUploadOutcome
import com.openlattice.chronicle.collection.upload.UPLOAD_DELEGATE_THREW
import com.openlattice.chronicle.collection.upload.runCombinedUploadCore
import com.openlattice.chronicle.constants.TelemetryEvents
import com.openlattice.chronicle.preferences.SensorSettings
import com.openlattice.chronicle.storage.ChronicleDb
import com.openlattice.chronicle.telemetry.LocalTelemetry
import java.time.OffsetDateTime

private val COMBINED_UPLOAD_WORKER_TAG = CombinedUploadWorker::class.java.simpleName
// Owned by :collection-base CollectionConstants (referenced by :collection-upload);
// re-exported here so existing import sites stay unchanged.
const val COMBINED_UPLOAD_WORK_NAME = CONTRACT_COMBINED_UPLOAD_WORK_NAME
const val COMBINED_UPLOAD_IMMEDIATE_WORK_NAME =
    CONTRACT_COMBINED_UPLOAD_IMMEDIATE_WORK_NAME

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
    override fun doWork(): Result = retryOnStorageFailure { runCombinedUpload(applicationContext, runAttemptCount, ::isStopped) }
}

fun runCombinedUpload(context: Context, runAttemptCount: Int, stopped: () -> Boolean): ListenableWorker.Result =
    runEnrolledUpload(context, COMBINED_UPLOAD_WORKER_TAG) {
        if (!UploadQueueSingleFlight.tryAcquire(COMBINED_UPLOAD_WORK_NAME)) {
            Log.i(COMBINED_UPLOAD_WORKER_TAG, "Combined upload deferred because the queue is already being drained")
            ListenableWorker.Result.retry()
        } else {
            try {
                runCombinedUploadOwned(context, runAttemptCount, stopped)
            } finally {
                UploadQueueSingleFlight.release(COMBINED_UPLOAD_WORK_NAME)
            }
        }
    }

private fun runCombinedUploadOwned(context: Context, runAttemptCount: Int, stopped: () -> Boolean): ListenableWorker.Result {
    val db = ChronicleDb.getInstance(context)
    val primary = runCombinedUploadCore(
        runAttemptCount = runAttemptCount,
        runUsageUpload = { runUsageUpload(context, db, stopped) },
        runSensorUpload = { runSensorUpload(context, db, stopped) },
    ).toWorkerResult()
    val withBattery = mergeUploadWorkerResult(runAttemptCount, primary, runBatteryUpload(context, db, stopped))
    return mergeDiagnosticUploadResult(runAttemptCount, withBattery, UploadDiagnosticsUploader(context, db).execute())
}

// Delegate, telemetry and storage failures retain rows for retry while sibling streams continue.
@Suppress("TooGenericExceptionCaught")
private fun runUsageUpload(context: Context, db: ChronicleDb, stopped: () -> Boolean): Int = try {
    LocalTelemetry.logEvent(TelemetryEvents.UPLOAD_START, null)
    UploadWorkerDelegate(context, db).execute(stopped)
} catch (error: Exception) {
    Log.e(COMBINED_UPLOAD_WORKER_TAG, "Usage upload failed: ${persistedUploadFailureCode(error)}")
    LocalTelemetry.logEvent(TelemetryEvents.UPLOAD_FAILURE, null)
    UPLOAD_DELEGATE_THREW
}

// Restricted delegates have no closed exception contract; retain the queue and continue siblings.
@Suppress("TooGenericExceptionCaught")
private fun runSensorUpload(context: Context, db: ChronicleDb, stopped: () -> Boolean): Int = try {
    LocalTelemetry.logEvent(TelemetryEvents.SENSOR_UPLOAD_START, null)
    val failures = DistributionRestrictedRuntime.uploadSensors(context, db, stopped)
    if (failures == 0) {
        LocalTelemetry.logEvent(TelemetryEvents.SENSOR_UPLOAD_SUCCESS, null)
        SensorSettings(context).setLastSensorUpload(OffsetDateTime.now().toString())
    } else {
        LocalTelemetry.logEvent(TelemetryEvents.SENSOR_UPLOAD_FAILURE, null)
    }
    failures
} catch (error: Exception) {
    Log.e(COMBINED_UPLOAD_WORKER_TAG, "Sensor upload failed: ${persistedUploadFailureCode(error)}")
    LocalTelemetry.logEvent(TelemetryEvents.SENSOR_UPLOAD_FAILURE, null)
    UPLOAD_DELEGATE_THREW
}

// Collection, transport and accounting failures must release ownership and request retry.
@Suppress("TooGenericExceptionCaught")
private fun runBatteryUpload(context: Context, db: ChronicleDb, stopped: () -> Boolean): Int {
    if (!UploadQueueSingleFlight.tryAcquire(BATTERY_UPLOAD_WORK_NAME)) return 0
    return try {
        collectBatterySampleIfDue(context)
        BatteryUploadWorkerDelegate(context, db, stopped = stopped).execute()
    } catch (error: Exception) {
        Log.e(COMBINED_UPLOAD_WORKER_TAG, "Battery upload failed: ${persistedUploadFailureCode(error)}")
        UPLOAD_DELEGATE_THREW
    } finally {
        UploadQueueSingleFlight.release(BATTERY_UPLOAD_WORK_NAME)
    }
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
