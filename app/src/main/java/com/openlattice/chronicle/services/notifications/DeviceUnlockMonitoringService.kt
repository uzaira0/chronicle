package com.openlattice.chronicle.services.notifications

import android.app.PendingIntent
import android.app.Service
import android.content.Context
import com.openlattice.chronicle.utils.registerPrivateReceiver
import com.openlattice.chronicle.utils.registerSystemBroadcastReceiver
import android.content.Intent
import android.content.IntentFilter
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.openlattice.chronicle.services.startForegroundServiceIfPermitted
import com.openlattice.chronicle.services.promoteForegroundServiceIfPermitted
import com.openlattice.chronicle.MainActivity
import com.openlattice.chronicle.R
import com.openlattice.chronicle.receivers.lifecycle.NotificationDismissedReceiver
import com.openlattice.chronicle.receivers.lifecycle.UnlockDeviceReceiver
import com.openlattice.chronicle.collection.state.ResearchPersistenceGate
import com.openlattice.chronicle.collection.state.participationRetainsServices
import com.openlattice.chronicle.collection.state.CollectionLoopStore
import com.openlattice.chronicle.collection.state.StorageAdmission
import com.openlattice.chronicle.collection.CollectionModuleId
import com.openlattice.chronicle.collection.identification.DeviceUser
import com.openlattice.chronicle.collection.identification.TargetUserRouter
import com.openlattice.chronicle.collection.core.ModuleResult
import com.openlattice.chronicle.preferences.EnrollmentSettings
import com.openlattice.chronicle.preferences.EncryptedPrefsHelper
import com.openlattice.chronicle.utils.Utils.getPendingIntentMutabilityFlag
import com.openlattice.chronicle.utils.Utils.createNotificationChannel
import java.util.concurrent.Executors
import com.openlattice.chronicle.storage.LocalStoreRecoveryRequiredException

// A "forever running" service to monitor device unlock. A workaround for devices running version >= 8.0
// since we can no longer register ACTION_USER_PRESENT intent in manifest

/**
 * This is required to be a foreground service, because ACTION_USER_PRESENT must be dynamically
 * registered for in the manifest. So in order to constantly be registered across multiple lock
 * and unlock cycles, we have to have a foreground service present.
 *
 * It runs only for user identification ([deviceMonitoringMayRun]). Device state (battery, charging,
 * Battery Saver, network, low memory) never starts or keeps it: `DeviceStateSampler.record` samples
 * that state at boot, when Chronicle opens and on each periodic sync, and `HardwareSensorService`
 * adds broadcast-time rows while it runs for a sensor. Screen, unlock, startup and shutdown come
 * from the usage timeline.
 */
class DeviceUnlockMonitoringService : Service() {

    private var unlockDeviceReceiver = UnlockDeviceReceiver()
    private var identifyAfterRebootReceiver = UnlockDeviceReceiver()
    private var notificationDismissedReceiver = NotificationDismissedReceiver()
    private var identificationReceiversRegistered = false
    @Volatile private var destroyed = false

    companion object {
        private const val RESTART_ON_BOOT_KEY = "restartOnBoot"
        private val AUTHORIZATION_EXECUTOR = Executors.newSingleThreadExecutor()

        internal fun resetObservedUser(context: Context) {
            val error = try {
                (TargetUserRouter.setTargetUser(context, DeviceUser.NOT_SET) as? ModuleResult.Failed)?.error
            } catch (error: LocalStoreRecoveryRequiredException) { error }
            if (error != null) Log.w("DeviceUnlockMonitoring", "Target-user reset failed", error)
        }

        /**
         * Starts while the caller still owns its foreground/broadcast exemption. Callers must
         * have already evaluated [deviceMonitoringMayRun] off the main thread. Deferring the
         * actual framework call to another executor can outlive that exemption on Android 12+.
         */
        fun startAuthorizedService(context: Context, restartOnBoot: Boolean? = false): Boolean {
            val appContext = context.applicationContext
            val intent = Intent(appContext, DeviceUnlockMonitoringService::class.java).apply {
                putExtra(RESTART_ON_BOOT_KEY, restartOnBoot)
            }
            if (!startForegroundServiceIfPermitted(appContext, intent)) {
                UnlockMonitoringRuntimeStatus.markDeferred(appContext, true)
                return false
            }
            UnlockMonitoringRuntimeStatus.markDeferred(appContext, false)
            return true
        }

        fun reconcile(context: Context, restartOnBoot: Boolean = false): Boolean =
            if (deviceMonitoringMayRun(context)) startAuthorizedService(context, restartOnBoot)
            else { stopService(context); false }

        fun stopService(context: Context) {
            AUTHORIZATION_EXECUTOR.execute { resetObservedUser(context.applicationContext) }
            val intent = Intent(context, DeviceUnlockMonitoringService::class.java)
            context.stopService(intent)
        }
    }

    override fun onBind(intent: Intent?): IBinder? {
        return null
    }


    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // Every startForegroundService start must promote promptly, not only the one that created the service:
        // Android can demote a running service (background usage toggled), and the next start then crashes the
        // app unless it promotes again (https://developer.android.com/develop/background-work/services/fgs/launch).
        // Receivers remain unregistered until the authoritative manifest is checked off-main.
        // Promotion can still fail after a permission or service-state change. Record it as deferred (Settings shows the
        // "open Chronicle" hint and MainActivity restarts it) instead of crash-looping.
        if (!runCatching { startForeground() }.onFailure {
                Log.w(javaClass.name, "Foreground notification creation failed", it)
            }.getOrDefault(false)) {
            UnlockMonitoringRuntimeStatus.markDeferred(applicationContext, true)
            // A later start already queued must still run and promote; a bare stopSelf would bring the
            // service down with that start's promotion pending, which is the same crash.
            if (stopSelfResult(startId)) destroyed = true
            return START_NOT_STICKY
        }
        UnlockMonitoringRuntimeStatus.markDeferred(applicationContext, false)
        val restartOnBoot = intent?.getBooleanExtra(RESTART_ON_BOOT_KEY, false)

        AUTHORIZATION_EXECUTOR.execute {
            // Receivers follow the retained enrollment (a pause keeps them); each callback admits
            // only active collection.
            val identify = userIdentificationMonitorRetained(applicationContext)
            val prompt = userIdentificationMayRun(applicationContext)
            if (!prompt) resetObservedUser(applicationContext)
            ContextCompat.getMainExecutor(applicationContext).execute main@{
                if (destroyed) return@main
                reconcileReceivers(identify)
                if (!identify) {
                    Log.i(javaClass.name, "unlock monitoring service stopped: study scope is inactive")
                    stopSelfResult(startId)
                    return@main
                }
                Log.i(
                    javaClass.name,
                    "unlock monitoring service started with restartOnBoot = $restartOnBoot",
                )
                if (restartOnBoot == true && prompt) {
                    Intent(applicationContext.getString(R.string.action_identify_after_reboot))
                        .setPackage(applicationContext.packageName)
                        .also(applicationContext::sendBroadcast)
                }
            }
        }

        // if the service is killed after starting, the system will try to re-create the service later
        return START_STICKY
    }

    private fun startForeground(): Boolean {
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

        val notification = NotificationCompat.Builder(applicationContext, UNLOCK_MONITORING_CHANNEL_ID)
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
        return promoteForegroundServiceIfPermitted(this, notificationId, notification)
    }

    private fun createReceiverIntentFilter(
        actions: Set<String>,
        priority: Int = 0
    ): IntentFilter {
        return IntentFilter().also {
            it.priority = priority
            for (action in actions) {
                it.addAction(action)
            }
        }
    }

    private fun registerIdentificationReceivers() {

        val identifyAction = getString(R.string.action_identify_after_reboot)
        var intentFilter = createReceiverIntentFilter(
            UnlockDeviceReceiver.getValidReceiverActions(applicationContext) - identifyAction,
        )
        // Partition system and app actions across distinct receivers: https://developer.android.com/develop/background-work/background-tasks/broadcasts#context-registered-receivers
        registerSystemBroadcastReceiver(applicationContext, unlockDeviceReceiver, intentFilter)
        registerPrivateReceiver(applicationContext, identifyAfterRebootReceiver, IntentFilter(identifyAction))
        Log.i(javaClass.name, "${UnlockDeviceReceiver::class.java.canonicalName} is registered")

        intentFilter = createReceiverIntentFilter(setOf(NOTIFICATION_DELETED_ACTION))
        registerPrivateReceiver(applicationContext, notificationDismissedReceiver, intentFilter)
        Log.i(
            javaClass.name,
            "${NotificationDismissedReceiver::class.java.canonicalName} is registered"
        )

    }

    private fun reconcileReceivers(identify: Boolean) {
        if (identify && !identificationReceiversRegistered) {
            registerIdentificationReceivers()
            identificationReceiversRegistered = true
        } else if (!identify && identificationReceiversRegistered) {
            applicationContext.unregisterReceiver(unlockDeviceReceiver)
            applicationContext.unregisterReceiver(identifyAfterRebootReceiver)
            applicationContext.unregisterReceiver(notificationDismissedReceiver)
            identificationReceiversRegistered = false
        }
    }

    override fun onDestroy() {
        destroyed = true
        AUTHORIZATION_EXECUTOR.execute { resetObservedUser(applicationContext) }
        Log.i(javaClass.name, "unlock monitoring service stopped. Unregistering receivers")
        reconcileReceivers(false)
        super.onDestroy()
    }
}

/** Study authority, local opt-in, active enrollment, notification delivery and research storage must all hold. */
internal fun userIdentificationMayRun(context: Context): Boolean =
    userIdentificationAllowed(context, ResearchPersistenceGate::isActiveEnrollment) && StorageAdmission.allowed(context)

/**
 * The monitor and its receivers live while user identification is retained, including a remote pause:
 * resuming from the background cannot rely on a new foreground-service start, which Android 12+
 * refuses there (https://developer.android.com/develop/background-work/services/fgs/restrictions-bg-start).
 * Prompts still require [userIdentificationMayRun]. A terminal state (completed, withdrawn, not
 * enrolled), the Identify device user switch turned off, or blocked prompts stop it. No other module
 * keeps it running.
 */
internal fun deviceMonitoringMayRun(context: Context): Boolean = userIdentificationMonitorRetained(context)

internal fun userIdentificationMonitorRetained(context: Context): Boolean =
    userIdentificationAllowed(context, ::enrollmentRetained)

internal object UnlockMonitoringRuntimeStatus {
    private const val PREF_START_DEFERRED = "unlock_monitoring_start_deferred"

    fun isDeferred(context: Context): Boolean =
        EncryptedPrefsHelper.getEncryptedPrefs(context.applicationContext)
            .getBoolean(PREF_START_DEFERRED, false)

    /** One writer in call order: the authorization worker (a main-thread caller must not open secure storage). */
    fun markDeferred(context: Context, deferred: Boolean) {
        if (!ResearchPersistenceGate.isOnAuthorizationWorker()) {
            ResearchPersistenceGate.executeAsync { markDeferred(context.applicationContext, deferred) }
            return
        }
        try {
            check(
                EncryptedPrefsHelper.getEncryptedPrefs(context.applicationContext)
                    .edit()
                    .putBoolean(PREF_START_DEFERRED, deferred)
                    .commit(),
            ) { "Unable to persist unlock-monitoring runtime status" }
        } catch (error: Exception) {
            Log.e("UnlockMonitoringRuntimeStatus", "Unable to persist monitoring status hint", error)
        }
    }
}

/** The local half of user identification, as Settings' Identify device user switch and Data Sharing both show it. */
enum class IdentifyDeviceUser { ON, OFF, NOTIFICATIONS_BLOCKED }

/**
 * The one owner of the local state: the participant's switch, then whether the unlock prompt can be shown. Chronicle's
 * channels are created first (idempotent; a participant's channel settings are kept), so a channel Chronicle has not
 * posted to yet does not read as blocked.
 */
internal fun identifyDeviceUserState(context: Context): IdentifyDeviceUser {
    val app = context.applicationContext
    if (!runCatching { EnrollmentSettings(app).isUserIdentificationEnabled() }.getOrDefault(false)) return IdentifyDeviceUser.OFF
    createNotificationChannel(app)
    return if (hasNotificationPermission(app, IDENTIFY_USER_CHANNEL_ID)) IdentifyDeviceUser.ON else IdentifyDeviceUser.NOTIFICATIONS_BLOCKED
}

private fun userIdentificationAllowed(context: Context, enrolled: (Context) -> Boolean): Boolean =
    identifyDeviceUserState(context) == IdentifyDeviceUser.ON &&
        enrolled(context.applicationContext) &&
        moduleCollects(context, CollectionModuleId.USER_IDENTIFICATION)

/**
 * The durable decision alone (accepted, study-enabled, no required decline): the monitor outlives a full disk,
 * which only refuses the writes behind it.
 */
private fun moduleCollects(context: Context, module: CollectionModuleId): Boolean =
    runCatching { CollectionLoopStore.of(context).collects(module) }.getOrDefault(false)

// No persistence lease: reconcile also runs inside ResearchPersistenceGate.stop.
private fun enrollmentRetained(context: Context): Boolean =
    ResearchPersistenceGate.isEnrolledIgnoringStatus(context) &&
        participationRetainsServices(runCatching { EnrollmentSettings(context).getParticipationStatus() }.getOrNull())
