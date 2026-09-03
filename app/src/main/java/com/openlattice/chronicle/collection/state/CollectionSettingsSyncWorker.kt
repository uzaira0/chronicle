package com.openlattice.chronicle.collection.state

import android.content.Context
import android.util.Log
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.Worker
import androidx.work.WorkerParameters
import java.util.concurrent.TimeUnit

/**
 * Drives [CollectionLoopCoordinator.sync] — fetches the server data-collection setting,
 * reconciles it, and dispatches side effects (collection loop closure design §5.5).
 *
 * Propagation is poll-based (no FCM in this deployment): a 1h periodic floor
 * ([schedulePeriodic]) plus an on-demand trigger ([enqueueNow]) that the upload worker
 * piggybacks, enrollment fires, and the dormant FCM handler points at. The legacy 6h
 * `SensorSettingsRefreshWorker` is intentionally left on its own schedule, untouched.
 */
class CollectionSettingsSyncWorker(
    context: Context,
    params: WorkerParameters,
) : Worker(context, params) {

    override fun doWork(): Result =
        try {
            if (CollectionLoopCoordinator(applicationContext).sync()) Result.success() else Result.retry()
        } catch (e: Exception) {
            Log.e(TAG, "Collection settings sync failed", e)
            Result.retry()
        }

    companion object {
        private const val TAG = "CollectionSettingsSyncWorker"
        const val SYNC_INTERVAL_HOURS = 1L
        private const val PERIODIC_NAME = "collection_settings_sync_periodic"
        private const val ONESHOT_NAME = "collection_settings_sync"

        private val NETWORK_CONSTRAINT = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)
            .build()

        /** Schedules the 1h periodic floor. KEEP so it is not reset on every call. */
        fun schedulePeriodic(context: Context) {
            val request = PeriodicWorkRequestBuilder<CollectionSettingsSyncWorker>(
                SYNC_INTERVAL_HOURS, TimeUnit.HOURS,
            ).setConstraints(NETWORK_CONSTRAINT)
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
                .build()
            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                PERIODIC_NAME, ExistingPeriodicWorkPolicy.KEEP, request,
            )
        }

        /** Fires an immediate sync (piggyback / enrollment / push). REPLACE coalesces bursts. */
        fun enqueueNow(context: Context) {
            val request = OneTimeWorkRequestBuilder<CollectionSettingsSyncWorker>()
                .setConstraints(NETWORK_CONSTRAINT)
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
                .build()
            WorkManager.getInstance(context).enqueueUniqueWork(
                ONESHOT_NAME, ExistingWorkPolicy.REPLACE, request,
            )
        }
    }
}
