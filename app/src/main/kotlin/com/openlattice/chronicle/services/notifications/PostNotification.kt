package com.openlattice.chronicle.services.notifications

import android.app.Notification
import android.content.Context
import android.util.Log
import androidx.core.app.NotificationManagerCompat
import com.openlattice.chronicle.utils.Utils

/**
 * Posts a best-effort notification; callers keep working without the notice. Returns whether it was posted.
 * Without permission (POST_NOTIFICATIONS denied, app or channel blocked) Android usually drops the notification
 * silently, so permission is checked first; some versions throw SecurityException instead.
 */
fun postNotificationIfPermitted(context: Context, id: Int, notification: Notification, tag: String? = null): Boolean {
    // Create channels before checking delivery; recreation is a no-op: https://developer.android.com/develop/ui/views/notifications/channels#CreateChannel
    Utils.createNotificationChannel(context)
    if (!hasNotificationPermission(context, notification.channelId)) {
        Log.w("PostNotification", "Notification $id not posted: notifications not permitted")
        return false
    }
    return try {
        NotificationManagerCompat.from(context).notify(tag, id, notification)
        true
    } catch (e: SecurityException) {
        Log.w("PostNotification", "Notification $id suppressed (POST_NOTIFICATIONS not granted)", e)
        false
    }
}
