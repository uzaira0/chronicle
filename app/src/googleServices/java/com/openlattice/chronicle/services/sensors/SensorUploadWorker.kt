package com.openlattice.chronicle.services.sensors

import android.content.Context
import android.util.Log
import androidx.work.*
import com.openlattice.chronicle.collection.sensors.SensorUploadMigration
import com.openlattice.chronicle.collection.state.ResearchPersistenceGate
import com.openlattice.chronicle.constants.TelemetryEvents
import com.openlattice.chronicle.preferences.SensorSettings
import com.openlattice.chronicle.services.upload.UPLOAD_NETWORK_CONSTRAINT
import com.openlattice.chronicle.services.upload.UploadQueueSingleFlight
import com.openlattice.chronicle.telemetry.LocalTelemetry
import com.openlattice.chronicle.storage.ChronicleDb
import com.openlattice.chronicle.storage.ServerMigrationHelper
import java.time.OffsetDateTime
import java.util.concurrent.TimeUnit

private val TAG = SensorUploadWorker::class.java.simpleName
private const val SENSOR_UPLOAD_INTERVAL_MIN = 15L
internal const val LEGACY_SENSOR_UPLOAD_WORK_NAME = "sensor_upload"

class SensorUploadWorker(context: Context, params: WorkerParameters) : Worker(context, params) {

    override fun doWork(): Result {
        LocalTelemetry.logEvent(TelemetryEvents.SENSOR_UPLOAD_START, null)

        return try {
            val result = ResearchPersistenceGate.runIfActive(applicationContext) {
                if (!UploadQueueSingleFlight.tryAcquire(LEGACY_SENSOR_UPLOAD_WORK_NAME)) {
                    Log.i(TAG, "Sensor upload deferred because the queue is already being drained")
                    Result.retry()
                } else try {
                    val chronicleDb = ChronicleDb.getInstance(applicationContext)
                    ServerMigrationHelper.migrateIfNeeded(applicationContext, chronicleDb)
                    val delegate = SensorUploadWorkerDelegate(applicationContext, chronicleDb)
                    // Phase 6C migration switch: both paths run the identical delegate logic.
                    val failures = if (SensorUploadMigration.USE_MODULE_MANAGER_SENSOR_UPLOAD_PATH) {
                        delegate.asModule().upload().serverFailureCount
                    } else {
                        delegate.execute()
                    }
                    if (failures == 0) {
                        LocalTelemetry.logEvent(TelemetryEvents.SENSOR_UPLOAD_SUCCESS, null)
                        SensorSettings(applicationContext)
                            .setLastSensorUpload(OffsetDateTime.now().toString())
                        Result.success()
                    } else {
                        LocalTelemetry.logEvent(TelemetryEvents.SENSOR_UPLOAD_FAILURE, null)
                        if (runAttemptCount > 5) Result.failure() else Result.retry()
                    }
                } finally {
                    UploadQueueSingleFlight.release(LEGACY_SENSOR_UPLOAD_WORK_NAME)
                }
            }
            if (result == null) {
                Log.i(TAG, "Sensor upload skipped without an active study enrollment")
            }
            result ?: Result.success()
        } catch (e: Exception) {
            Log.e(TAG, "Sensor upload failed", e)
            LocalTelemetry.recordException(e)
            LocalTelemetry.logEvent(TelemetryEvents.SENSOR_UPLOAD_FAILURE, null)
            Result.failure()
        }
    }
}

fun scheduleSensorUploadWork(context: Context) {
    val workRequest = PeriodicWorkRequestBuilder<SensorUploadWorker>(
        SENSOR_UPLOAD_INTERVAL_MIN, TimeUnit.MINUTES
    ).setConstraints(UPLOAD_NETWORK_CONSTRAINT)
     .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
     .build()

    WorkManager.getInstance(context).enqueueUniquePeriodicWork(
        LEGACY_SENSOR_UPLOAD_WORK_NAME,
        ExistingPeriodicWorkPolicy.UPDATE,
        workRequest
    )
}
