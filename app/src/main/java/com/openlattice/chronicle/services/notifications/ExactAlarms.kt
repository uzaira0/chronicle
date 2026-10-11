package com.openlattice.chronicle.services.notifications

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.BroadcastReceiver
import android.content.Intent
import android.os.Build
import android.util.Log
import com.openlattice.chronicle.constants.TelemetryEvents
import com.openlattice.chronicle.telemetry.LocalTelemetry

/** Arms a reminder, allowing inexact delivery; returns false if the provider is unavailable. */
internal fun scheduleReminderAlarm(context: Context, triggerAtMillis: Long, pendingIntent: PendingIntent): Boolean {
    val manager = context.getSystemService(AlarmManager::class.java)
        ?: return false
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && manager.canScheduleExactAlarms()) {
        try {
            manager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAtMillis, pendingIntent)
            return true
        } catch (error: SecurityException) {
            Log.w("ExactAlarms", "Exact alarm access changed; using inexact reminder", error)
        }
    }
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        LocalTelemetry.logEvent(TelemetryEvents.EXACT_ALARM_PERMISSION_DENIED, null)
    }
    manager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAtMillis, pendingIntent)
    return true
}

/** Permission grants are advisory broadcasts: recheck before reconciling the stored schedules. */
class ExactAlarmPermissionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S ||
            intent.action != AlarmManager.ACTION_SCHEDULE_EXACT_ALARM_PERMISSION_STATE_CHANGED) return
        if (context.getSystemService(AlarmManager::class.java)?.canScheduleExactAlarms() != true) return
        try {
            scheduleNotificationsWorker(context)
        } catch (error: IllegalStateException) {
            Log.w("ExactAlarms", "Reminder reconciliation could not be scheduled", error)
        }
    }
}
