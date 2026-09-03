package com.openlattice.chronicle.services.sensors

import android.content.Context
import android.util.Log
import androidx.work.*
import com.openlattice.chronicle.collection.sensors.SensorSettingsMigration
import com.openlattice.chronicle.collection.sensors.SensorSettingsRefreshDelegate
import com.openlattice.chronicle.collection.sensors.SensorSettingsRefreshOutcome
import com.openlattice.chronicle.preferences.SensorSettings
import com.openlattice.chronicle.services.sync.scheduleChronicleSyncWork
import com.openlattice.chronicle.storage.ChronicleDb
import java.util.UUID
import java.util.concurrent.TimeUnit

private val TAG = SensorSettingsRefreshWorker::class.java.simpleName
private const val SENSOR_SETTINGS_REFRESH_INTERVAL_HOURS = 6L

class SensorSettingsRefreshWorker(context: Context, params: WorkerParameters) : Worker(context, params) {

    override fun doWork(): Result {
        // Phase 6B migration switch: exactly one refresh path runs per execution.
        // Defaults to the legacy inline refresh (current behaviour) until module parity
        // tests pass — see SensorSettingsMigration.USE_MODULE_MANAGER_SENSOR_SETTINGS_PATH.
        return if (SensorSettingsMigration.USE_MODULE_MANAGER_SENSOR_SETTINGS_PATH) {
            doModuleRefresh()
        } else {
            doLegacyRefresh()
        }
    }

    /**
     * Per-sensor module path: reports modeled-sensor availability for the study-configured
     * per-sensor set via [SensorSettingsRefreshDelegate]. Per-sensor config and the
     * hardware-sensor service lifecycle are owned by `CollectionLoopCoordinator` (the
     * per-sensor `DataCollection` modules) — this worker never writes the sensor config or
     * starts/stops the service, which would clobber the coordinator's per-sensor settings
     * (per-sensor consent redesign, 2026-06-11).
     */
    private fun doModuleRefresh(): Result {
        val db = ChronicleDb.getInstance(applicationContext)
        val delegate = SensorSettingsRefreshDelegate(
            gateway = ChronicleSensorSettingsGateway(applicationContext, db),
            store = SensorSettingsPrefsStore(applicationContext),
        )
        return when (delegate.refresh().outcome) {
            SensorSettingsRefreshOutcome.SUCCESS -> Result.success()
            SensorSettingsRefreshOutcome.RETRY -> Result.retry()
        }
    }

    /** Legacy inline refresh — the regression baseline, unchanged from before Phase 6. */
    private fun doLegacyRefresh(): Result {
        try {
            val db = ChronicleDb.getInstance(applicationContext)
            val servers = listOfNotNull(db.uploadServerDao().getEnabledServer())
            if (servers.isEmpty()) {
                Log.i(TAG, "No enabled servers, skipping settings refresh")
                return Result.success()
            }

            val sensorSettings = SensorSettings(applicationContext)
            val currentSensors = sensorSettings.getConfiguredSensors()
            val wasEnabled = sensorSettings.isEnabled()

            // Fetch settings from the first enabled server (settings are device-wide,
            // not per-study, so any server's view is fine). Report availability to all.
            val primary = servers.first()
            val primaryStudyId = UUID.fromString(primary.studyId)
            val primaryApi = com.openlattice.chronicle.services.upload.UploadWorker.getChronicleStudyApi(
                primary.url,
                primary.mobileSigningSecretOverride
            )
            val fetched = primaryApi.getAndroidSensorSettings(primaryStudyId)
            val fetchedSensors = fetched.sensors

            val settingsChanged = currentSensors != fetchedSensors
                    || sensorSettings.getSamplingRateHz() != fetched.samplingRateHz
                    || sensorSettings.getDutyCycleActiveSeconds() != fetched.dutyCycleActiveSeconds
                    || sensorSettings.getDutyCyclePeriodSeconds() != fetched.dutyCyclePeriodSeconds

            if (settingsChanged) {
                Log.i(TAG, "Sensor settings changed, updating (sensors: $currentSensors -> $fetchedSensors)")
                sensorSettings.save(fetched)

                val nowEnabled = sensorSettings.isEnabled()
                when {
                    wasEnabled && nowEnabled -> {
                        HardwareSensorService.stopService(applicationContext)
                        HardwareSensorService.startService(applicationContext)
                        Log.i(TAG, "Restarted sensor service with updated settings")
                    }
                    !wasEnabled && nowEnabled -> {
                        HardwareSensorService.startService(applicationContext)
                        scheduleChronicleSyncWork(applicationContext)
                        Log.i(TAG, "Sensor collection newly enabled, started service")
                    }
                    wasEnabled && !nowEnabled -> {
                        HardwareSensorService.stopService(applicationContext)
                        Log.i(TAG, "Sensor collection disabled, stopped service")
                    }
                }
            } else {
                Log.i(TAG, "Sensor settings unchanged")
            }

            // Report availability to the active study server using this enrollment's device UUID.
            // On failure, persist the error onto the server row so it's visible in
            // the Server Edit screen — silent skip is unacceptable for HIPAA visibility.
            if (fetchedSensors.isNotEmpty()) {
                val serverDao = db.uploadServerDao()
                for (server in servers) {
                    val ok = SensorAvailabilityReporter.checkAndReport(
                        applicationContext,
                        UUID.fromString(server.studyId),
                        server.participantId,
                        server.sourceDeviceId,
                        server.apiKey,
                        fetchedSensors,
                        server.url,
                        server.mobileSigningSecretOverride
                    )
                    if (!ok) {
                        Log.w(TAG, "Sensor availability report failed for '${server.name}'")
                        serverDao.updateSensorUploadStatus(
                            server.id,
                            java.time.OffsetDateTime.now().toString(),
                            "availability report failed",
                            server.sensorConsecutiveFailures + 1,
                            server.lastUploadedSensorId
                        )
                    }
                }
            }

            return Result.success()
        } catch (e: Exception) {
            if (e.message?.contains("code 404") == true && e.message?.contains("AndroidSensor") == true) {
                val sensorSettings = SensorSettings(applicationContext)
                if (sensorSettings.isEnabled()) {
                    sensorSettings.clear()
                    HardwareSensorService.stopService(applicationContext)
                    Log.i(TAG, "AndroidSensor settings missing on server; disabled local sensor collection")
                } else {
                    Log.i(TAG, "AndroidSensor settings missing on server; sensor collection already disabled")
                }
                return Result.success()
            }
            Log.w(TAG, "Failed to refresh sensor settings", e)
            return Result.retry()
        }
    }
}

fun enqueueSensorSettingsRefresh(context: Context) {
    val workRequest = OneTimeWorkRequestBuilder<SensorSettingsRefreshWorker>()
        .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
        .build()

    WorkManager.getInstance(context).enqueueUniqueWork(
        "sensor_settings_refresh",
        ExistingWorkPolicy.REPLACE,
        workRequest
    )
}

fun scheduleSensorSettingsRefreshWork(context: Context) {
    val workRequest = PeriodicWorkRequestBuilder<SensorSettingsRefreshWorker>(
        SENSOR_SETTINGS_REFRESH_INTERVAL_HOURS, TimeUnit.HOURS
    ).setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
     .build()

    WorkManager.getInstance(context).enqueueUniquePeriodicWork(
        "sensor_settings_refresh_periodic",
        ExistingPeriodicWorkPolicy.KEEP,
        workRequest
    )
}
