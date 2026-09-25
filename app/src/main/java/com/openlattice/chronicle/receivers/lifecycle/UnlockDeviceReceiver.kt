package com.openlattice.chronicle.receivers.lifecycle

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.openlattice.chronicle.R
import com.openlattice.chronicle.UserIdentificationActivity
import com.openlattice.chronicle.collection.state.ResearchPersistenceGate
import com.openlattice.chronicle.collection.core.ModuleResult
import com.openlattice.chronicle.collection.identification.TargetUserRouter
import com.openlattice.chronicle.services.notifications.IDENTIFY_USER_CHANNEL_ID
import com.openlattice.chronicle.services.notifications.DeviceUnlockMonitoringService
import com.openlattice.chronicle.services.notifications.IDENTIFY_USER_NOTIFICATION_TAG
import com.openlattice.chronicle.services.notifications.NOTIFICATION_DELETED_ACTION
import com.openlattice.chronicle.services.notifications.userIdentificationMayRun
import com.openlattice.chronicle.utils.Utils.getPendingIntentMutabilityFlag
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException

class UnlockDeviceReceiver : BroadcastReceiver() {

    private lateinit var appContext: Context

    companion object  {
        private val IO_EXECUTOR = Executors.newSingleThreadExecutor()

        fun getValidReceiverActions(context: Context): Set<String> {
            return setOf(Intent.ACTION_USER_PRESENT, Intent.ACTION_SCREEN_ON, context.getString(R.string.action_identify_after_reboot))
        }
    }

    override fun onReceive(context: Context, intent: Intent) {
        if (!getValidReceiverActions(context).contains(intent.action)) return
        val pendingResult = goAsync()
        try {
            IO_EXECUTOR.execute {
                try {
                    handleReceive(context.applicationContext, intent)
                } finally {
                    pendingResult.finish()
                }
            }
        } catch (error: RejectedExecutionException) {
            pendingResult.finish()
            Log.w(javaClass.name, "Identify-user notification task was rejected", error)
        }
    }

    private fun handleReceive(context: Context, intent: Intent) {
        appContext = context

        if (!userIdentificationMayRun(context)) {
            DeviceUnlockMonitoringService.stopService(context)
            return
        }
        var posted = false
        ResearchPersistenceGate.persistIfActive(context) {
            posted = postIdentifyNotification(context, intent)
        }
        // A posted prompt means the current user is unknown until someone answers it, so usage
        // from here on is "unassigned" instead of credited to whoever answered last. The original
        // app did this from a NotificationListener, which needs notification access; Chronicle
        // posts the prompt itself, so it resets here in every build. Outside persistIfActive:
        // TargetUserRouter takes the same barrier.
        if (posted) {
            val result = TargetUserRouter.setTargetUser(context, context.getString(R.string.user_unassigned))
            if (result !is ModuleResult.Ok) {
                Log.w(javaClass.name, "Target-user reset after identify prompt failed: ${result.label}")
            }
        }
    }

    /** Returns true only when the prompt was handed to the notification manager. */
    private fun postIdentifyNotification(context: Context, intent: Intent): Boolean {
        val action = intent.action
        if (!getValidReceiverActions(context).contains(action)) {
            return false
        }

        // create intent to start UserIdentificationActivity
        val userIdentificationIntent =
            Intent(context, UserIdentificationActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            }
        val pendingIntent =
            PendingIntent.getActivity(
                context,
                0,
                userIdentificationIntent,
                getPendingIntentMutabilityFlag(PendingIntent.FLAG_UPDATE_CURRENT)
            )

        // Standard template, not a custom RemoteViews layout: Android 12+ wraps custom layouts in
        // its own header and clips them to ~48dp, which hid the prompt text on newer devices.
        val message = context.getString(R.string.on_wake_notification_message)
        val notificationBuilder = NotificationCompat.Builder(context, IDENTIFY_USER_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_notification)
            .setColor(ContextCompat.getColor(context, R.color.colorPrimary))
            .setContentTitle(context.getString(R.string.on_wake_notification_title))
            .setContentText(message)
            .setStyle(NotificationCompat.BigTextStyle().bigText(message))
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .setShowWhen(true)
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setContentIntent(pendingIntent)
            .setDeleteIntent(createOnDismissedIntent())
            .setAutoCancel(true) // remove when user taps on notification

        // POST_NOTIFICATIONS (API 33+) is a runtime permission the user may have denied; NotificationManagerCompat.notify
        // then throws SecurityException. Handle it explicitly (same contract as CollectionLoopCoordinator.notifySafely):
        // the identify-user reminder is best-effort, so a denied permission is logged and swallowed, never crashes the receiver.
        try {
            NotificationManagerCompat.from(context)
                .notify(
                    IDENTIFY_USER_NOTIFICATION_TAG,
                    context.resources.getInteger(R.integer.identify_user_notification_id),
                    notificationBuilder.build(),
                )
        } catch (e: SecurityException) {
            Log.w(javaClass.name, "Identify-user notification suppressed (POST_NOTIFICATIONS not granted)", e)
            return false
        }
        return true
    }

    private fun createOnDismissedIntent(): PendingIntent {
        val intent = Intent(NOTIFICATION_DELETED_ACTION)
        val resources = appContext.resources
        val notificationId = resources.getInteger(R.integer.dismiss_target_user_notification_id)
        intent.putExtra(appContext.getString(R.string.notification_id), notificationId)

        return PendingIntent.getBroadcast(appContext, 0, intent, getPendingIntentMutabilityFlag(0))
    }
}
