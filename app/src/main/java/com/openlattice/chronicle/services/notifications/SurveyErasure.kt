package com.openlattice.chronicle.services.notifications

import android.app.AlarmManager
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import com.openlattice.chronicle.preferences.EncryptedPrefsHelper
import com.openlattice.chronicle.preferences.MOBILE_REMINDER_REQUEST_CODES
import com.openlattice.chronicle.receivers.lifecycle.SurveyNotificationsReceiver
import com.openlattice.chronicle.utils.Utils.getPendingIntentMutabilityFlag

const val SURVEY_ENROLLMENT_SCOPE = "survey_enrollment_scope"
const val SURVEY_NOTIFICATION_TAG = "chronicle_survey"

/** Cancel capabilities before retiring their durable cancellation handles. Caller owns stop. */
fun eraseSurveyArtifacts(context: Context) {
    val prefs = EncryptedPrefsHelper.getEncryptedPrefs(context)
    val codes = prefs.getStringSet(MOBILE_REMINDER_REQUEST_CODES, emptySet()).orEmpty()
        .mapNotNull(String::toIntOrNull).toSet()
    val alarms = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
    val notifications = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
    notifications.activeNotifications.filter { it.tag == SURVEY_NOTIFICATION_TAG || (it.tag == null && it.id in codes) }.forEach {
        it.notification.contentIntent?.cancel()
        notifications.cancel(it.tag, it.id)
    }
    codes.forEach { code ->
        val intent = Intent(context, SurveyNotificationsReceiver::class.java).setAction(SURVEY_NOTIFICATION_ACTION)
        PendingIntent.getBroadcast(context, code, intent, getPendingIntentMutabilityFlag(PendingIntent.FLAG_NO_CREATE))?.let {
            alarms.cancel(it)
            it.cancel()
        }
    }
    clearReminderAlarmState(context)
    check(prefs.edit().remove(MOBILE_REMINDER_REQUEST_CODES).commit()) { "Unable to retire survey alarms" }
}
