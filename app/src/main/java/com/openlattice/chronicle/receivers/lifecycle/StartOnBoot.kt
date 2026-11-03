package com.openlattice.chronicle.receivers.lifecycle

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.Intent.ACTION_BOOT_COMPLETED
import android.content.Intent.ACTION_MY_PACKAGE_REPLACED
import android.content.Intent.ACTION_TIME_CHANGED
import android.util.Log
import com.openlattice.chronicle.EnrolledWork
import com.openlattice.chronicle.collection.state.CollectionLoopCoordinator
import com.openlattice.chronicle.collection.state.ResearchPersistenceGate
import com.openlattice.chronicle.BuildConfig
import com.openlattice.chronicle.collection.DistributionRestrictedRuntime
import com.openlattice.chronicle.data.ParticipationStatus
import com.openlattice.chronicle.preferences.EnrollmentSettings
import com.openlattice.chronicle.services.enrollment.enqueueEnrollmentStatusRefresh
import com.openlattice.chronicle.services.notifications.restoreReminderAlarmsAsync
import com.openlattice.chronicle.services.notifications.DeviceUnlockMonitoringService
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException

val TAG = StartOnBoot::class.java.simpleName

class StartOnBoot : BroadcastReceiver() {
    override fun onReceive(context: Context?, intent: Intent?) {
        // A manual clock change re-measures the server clock (ServerClock); a time zone change moves no instant.
        if (context != null && intent?.action == ACTION_TIME_CHANGED) {
            enqueueEnrollmentStatusRefresh(context.applicationContext)
            return
        }
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
                runBootTask(context.applicationContext) { pendingResult.finish() }
            }
        } catch (error: RejectedExecutionException) {
            pendingResult.finish()
            Log.e(javaClass.canonicalName, "Boot recovery executor rejected work", error)
        }
    }

    internal fun runBootTask(context: Context, finish: () -> Unit) {
        try {
            handleBoot(context)
        } catch (error: Exception) {
            Log.e(TAG, "Boot initialization unavailable; retrying locally", error)
            ResearchPersistenceGate.initializeAsync(context)
        } finally {
            finish()
        }
    }

    private fun handleBoot(context: Context) {
        ResearchPersistenceGate.initialize(context)
        val settings = EnrollmentSettings(context)
        if (settings.isEnrolledOrThrow()) {
            restoreReminderAlarmsAsync(context)
            EnrolledWork.reconcile(context)
            // A reboot resets the server clock's anchor: re-measure now, not at the next periodic poll.
            enqueueEnrollmentStatusRefresh(context)
            CollectionLoopCoordinator(context).updateSensorService()
        }
        if (BuildConfig.ALLOW_RESTRICTED_RESEARCH_PERMISSIONS &&
            settings.getParticipationStatus() == ParticipationStatus.ENROLLED) {
            DistributionRestrictedRuntime.drainDirectBootSamples(context)
        }
        // Boot may start a foreground service; a paused study keeps its monitor for a background resume.
        DeviceUnlockMonitoringService.reconcile(context, restartOnBoot = true)
    }

    private companion object {
        val IO_EXECUTOR = Executors.newSingleThreadExecutor()
    }
}
