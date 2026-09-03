package com.openlattice.chronicle.collection.device

import android.content.Context
import android.util.Log
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.Worker
import androidx.work.WorkerParameters
import com.openlattice.chronicle.collection.CollectionModuleId
import com.openlattice.chronicle.collection.DistributionCollectionContributions
import com.openlattice.chronicle.collection.core.ModuleResult
import java.util.concurrent.TimeUnit

private val TAG = ExpansionCollectionWorker::class.java.simpleName

const val EXPANSION_COLLECTION_WORK_NAME = "expansion_modules_collection"
private const val EXPANSION_COLLECTION_INTERVAL_MIN = 15L

/**
 * Periodic [Worker] driving the sensing-expansion collection modules. Each run:
 *  - takes one pull sample from connectivity_state and device_settings, plus any modules contributed
 *    by the non-Play research distribution (each module self-enforces enrollment + per-module
 *    consent and never throws);
 *  - ensures Play Services registration for the push modules sleep / activity_recognition matches
 *    current consent (idempotent — registers consented, removes declined).
 *
 * Always reports [Result.success]: a module that is gated off skips, and the modules convert any
 * failure into a logged [ModuleResult] rather than throwing, so this worker never crashes the
 * WorkManager dispatcher.
 */
class ExpansionCollectionWorker(context: Context, workerParameters: WorkerParameters) :
    Worker(context, workerParameters) {

    override fun doWork(): Result {
        // Periodic path: honor each module's per-module collection interval via a last-run gate.
        collectExpansionSamples(applicationContext, ExpansionPullSchedule(applicationContext))
        return Result.success()
    }
}

/**
 * Takes one pull sample from each pull module and refreshes push-module registration. Shared with
 * immediate/combined sync so "upload now" reflects fresh state instead of waiting for the periodic
 * window. Never throws.
 *
 * When [schedule] is non-null (the periodic worker path) each module's per-module collection
 * interval is enforced: a module is sampled only when due, and a successful sample resets its
 * interval clock. When [schedule] is null (immediate "upload now") every module samples.
 */
fun collectExpansionSamples(context: Context, schedule: ExpansionPullSchedule? = null) {
    val appContext = context.applicationContext
    val now = System.currentTimeMillis()
    pullExpansionModule(CollectionModuleId.CONNECTIVITY_STATE, schedule, now) { ConnectivityStateModuleHolder.get(appContext).sample() }
    pullExpansionModule(CollectionModuleId.DEVICE_SETTINGS, schedule, now) { DeviceSettingsModuleHolder.get(appContext).sample() }
    DistributionCollectionContributions.collectAdditionalSamples(appContext, schedule, now)
}

internal inline fun pullExpansionModule(
    moduleId: CollectionModuleId,
    schedule: ExpansionPullSchedule?,
    nowMs: Long,
    block: () -> ModuleResult,
) {
    if (schedule != null && !schedule.isDue(moduleId, nowMs)) {
        Log.d(TAG, "${moduleId.id} not due yet (interval ${schedule.intervalSeconds(moduleId)}s); skipping")
        return
    }
    try {
        val result = block()
        when (result) {
            is ModuleResult.Failed -> Log.w(TAG, "${moduleId.id} sample failed: ${result.redactedMessage}")
            // Only a successful sample resets the interval clock — a gated-off / unavailable module
            // stays due so it collects promptly the moment it becomes collectable.
            is ModuleResult.Ok -> schedule?.markRan(moduleId, nowMs)
            else -> Unit
        }
    } catch (e: Exception) {
        // sample() does not throw by contract; defence-in-depth so one module can't crash the run.
        Log.w(TAG, "${moduleId.id} collection threw unexpectedly", e)
    }
}

/** Schedules the periodic [ExpansionCollectionWorker]. Idempotent via UPDATE. */
fun scheduleExpansionCollectionWork(context: Context) {
    val workRequest = PeriodicWorkRequestBuilder<ExpansionCollectionWorker>(
        EXPANSION_COLLECTION_INTERVAL_MIN,
        TimeUnit.MINUTES,
    ).build()

    WorkManager.getInstance(context).enqueueUniquePeriodicWork(
        EXPANSION_COLLECTION_WORK_NAME,
        ExistingPeriodicWorkPolicy.UPDATE,
        workRequest,
    )
}
