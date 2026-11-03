package com.openlattice.chronicle.services.notifications

import com.openlattice.chronicle.EnrolledWork
import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.BroadcastReceiver
import android.content.Intent
import android.os.Build
import android.util.Log
import com.openlattice.chronicle.constants.TelemetryEvents
import com.openlattice.chronicle.telemetry.LocalTelemetry

/** Arms a reminder, exactly while the platform allows it, else inexactly; returns false if the provider is unavailable. */
internal fun scheduleReminderAlarm(context: Context, triggerAtMillis: Long, pendingIntent: PendingIntent): Boolean {
    val manager = context.getSystemService(AlarmManager::class.java)
        ?: return false
    if (exactAlarmAccess(context)) {
        try {
            manager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAtMillis, pendingIntent)
            return true
        } catch (error: SecurityException) {
            Log.w("ExactAlarms", "Exact alarm access changed; using inexact reminder", error)
        }
    }
    LocalTelemetry.logEvent(TelemetryEvents.EXACT_ALARM_PERMISSION_DENIED, null)
    manager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAtMillis, pendingIntent)
    return true
}

/** Exact alarms need no permission below Android 12; from 12 on, the "Alarms & reminders" access as it stands now. */
internal fun exactAlarmAccess(context: Context): Boolean = Build.VERSION.SDK_INT < Build.VERSION_CODES.S ||
    context.getSystemService(AlarmManager::class.java)?.canScheduleExactAlarms() == true

/** Permission grants are advisory broadcasts: recheck before reconciling the stored schedules. */
class ExactAlarmPermissionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S ||
            intent.action != AlarmManager.ACTION_SCHEDULE_EXACT_ALARM_PERMISSION_STATE_CHANGED) return
        if (!exactAlarmAccess(context)) return
        try {
            EnrolledWork.rescheduleNotifications(context)
        } catch (error: IllegalStateException) {
            Log.w("ExactAlarms", "Reminder reconciliation could not be scheduled", error)
        }
    }
}
