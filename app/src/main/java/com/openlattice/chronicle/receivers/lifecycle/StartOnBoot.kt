package com.openlattice.chronicle.receivers.lifecycle

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.Intent.ACTION_BOOT_COMPLETED
import android.content.Intent.ACTION_MY_PACKAGE_REPLACED
import android.util.Log
import com.openlattice.chronicle.BuildConfig
import com.openlattice.chronicle.collection.DistributionRestrictedRuntime
import com.openlattice.chronicle.data.ParticipationStatus
import com.openlattice.chronicle.preferences.EnrollmentSettings
import com.openlattice.chronicle.preferences.SensorSettings
import com.openlattice.chronicle.services.enrollment.scheduleEnrollmentMonitoringWork
import com.openlattice.chronicle.services.notifications.DeviceUnlockMonitoringService
import com.openlattice.chronicle.services.notifications.userIdentificationMayRun
import com.openlattice.chronicle.services.notifications.scheduleNotificationsWorker
import com.openlattice.chronicle.services.sync.scheduleChronicleSyncWork
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException

val TAG = StartOnBoot::class.java.simpleName

class StartOnBoot : BroadcastReceiver() {
    override fun onReceive(context: Context?, intent: Intent?) {
        if (
            context == null ||
            intent == null ||
            (intent.action != ACTION_BOOT_COMPLETED && intent.action != ACTION_MY_PACKAGE_REPLACED)
        ) {
            Log.e(javaClass.canonicalName, "Unable to start Usage Service at Boot.")
            return
        }
        val pendingResult = goAsync()
        try {
            IO_EXECUTOR.execute {
                try {
                    handleBoot(context.applicationContext)
                } finally {
                    pendingResult.finish()
                }
            }
        } catch (error: RejectedExecutionException) {
            pendingResult.finish()
            Log.e(javaClass.canonicalName, "Boot recovery executor rejected work", error)
        }
    }

    private fun handleBoot(context: Context) {
        // If this process was started in direct-boot mode (locked-boot sensor
        // collection), the androidx.startup initializers were skipped — re-run them
        // now that the user has unlocked. Idempotent no-op in normal processes.
        if (BuildConfig.ALLOW_RESTRICTED_RESEARCH_PERMISSIONS) {
            DistributionRestrictedRuntime.reinitializeDirectBootProcess(context)
        }
        val settings = EnrollmentSettings(context)
        if (settings.isEnrolled()) {
            // Always refresh enrollment status at boot when IDs exist.
            // Other workers can decide whether to collect based on cached participation status.
            scheduleEnrollmentMonitoringWork(context)
            Log.i(TAG, "started enrollment monitoring worker at boot")
        }

        if (settings.getParticipationStatus() == ParticipationStatus.ENROLLED) {

            // BOOT_COMPLETED arrives at first unlock on a locked device: replay any
            // sensor samples the direct-boot runtime buffered pre-unlock into the
            // normal queue (no-op when the buffer is empty; covers a direct-boot
            // service that died before the unlock handover could drain it).
            if (BuildConfig.ALLOW_RESTRICTED_RESEARCH_PERMISSIONS) {
                DistributionRestrictedRuntime.drainDirectBootSamples(context)
            }

            // start workers
            scheduleNotificationsWorker(context)
            Log.i(TAG, "started notifications worker at boot")

            scheduleChronicleSyncWork(context)
            Log.i(TAG, "started sync worker strategy at boot")

            if (userIdentificationMayRun(context)) {
                DeviceUnlockMonitoringService.startAuthorizedService(context, restartOnBoot = true)
            } else {
                DeviceUnlockMonitoringService.stopService(context)
            }

            if (BuildConfig.ALLOW_RESTRICTED_RESEARCH_PERMISSIONS) {
                val sensorSettings = SensorSettings(context)
                if (sensorSettings.isEnabled()) {
                    DistributionRestrictedRuntime.startHardwareSensors(context)
                    Log.i(TAG, "started sensor service at boot")
                }

                // Refresh research sensor settings from backend in background.
                DistributionRestrictedRuntime.enqueueSensorSettingsRefresh(context)
                DistributionRestrictedRuntime.scheduleSensorSettingsRefresh(context)
                Log.i(TAG, "enqueued sensor settings refresh at boot")
            } else {
                SensorSettings(context).clear()
            }
        } else {
            DeviceUnlockMonitoringService.stopService(context)
        }
    }

    private companion object {
        val IO_EXECUTOR = Executors.newSingleThreadExecutor()
    }
}
