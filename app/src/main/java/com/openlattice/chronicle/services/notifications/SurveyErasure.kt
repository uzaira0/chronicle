package com.openlattice.chronicle.services.notifications

import com.openlattice.chronicle.storage.checkLocalStoreWrite
import android.app.NotificationManager
import android.content.Context
import com.openlattice.chronicle.preferences.EncryptedPrefsHelper
import com.openlattice.chronicle.preferences.MOBILE_REMINDER_REQUEST_CODES

const val SURVEY_ENROLLMENT_SCOPE = "survey_enrollment_scope"
const val SURVEY_NOTIFICATION_TAG = "chronicle_survey"

/** Cancel capabilities before retiring their durable cancellation handles. Caller owns stop. */
fun eraseSurveyArtifacts(context: Context) {
    val prefs = EncryptedPrefsHelper.getEncryptedPrefs(context)
    val codes = prefs.getStringSet(MOBILE_REMINDER_REQUEST_CODES, emptySet()).orEmpty()
        .mapNotNull(String::toIntOrNull).toSet()
    val notifications = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
    notifications.activeNotifications.filter { it.tag == SURVEY_NOTIFICATION_TAG || (it.tag == null && it.id in codes) }.forEach {
        it.notification.contentIntent?.cancel()
        notifications.cancel(it.tag, it.id)
    }
    codes.forEach { code -> cancelReminderByRequestCode(context, code) }
    clearReminderAlarmState(context)
    checkLocalStoreWrite(prefs.edit().remove(MOBILE_REMINDER_REQUEST_CODES).commit()) { "Unable to retire survey alarms" }
}
