package com.openlattice.chronicle.services.sensors

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.Worker
import androidx.work.WorkerParameters
import com.openlattice.chronicle.collection.sensors.SensorSettingsRefreshDelegate
import com.openlattice.chronicle.collection.sensors.SensorSettingsRefreshOutcome
import com.openlattice.chronicle.storage.ChronicleDb
import java.util.concurrent.TimeUnit

private const val SENSOR_SETTINGS_REFRESH_INTERVAL_HOURS = 6L
internal const val SENSOR_SETTINGS_REFRESH_WORK_NAME = "sensor_settings_refresh"
internal const val SENSOR_SETTINGS_REFRESH_PERIODIC_WORK_NAME = "sensor_settings_refresh_periodic"

class SensorSettingsRefreshWorker(context: Context, params: WorkerParameters) : Worker(context, params) {

    /**
     * Reports which modeled sensors the device has (every `AndroidSensorType`) via
     * [SensorSettingsRefreshDelegate]. Per-sensor config and the
     * hardware-sensor service lifecycle are owned by `CollectionLoopCoordinator` (the
     * per-sensor `DataCollection` modules) — this worker never writes the sensor config or
     * starts/stops the service, which would clobber the coordinator's per-sensor settings.
     */
    override fun doWork(): Result {
        val db = ChronicleDb.getInstance(applicationContext)
        val delegate = SensorSettingsRefreshDelegate(ChronicleSensorSettingsGateway(applicationContext, db))
        return when (delegate.refresh().outcome) {
            SensorSettingsRefreshOutcome.SUCCESS -> Result.success()
            SensorSettingsRefreshOutcome.RETRY -> Result.retry()
        }
    }


}

fun enqueueSensorSettingsRefresh(context: Context, policy: ExistingWorkPolicy) {
    val workRequest = OneTimeWorkRequestBuilder<SensorSettingsRefreshWorker>()
        .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
        .build()

    WorkManager.getInstance(context).enqueueUniqueWork(
        SENSOR_SETTINGS_REFRESH_WORK_NAME,
        policy,
        workRequest
    )
}

fun scheduleSensorSettingsRefreshWork(context: Context) {
    val workRequest = PeriodicWorkRequestBuilder<SensorSettingsRefreshWorker>(
        SENSOR_SETTINGS_REFRESH_INTERVAL_HOURS, TimeUnit.HOURS
    ).setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
     .build()

    WorkManager.getInstance(context).enqueueUniquePeriodicWork(
        SENSOR_SETTINGS_REFRESH_PERIODIC_WORK_NAME,
        ExistingPeriodicWorkPolicy.KEEP,
        workRequest
    )
}
