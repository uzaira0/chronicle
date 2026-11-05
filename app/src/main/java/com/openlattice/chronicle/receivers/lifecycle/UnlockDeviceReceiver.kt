package com.openlattice.chronicle.receivers.lifecycle

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.openlattice.chronicle.R
import com.openlattice.chronicle.UserIdentificationActivity
import com.openlattice.chronicle.collection.state.ResearchPersistenceGate
import com.openlattice.chronicle.services.notifications.IDENTIFY_USER_CHANNEL_ID
import com.openlattice.chronicle.services.notifications.DeviceUnlockMonitoringService
import com.openlattice.chronicle.services.notifications.IDENTIFY_USER_NOTIFICATION_TAG
import com.openlattice.chronicle.services.notifications.NOTIFICATION_DELETED_ACTION
import com.openlattice.chronicle.services.notifications.postNotificationIfPermitted
import com.openlattice.chronicle.services.notifications.userIdentificationMayRun
import com.openlattice.chronicle.services.notifications.userIdentificationMonitorRetained
import com.openlattice.chronicle.utils.Utils.getPendingIntentMutabilityFlag
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException

class UnlockDeviceReceiver : BroadcastReceiver() {

    private lateinit var appContext: Context

    companion object  {
        private val IO_EXECUTOR = Executors.newSingleThreadExecutor()

        /** Unlock (and the after-reboot prompt) only: posting at screen-on put a first copy behind the lock screen. */
        fun getValidReceiverActions(context: Context): Set<String> =
            setOf(Intent.ACTION_USER_PRESENT, context.getString(R.string.action_identify_after_reboot))
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
        DeviceUnlockMonitoringService.resetObservedUser(context)

        if (!userIdentificationMayRun(context)) {
            // A paused enrollment keeps its receivers registered: no prompt, and no restart either.
            if (!userIdentificationMonitorRetained(context)) DeviceUnlockMonitoringService.reconcile(context)
            return
        }
        ResearchPersistenceGate.persistIfActive(context) {
            postIdentifyNotification(context, intent)
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

        return postNotificationIfPermitted(context, context.resources.getInteger(R.integer.identify_user_notification_id),
            notificationBuilder.build(), IDENTIFY_USER_NOTIFICATION_TAG)
    }

    private fun createOnDismissedIntent(): PendingIntent {
        val intent = Intent(NOTIFICATION_DELETED_ACTION)
        val resources = appContext.resources
        val notificationId = resources.getInteger(R.integer.dismiss_target_user_notification_id)
        intent.putExtra(appContext.getString(R.string.notification_id), notificationId)

        return PendingIntent.getBroadcast(appContext, 0, intent, getPendingIntentMutabilityFlag(0))
    }
}
