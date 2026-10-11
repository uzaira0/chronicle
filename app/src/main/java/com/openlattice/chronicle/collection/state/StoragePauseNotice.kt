package com.openlattice.chronicle.collection.state

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import com.openlattice.chronicle.MainActivity
import com.openlattice.chronicle.R
import com.openlattice.chronicle.services.notifications.CHANNEL_ID
import com.openlattice.chronicle.services.notifications.postNotificationIfPermitted

internal const val STORAGE_PAUSE_NOTIFICATION_ID = 47_005

internal fun notifyStoragePause(context: Context): Boolean {
    if (ResearchPersistenceGate.captureOwner(context) == null) return false
    val pending = PendingIntent.getActivity(context, STORAGE_PAUSE_NOTIFICATION_ID,
        Intent(context, MainActivity::class.java).putExtra(MainActivity.EXTRA_SELECT_TAB, R.id.nav_data_sharing),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
    val text = context.getString(R.string.collection_paused_storage)
    // Not posted (permission denied) leaves the episode un-notified, so a later grant still shows it.
    return postNotificationIfPermitted(context, STORAGE_PAUSE_NOTIFICATION_ID, NotificationCompat.Builder(context, CHANNEL_ID)
        .setSmallIcon(R.drawable.ic_stat_notification).setContentTitle(context.getString(R.string.app_name))
        .setContentText(text).setStyle(NotificationCompat.BigTextStyle().bigText(text))
        .setVisibility(NotificationCompat.VISIBILITY_PRIVATE).setContentIntent(pending).setAutoCancel(true).build())
}
