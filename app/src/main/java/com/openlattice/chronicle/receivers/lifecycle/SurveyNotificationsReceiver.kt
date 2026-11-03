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
import androidx.core.content.ContextCompat
import com.google.gson.Gson
import com.google.gson.JsonSyntaxException
import com.openlattice.chronicle.R
import com.openlattice.chronicle.constants.NotificationType
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
import com.openlattice.chronicle.services.notifications.SURVEY_NOTIFICATION_TAG
import com.openlattice.chronicle.services.notifications.armReminder
import com.openlattice.chronicle.services.notifications.claimReminderOccurrence
import com.openlattice.chronicle.services.notifications.postNotificationIfPermitted
import com.openlattice.chronicle.services.notifications.recordReminderUndelivered
import com.openlattice.chronicle.storage.UploadServerEntity
import com.openlattice.chronicle.utils.Utils.getPendingIntentMutabilityFlag
import com.openlattice.chronicle.BuildConfig
import com.openlattice.chronicle.collection.CollectionModuleId
import com.openlattice.chronicle.collection.state.ResearchPersistenceGate

class SurveyNotificationsReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (!BuildConfig.ALLOW_PARTICIPANT_FORM_REMINDERS) return
        val pending = goAsync()
        Thread {
            try {
                // An alarm often cold-starts the process before authorization is published; wait for it,
                // well inside the ~10 s goAsync budget
                // (https://developer.android.com/reference/android/content/BroadcastReceiver#goAsync()).
                // Past the deadline the occurrence is dropped: the process start's restore arms the next one,
                // and the survey reminder repeats until every day is answered.
                if (ResearchPersistenceGate.awaitAuthorization(context, AUTHORIZATION_WAIT_MS)) {
                    deliverReminder(context, intent)
                } else {
                    Log.w(RECEIVER_TAG, "Survey reminder fired before enrollment admission was published")
                }
            } catch (error: Exception) { Log.w(RECEIVER_TAG, "Survey reminder admission unavailable", error) }
            finally { pending.finish() }
        }.start()
    }
}

/**
 * Posts the reminder [intent] carries when the enrollment that armed it still owns this process and
 * admits it, and arms its next occurrence.
 */
private fun deliverReminder(context: Context, intent: Intent) {
    val gate = ResearchPersistenceGate
    val owner = gate.captureOwner(context) ?: return
    // Questionnaire reminders follow the questionnaire module. The daily app-usage survey has no
    // module of its own: the server schedules it only while the study's notifications are on.
    val token = if (reminderType(intent) == NotificationType.QUESTIONNAIRE) {
        gate.captureStudyEnabledObservation(context, CollectionModuleId.QUESTIONNAIRE)
    } else gate.captureObservation(context, null)
    // A reminder writes no research data, so research-storage admission never silences it; it needs only the
    // enrollment that armed it, still active, under the read lease a withdrawal waits on.
    if (!token.isCurrent()) return
    gate.runIfExpectedOwner(context, owner) {
        val scope = gate.observationScope(context, CollectionModuleId.QUESTIONNAIRE)
        if (scope?.key == intent.getStringExtra(SURVEY_ENROLLMENT_SCOPE) &&
            owner.studyId == intent.getStringExtra(STUDY_ID) && owner.participantId == intent.getStringExtra(PARTICIPANT_ID)) {
            postAdmitted(context, intent, owner)
        }
        true
    }
}

private fun reminderType(intent: Intent): NotificationType? = runCatching {
    Gson().fromJson(intent.getStringExtra(NOTIFICATION_DETAILS), NotificationDetails::class.java)?.type
}.getOrNull()

private fun postAdmitted(context: Context, intent: Intent, owner: UploadServerEntity) {
    if (intent.action != SURVEY_NOTIFICATION_ACTION ||
        intent.getStringExtra(PARTICIPANT_ID).isNullOrBlank() || intent.getStringExtra(STUDY_ID).isNullOrBlank()
    ) {
        return
    }

    Log.i(RECEIVER_TAG, "Received survey notification intent")
    val notificationEntry = intent.getStringExtra(NOTIFICATION_DETAILS)

    val notification = try {
        Gson().fromJson(
            notificationEntry,
            NotificationDetails::class.java
        )
    } catch (e: JsonSyntaxException) {
        Log.e(RECEIVER_TAG, "invalid json", e)
        null
    } ?: return

    // Held through the post: a sync that retired this code (form removed, rule changed) either
    // finishes first and nothing is posted, or waits until the post is done.
    synchronized(REMINDER_SCHEDULE_LOCK) {
        if (notification.requestCode() !in EnrollmentSettings(context).getMobileReminderRequestCodes()) return
        // Arm first: a repeated occurrence is not posted again, but its successor must still be armed.
        armReminder(context, notification, intent, consumedAtMillis = notification.scheduledAtMillis ?: System.currentTimeMillis())
        if (!claimReminderOccurrence(context, notification)) return
        // The survey reminder is posted only while the last fetched state had an ended day left to answer, and
        // only while the phone is still shared (the next sync cancels its alarm).
        if (notification.type == NotificationType.AWARENESS &&
            !(notification.unansweredDays && EnrollmentSettings(context).isUserIdentificationEnabled())
        ) {
            return
        }
        // Blocked notifications drop the reminder; the study sees it in the upload diagnostics.
        val posted = reminderNotification(context, intent, notification, notificationEntry)
        if (!postNotificationIfPermitted(context, notification.requestCode(), posted, SURVEY_NOTIFICATION_TAG)) {
            recordReminderUndelivered(context, owner, notification.requestCode())
        }
    }
}

private fun reminderNotification(context: Context, intent: Intent, notification: NotificationDetails,
                                 notificationEntry: String?): Notification {
    // The tap mints a fresh one-time code for its own form; the reminder carries none. It carries the
    // enrollment scope the alarm was admitted under.
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
        .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
        .setPublicVersion(NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_notification)
            .setContentTitle(context.getString(R.string.app_name))
            .setContentText(context.getString(R.string.reminder_public_message))
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .build())
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
    return builder.build()
}

private const val AUTHORIZATION_WAIT_MS = 5_000L
private val RECEIVER_TAG = SurveyNotificationsReceiver::class.java.name
