package com.openlattice.chronicle.services.notifications

import android.annotation.SuppressLint
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.openlattice.chronicle.MainActivity
import com.openlattice.chronicle.R
import com.openlattice.chronicle.receivers.lifecycle.NotificationDismissedReceiver
import com.openlattice.chronicle.receivers.lifecycle.DeviceLifecycleReceiver
import com.openlattice.chronicle.receivers.lifecycle.UnlockDeviceReceiver
import com.openlattice.chronicle.collection.state.ResearchPersistenceGate
import com.openlattice.chronicle.collection.state.CollectionGate
import com.openlattice.chronicle.collection.CollectionModuleId
import com.openlattice.chronicle.preferences.EnrollmentSettings
import com.openlattice.chronicle.preferences.EncryptedPrefsHelper
import com.openlattice.chronicle.services.lifecycle.DeviceLifecycleEventRecorder
import com.openlattice.chronicle.services.lifecycle.deviceLifecycleIntentFilter
import com.openlattice.chronicle.utils.Utils.getPendingIntentMutabilityFlag
import com.openlattice.chronicle.utils.Utils.createNotificationChannel
import java.util.concurrent.Executors

// A "forever running" service to monitor device unlock. A workaround for devices running version >= 8.0
// since we can no longer register ACTION_USER_PRESENT intent in manifest

/**
 * This is required to be a foreground service, because ACTION_USER_PRESENT must be dynamically
 * registered for in the manifest. So in order to constantly be registered across multiple lock
 * and unlock cycles, we have to have a foreground service present.
 */
class DeviceUnlockMonitoringService : Service() {

    private var unlockDeviceReceiver = UnlockDeviceReceiver()
    private var notificationDismissedReceiver = NotificationDismissedReceiver()
    private var lifecycleReceiver = DeviceLifecycleReceiver()
    private var receiversRegistered = false
    @Volatile private var destroyed = false

    companion object {
        private const val RESTART_ON_BOOT_KEY = "restartOnBoot"
        private val AUTHORIZATION_EXECUTOR = Executors.newSingleThreadExecutor()

        /**
         * Starts while the caller still owns its foreground/broadcast exemption. Callers must
         * have already evaluated [userIdentificationMayRun] off the main thread. Deferring the
         * actual framework call to another executor can outlive that exemption on Android 12+.
         */
        fun startAuthorizedService(context: Context, restartOnBoot: Boolean? = false): Boolean {
            val appContext = context.applicationContext
            val intent = Intent(appContext, DeviceUnlockMonitoringService::class.java).apply {
                putExtra(RESTART_ON_BOOT_KEY, restartOnBoot)
            }
            try {
                ContextCompat.startForegroundService(appContext, intent)
            } catch (error: IllegalStateException) {
                UnlockMonitoringRuntimeStatus.markDeferred(appContext, true)
                Log.w(javaClass.name, "Android deferred unlock monitoring until Chronicle is foreground", error)
                return false
            } catch (error: SecurityException) {
                UnlockMonitoringRuntimeStatus.markDeferred(appContext, true)
                Log.w(javaClass.name, "Android denied unlock-monitoring foreground service startup", error)
                return false
            }
            UnlockMonitoringRuntimeStatus.markDeferred(appContext, false)
            return true
        }

        fun stopService(context: Context) {
            val intent = Intent(context, DeviceUnlockMonitoringService::class.java)
            context.stopService(intent)
        }
    }

    override fun onBind(intent: Intent?): IBinder? {
        return null
    }


    override fun onCreate() {
        super.onCreate()
        // Android requires a service created via startForegroundService to promote promptly.
        // Receivers remain unregistered until the authoritative manifest is checked off-main.
        startForeground()
        UnlockMonitoringRuntimeStatus.markDeferred(applicationContext, false)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val restartOnBoot = intent?.getBooleanExtra(RESTART_ON_BOOT_KEY, false)

        AUTHORIZATION_EXECUTOR.execute {
            val authorized = userIdentificationMayRun(applicationContext)
            ContextCompat.getMainExecutor(applicationContext).execute main@{
                if (destroyed) return@main
                if (!authorized) {
                    Log.i(javaClass.name, "unlock monitoring service stopped: study scope is inactive")
                    stopSelfResult(startId)
                    return@main
                }
                if (!receiversRegistered) {
                    registerReceivers()
                    receiversRegistered = true
                }
                Log.i(
                    javaClass.name,
                    "unlock monitoring service started with restartOnBoot = $restartOnBoot",
                )
                if (restartOnBoot == true) {
                    Intent(applicationContext.getString(R.string.action_identify_after_reboot))
                        .setPackage(applicationContext.packageName)
                        .also(applicationContext::sendBroadcast)
                }
            }
        }

        // if the service is killed after starting, the system will try to re-create the service later
        return START_STICKY
    }

    private fun startForeground() {
        // A startForegroundService call may arrive before any reminder worker or Activity has
        // created the shared channel (fresh install, boot, or minimal-boundary completion).
        // Create it synchronously before publishing the mandatory foreground notification.
        createNotificationChannel(applicationContext)

        val pendingIntent: PendingIntent =
            Intent(applicationContext, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
            }.let {
                PendingIntent.getActivity(applicationContext, 0, it, getPendingIntentMutabilityFlag(0))
            }

        val notification = NotificationCompat.Builder(applicationContext, CHANNEL_ID)
            .setContentTitle(getString(R.string.interactivity_monitoring_notification_title))
            .setContentText(getString(R.string.interactivity_monitoring_notification_message))
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOngoing(true)
            .setSmallIcon(R.drawable.ic_stat_notification)
            .setColor(ContextCompat.getColor(applicationContext, R.color.colorPrimary))
            .setContentIntent(pendingIntent)
            .build()

        val notificationId =
            applicationContext.resources.getInteger(R.integer.unlock_phone_monitoring_notification_id)
        startForeground(notificationId, notification)
    }

    private fun createReceiverIntentFilter(
        actions: Set<String>,
        priority: Int = IntentFilter.SYSTEM_HIGH_PRIORITY
    ): IntentFilter {
        return IntentFilter().also {
            it.priority = priority
            for (action in actions) {
                it.addAction(action)
            }
        }
    }

    @SuppressLint("UnspecifiedRegisterReceiverFlag")
    private fun registerReceivers() {

        var intentFilter =
            createReceiverIntentFilter(
                UnlockDeviceReceiver.getValidReceiverActions(
                    applicationContext
                )
            )
        ContextCompat.registerReceiver(
            applicationContext,
            unlockDeviceReceiver,
            intentFilter,
            ContextCompat.RECEIVER_NOT_EXPORTED,
        )
        Log.i(javaClass.name, "${UnlockDeviceReceiver::class.java.canonicalName} is registered")

        intentFilter = createReceiverIntentFilter(setOf(NOTIFICATION_DELETED_ACTION))
        ContextCompat.registerReceiver(
            applicationContext,
            notificationDismissedReceiver,
            intentFilter,
            ContextCompat.RECEIVER_NOT_EXPORTED,
        )
        Log.i(
            javaClass.name,
            "${NotificationDismissedReceiver::class.java.canonicalName} is registered"
        )

        intentFilter = deviceLifecycleIntentFilter()
        intentFilter.priority = IntentFilter.SYSTEM_LOW_PRIORITY
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            applicationContext.registerReceiver(lifecycleReceiver, intentFilter, RECEIVER_NOT_EXPORTED)
        } else {
            applicationContext.registerReceiver(lifecycleReceiver, intentFilter)
        }
        Log.i(javaClass.name, "${DeviceLifecycleReceiver::class.java.canonicalName} is registered")
    }

    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        DeviceLifecycleEventRecorder.recordAsync(
            applicationContext,
            DeviceLifecycleEventRecorder.lowMemoryEvent(level)
        )
    }

    override fun onDestroy() {
        destroyed = true
        Log.i(javaClass.name, "unlock monitoring service stopped. Unregistering receivers")
        if (receiversRegistered) {
            applicationContext.unregisterReceiver(unlockDeviceReceiver)
            applicationContext.unregisterReceiver(notificationDismissedReceiver)
            try {
                applicationContext.unregisterReceiver(lifecycleReceiver)
            } catch (e: IllegalArgumentException) {
                Log.w(javaClass.name, "Lifecycle receiver was not registered", e)
            }
            receiversRegistered = false
        }
        super.onDestroy()
    }
}

internal object UnlockMonitoringRuntimeStatus {
    private const val PREF_START_DEFERRED = "unlock_monitoring_start_deferred"

    fun isDeferred(context: Context): Boolean =
        EncryptedPrefsHelper.getEncryptedPrefs(context.applicationContext)
            .getBoolean(PREF_START_DEFERRED, false)

    fun markDeferred(context: Context, deferred: Boolean) {
        check(
            EncryptedPrefsHelper.getEncryptedPrefs(context.applicationContext)
                .edit()
                .putBoolean(PREF_START_DEFERRED, deferred)
                .commit(),
        ) { "Unable to persist unlock-monitoring runtime status" }
    }
}

/** Study authority, local opt-in, active enrollment, and notification delivery must all hold. */
internal fun userIdentificationMayRun(context: Context): Boolean =
    hasNotificationPermission(context.applicationContext) &&
        ResearchPersistenceGate.isActiveEnrollment(context.applicationContext) &&
        CollectionGate.collects(
            context.applicationContext,
            CollectionModuleId.USER_IDENTIFICATION,
        ) &&
        runCatching {
            EnrollmentSettings(context.applicationContext).isUserIdentificationEnabled()
        }.getOrDefault(false)
