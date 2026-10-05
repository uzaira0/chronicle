package com.openlattice.chronicle.receivers.lifecycle

import android.app.Notification
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.media.RingtoneManager
import android.net.Uri
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.google.gson.Gson
import com.google.gson.JsonSyntaxException
import com.openlattice.chronicle.R
import com.openlattice.chronicle.preferences.EnrollmentSettings
import com.openlattice.chronicle.preferences.PARTICIPANT_ID
import com.openlattice.chronicle.preferences.STUDY_ID
import com.openlattice.chronicle.services.notifications.CHANNEL_ID
import com.openlattice.chronicle.services.notifications.NOTIFICATION_DETAILS
import com.openlattice.chronicle.services.notifications.NotificationDetails
import com.openlattice.chronicle.services.notifications.REMINDER_SCHEDULE_LOCK
import com.openlattice.chronicle.services.notifications.ReminderLinkActivity
import com.openlattice.chronicle.services.notifications.SURVEY_ENROLLMENT_SCOPE
import com.openlattice.chronicle.services.notifications.SURVEY_NOTIFICATION_ACTION
import com.openlattice.chronicle.services.notifications.armReminder
import com.openlattice.chronicle.utils.Utils.getPendingIntentMutabilityFlag

class SurveyNotificationsReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (!com.openlattice.chronicle.BuildConfig.ALLOW_PARTICIPANT_FORM_REMINDERS) return
        val gate = com.openlattice.chronicle.collection.state.ResearchPersistenceGate
        val pending = goAsync()
        Thread {
        try {
        // An alarm often cold-starts the process before authorization is published; wait for it,
        // well inside the ~10 s goAsync budget, instead of dropping the reminder.
        gate.awaitAuthorization(context, AUTHORIZATION_WAIT_MS)
        val owner = gate.captureOwner(context) ?: return@Thread
        val token = gate.captureStudyEnabledObservation(context, com.openlattice.chronicle.collection.CollectionModuleId.QUESTIONNAIRE)
        token.persist {
        gate.runIfExpectedOwner(context, owner) {
            val scope = gate.observationScope(context, com.openlattice.chronicle.collection.CollectionModuleId.QUESTIONNAIRE)
            if (scope?.first == intent.getStringExtra(com.openlattice.chronicle.services.notifications.SURVEY_ENROLLMENT_SCOPE) &&
                owner.studyId == intent.getStringExtra(STUDY_ID) && owner.participantId == intent.getStringExtra(PARTICIPANT_ID)) {
                postAdmitted(context, intent)
            }
            true
        }
        } } catch (error: Exception) { Log.w(javaClass.name, "Survey reminder admission unavailable", error) }
        finally { pending.finish() }
        }.start()
    }

    private fun postAdmitted(context: Context, intent: Intent) {

        if (intent.action != SURVEY_NOTIFICATION_ACTION) {
            return
        }

        Log.i(javaClass.name, "Received survey notification intent")

        val participantId = intent.getStringExtra(PARTICIPANT_ID)
        val studyId = intent.getStringExtra(STUDY_ID)

        if (participantId.isNullOrBlank() || studyId.isNullOrBlank()) {
            return
        }
        val notificationEntry = intent.getStringExtra(NOTIFICATION_DETAILS)

        val notification = try {
            Gson().fromJson(
                notificationEntry,
                NotificationDetails::class.java
            )
        } catch (e: JsonSyntaxException) {
            Log.e(javaClass.name, "invalid json", e)
            null
        } ?: return

        // Held through the post: a sync that retired this code (form removed, rule changed) either
        // finishes first and nothing is posted, or waits until the post is done.
        synchronized(REMINDER_SCHEDULE_LOCK) {
            if (notification.requestCode() !in EnrollmentSettings(context).getMobileReminderRequestCodes()) return
            armReminder(context, notification, intent, consumedAtMillis = notification.scheduledAtMillis ?: System.currentTimeMillis())
            // The tap fetches a fresh one-time code: the code in this reminder is revoked at the
            // next reminder sync. It carries the enrollment scope the alarm was admitted under.
            val notifyIntent = Intent(context, ReminderLinkActivity::class.java)
                .putExtra(NOTIFICATION_DETAILS, notificationEntry)
                .putExtra(SURVEY_ENROLLMENT_SCOPE, intent.getStringExtra(SURVEY_ENROLLMENT_SCOPE))
            val pendingIntent: PendingIntent =
                PendingIntent.getActivity(
                    context,
                    notification.requestCode(),
                    notifyIntent,
                    getPendingIntentMutabilityFlag(PendingIntent.FLAG_UPDATE_CURRENT),
                )

            val builder = NotificationCompat.Builder(context, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_stat_notification)
                .setColor(ContextCompat.getColor(context, R.color.colorPrimary))
                // Standard template: Android 12+ clips custom RemoteViews layouts to ~48dp.
                .setContentTitle(notification.title)
                .setContentText(notification.message)
                .setStyle(NotificationCompat.BigTextStyle().bigText(notification.message))
                .setShowWhen(true)
                .setPriority(NotificationCompat.PRIORITY_HIGH) //support android 7.1
                .setContentIntent(pendingIntent)
                .setDefaults(Notification.DEFAULT_VIBRATE)
                // ReminderLinkActivity removes it once the form opens, so a failed tap can be retried.
                .setAutoCancel(false)

            val notificationSound: Uri =
                RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)
            builder.setSound(notificationSound)

            // POST_NOTIFICATIONS (API 33+) is a runtime permission the user may have denied; NotificationManagerCompat.notify
            // then throws SecurityException. Handle it explicitly (same contract as CollectionLoopCoordinator.notifySafely):
            // the deep-link survey reminder is best-effort, so a denied permission is logged and swallowed, never crashes the receiver.
            try {
                NotificationManagerCompat.from(context).notify(com.openlattice.chronicle.services.notifications.SURVEY_NOTIFICATION_TAG, notification.requestCode(), builder.build())
            } catch (e: SecurityException) {
                Log.w(javaClass.name, "Survey notification suppressed (POST_NOTIFICATIONS not granted)", e)
            }
        }
    }
}

private const val AUTHORIZATION_WAIT_MS = 5_000L
