package com.openlattice.chronicle.receivers.lifecycle

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.text.format.DateUtils
import android.util.Log
import android.widget.RemoteViews
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.openlattice.chronicle.R
import com.openlattice.chronicle.UserIdentificationActivity
import com.openlattice.chronicle.collection.state.ResearchPersistenceGate
import com.openlattice.chronicle.services.notifications.CHANNEL_ID
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
        ResearchPersistenceGate.persistIfActive(context) {
            postIdentifyNotification(context, intent)
        }
    }

    private fun postIdentifyNotification(context: Context, intent: Intent) {
        val action = intent.action
        if (!getValidReceiverActions(context).contains(action)) {
            return
        }

        val layout = RemoteViews(context.packageName, R.layout.notification)
        layout.setTextViewText(
            R.id.timestamp,
            DateUtils.formatDateTime(
                context,
                System.currentTimeMillis(),
                DateUtils.FORMAT_SHOW_TIME
            )
        )
        layout.setTextViewText(
            R.id.notification_title,
            context.getString(R.string.on_wake_notification_title)
        )
        layout.setTextViewText(
            R.id.notification_message,
            context.getString(R.string.on_wake_notification_message)
        )

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

        val notificationBuilder = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_notification)
            .setColor(ContextCompat.getColor(context, R.color.colorPrimary))
            .setCustomContentView(layout)
            .setCustomBigContentView(layout)
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setContentIntent(pendingIntent)
            .setDeleteIntent(createOnDismissedIntent())
//            .setDefaults(NotificationCompat.DEFAULT_VIBRATE)
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
        }

    }

    private fun createOnDismissedIntent(): PendingIntent {
        val intent = Intent(NOTIFICATION_DELETED_ACTION)
        val resources = appContext.resources
        val notificationId = resources.getInteger(R.integer.dismiss_target_user_notification_id)
        intent.putExtra(appContext.getString(R.string.notification_id), notificationId)

        return PendingIntent.getBroadcast(appContext, 0, intent, getPendingIntentMutabilityFlag(0))
    }
}
