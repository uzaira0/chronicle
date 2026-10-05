package com.openlattice.chronicle.collection.battery

import android.content.Context
import android.util.Log
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequest
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.Worker
import androidx.work.WorkerParameters
import com.openlattice.chronicle.collection.CollectionModuleId
import com.openlattice.chronicle.collection.core.ModuleResult
import com.openlattice.chronicle.collection.device.ExpansionPullSchedule
import java.util.concurrent.TimeUnit

private val TAG = BatteryCollectionWorker::class.java.simpleName

/** Unique WorkManager name for the periodic battery-telemetry collection work. */
const val BATTERY_WORK_NAME = "battery_telemetry"

/**
 * Battery-telemetry collection cadence. 15 minutes is both the WorkManager minimum for
 * periodic work and the default `CollectionModuleSetting.collectionCadence` (900s).
 */
private const val BATTERY_COLLECTION_INTERVAL_MIN = 15L

/**
 * Periodic [Worker] that drives `battery_telemetry` collection — the runtime trigger
 * that turns the otherwise-dormant `:collection-battery` module on (see
 * `docs/SENSING-EXPANSION-DESIGN.md` §5 / §12).
 *
 * Each run takes exactly one battery sample through the shared, app-scoped
 * [BatteryTelemetryCollectionModule] (via [BatteryTelemetryModuleHolder]). The module
 * itself enforces the enrollment check and never throws — see
 * [BatteryTelemetryCollectionModule.sample]. This worker only branches on
 * [BatteryCollectionMigration.USE_MODULE_MANAGER_BATTERY_PATH] and maps the
 * [ModuleResult] to a WorkManager [Result]:
 *  - [ModuleResult.Ok] / [ModuleResult.Skipped] → [Result.success];
 *  - [ModuleResult.Retry] → [Result.retry] (battery state momentarily unavailable);
 *  - [ModuleResult.Failed] → [Result.failure].
 *
 */
class BatteryCollectionWorker(context: Context, workerParameters: WorkerParameters) :
    Worker(context, workerParameters) {

    override fun doWork(): Result {
        // Not yet due ⇒ no-op success.
        return when (collectBatterySampleIfDue(applicationContext)) {
            null, is ModuleResult.Ok, is ModuleResult.Skipped -> Result.success()
            is ModuleResult.Retry -> Result.retry()
            is ModuleResult.Failed -> Result.failure()
        }
    }
}

/**
 * Takes one battery sample when the study's battery_telemetry interval has elapsed; null when
 * not due. The periodic worker and every sync share this gate, so neither path oversamples.
 */
fun collectBatterySampleIfDue(
    context: Context,
    sample: () -> ModuleResult = { collectBatterySample(context) },
): ModuleResult? {
    val schedule = ExpansionPullSchedule(context.applicationContext)
    val claimedAt = schedule.claimIfDue(CollectionModuleId.BATTERY_TELEMETRY) ?: return null
    var result: ModuleResult? = null
    try {
        return sample().also { result = it }
    } finally {
        schedule.completeClaim(CollectionModuleId.BATTERY_TELEMETRY, claimedAt, result is ModuleResult.Ok)
    }
}

/** Takes one battery sample through the module path, without the interval gate. */
fun collectBatterySample(context: Context): ModuleResult {
    if (!BatteryCollectionMigration.USE_MODULE_MANAGER_BATTERY_PATH) {
        Log.i(TAG, "Battery collection disabled by USE_MODULE_MANAGER_BATTERY_PATH; skipping")
        return ModuleResult.Skipped("battery collection disabled")
    }
    return try {
        val result = BatteryTelemetryModuleHolder.get(context.applicationContext).sample()
        if (result is ModuleResult.Failed) {
            Log.w(TAG, "Battery sample failed: ${result.redactedMessage}")
        }
        result
    } catch (e: Exception) {
        // sample() does not throw by contract; this is defence-in-depth so callers can
        // never crash the WorkManager dispatcher or combined upload pipeline.
        Log.w(TAG, "Battery collection threw unexpectedly", e)
        ModuleResult.Failed(e)
    }
}

/**
 * Schedules the periodic [BatteryCollectionWorker]. Idempotent — [ExistingPeriodicWorkPolicy.UPDATE]
 * keeps a single unique periodic work item, so repeated calls (e.g. on every sync
 * (re)configuration) do not stack workers.
 */
fun scheduleBatteryCollectionWork(context: Context) {
    val workRequest: PeriodicWorkRequest =
        PeriodicWorkRequestBuilder<BatteryCollectionWorker>(
            BATTERY_COLLECTION_INTERVAL_MIN,
            TimeUnit.MINUTES,
        ).build()

    WorkManager.getInstance(context).enqueueUniquePeriodicWork(
        BATTERY_WORK_NAME,
        ExistingPeriodicWorkPolicy.UPDATE,
        workRequest,
    )
}
