package com.openlattice.chronicle.services

import androidx.annotation.CheckResult
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat

@CheckResult
internal fun startForegroundServiceIfPermitted(context: Context, intent: Intent): Boolean {
    return try {
        // Manifest FGS permissions are install grants; handle framework refusal: https://developer.android.com/develop/background-work/services/fgs/declare
        ContextCompat.startForegroundService(context, intent)
        true
    } catch (error: IllegalStateException) {
        // Includes ForegroundServiceStartNotAllowedException on API 31+.
        Log.w("ForegroundServices", "Foreground service start deferred", error)
        false
    } catch (error: SecurityException) {
        Log.w("ForegroundServices", "Foreground service start denied", error)
        false
    }
}

/**
 * Promotes [service] with the notification from [builder]. The notification is always shown at once: Android 12+
 * otherwise holds a foreground-service notification back for up to 10 s, and a participant who just turned a module
 * on must see that collection is running. Taking the builder (not a built Notification) keeps that rule in one place.
 */
@CheckResult
internal fun promoteForegroundServiceIfPermitted(service: Service, id: Int, builder: NotificationCompat.Builder): Boolean {
    val notification = builder.setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE).build()
    return try {
        // specialUse has no runtime prerequisites; promotion enforces access: https://developer.android.com/develop/background-work/services/fgs/service-types#special-use
        ServiceCompat.startForeground(service, id, notification,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE else 0)
        true
    } catch (error: IllegalStateException) {
        Log.w("ForegroundServices", "Foreground promotion deferred", error)
        false
    } catch (error: SecurityException) {
        Log.w("ForegroundServices", "Foreground promotion denied", error)
        false
    }
}
