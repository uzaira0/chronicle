package com.openlattice.chronicle.services.notifications

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import org.robolectric.Shadows.shadowOf
import org.robolectric.shadows.ShadowAlarmManager

/**
 * The scheduled alarms that deliver a reminder: their operation is the [SURVEY_NOTIFICATION_ACTION] broadcast
 * (SurveyNotificationsReceiver; a re-arm from a delivered broadcast keeps its action, not always its component). Below
 * Android 11 WorkManager's initialization also arms its own force-stop alarm on a background thread (ReminderAlarmsTest),
 * so a reminder assertion counts reminder alarms, never every alarm.
 */
internal fun reminderAlarms(context: Context): List<ShadowAlarmManager.ScheduledAlarm> =
    shadowOf(context.getSystemService(AlarmManager::class.java)).scheduledAlarms.filter {
        alarmOperation(it)?.let { operation -> shadowOf(operation).savedIntent.action } == SURVEY_NOTIFICATION_ACTION
    }

/** The alarm's PendingIntent (null for a listener alarm), the one place tests read it. */
// Robolectric 4.17 exposes it only as the deprecated public field (no getter); this replaces the per-test reads.
@Suppress("DEPRECATION")
internal fun alarmOperation(alarm: ShadowAlarmManager.ScheduledAlarm): PendingIntent? = alarm.operation
