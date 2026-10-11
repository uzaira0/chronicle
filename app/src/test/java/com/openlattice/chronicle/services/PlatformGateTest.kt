package com.openlattice.chronicle.services

import android.Manifest
import android.app.AlarmManager
import android.app.Application
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationChannelGroup
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.ComponentName
import android.content.ContextWrapper
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.IBinder
import android.os.PowerManager
import android.provider.Settings
import android.os.Build
import androidx.test.core.app.ApplicationProvider
import androidx.work.Configuration
import androidx.work.WorkManager
import com.openlattice.chronicle.BuildConfig
import com.openlattice.chronicle.R
import com.openlattice.chronicle.batteryOptimizationSettingsIntent
import com.openlattice.chronicle.collection.state.onPersistenceWorker
import com.openlattice.chronicle.services.notifications.CHANNEL_ID
import com.openlattice.chronicle.services.notifications.ExactAlarmPermissionReceiver
import com.openlattice.chronicle.services.notifications.NOTIFICATIONS_WORK_NAME
import com.openlattice.chronicle.services.notifications.NotificationPermissionActivity
import com.openlattice.chronicle.services.notifications.postNotificationIfPermitted
import com.openlattice.chronicle.services.notifications.scheduleReminderAlarm
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements
import org.robolectric.shadows.ShadowAlarmManager
import org.robolectric.util.ReflectionHelpers
import java.util.concurrent.Executor
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class PlatformGateTest {
    private val app = ApplicationProvider.getApplicationContext<Application>()
    private val manager = requireNotNull(app.getSystemService(NotificationManager::class.java)) { "notification manager available" }
    private val alarmManager = requireNotNull(app.getSystemService(AlarmManager::class.java)) { "alarm manager available" }
    private val channelId = "guard-test"

    private fun notification(): Notification {
        manager.createNotificationChannel(NotificationChannel(channelId, "Guard test", NotificationManager.IMPORTANCE_LOW))
        return Notification.Builder(app, channelId).setSmallIcon(android.R.drawable.ic_dialog_info).build()
    }

    @Test @Config(sdk = [32, Build.VERSION_CODES.TIRAMISU, 36])
    fun notificationPostingChecksRuntimeAndAppState() {
        val notification = notification()
        shadowOf(app).denyPermissions(Manifest.permission.POST_NOTIFICATIONS)
        // Robolectric does not model Android's silent drop; assert the guard's decision.
        assertEquals(Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU, postNotificationIfPermitted(app, 1, notification))
        shadowOf(app).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
        assertTrue(postNotificationIfPermitted(app, 1, notification))
        shadowOf(manager).setNotificationsEnabled(false)
        assertFalse(postNotificationIfPermitted(app, 1, notification))
    }

    @Test @Config(sdk = [26, Build.VERSION_CODES.TIRAMISU, 36])
    fun missingAndBlockedChannelsCannotReportPosted() {
        shadowOf(app).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
        val notification = Notification.Builder(app, channelId).setSmallIcon(android.R.drawable.ic_dialog_info).build()
        assertFalse(postNotificationIfPermitted(app, 1, notification))
        manager.createNotificationChannel(NotificationChannel(channelId, "Blocked", NotificationManager.IMPORTANCE_NONE))
        assertFalse(postNotificationIfPermitted(app, 1, notification))
        assertEquals(0, shadowOf(manager).size())
    }

    @Test @Config(sdk = [26, Build.VERSION_CODES.TIRAMISU, 36])
    fun postingCreatesCanonicalChannelsEvenBeforeNotificationAccessIsGranted() {
        assertNull(manager.getNotificationChannel(CHANNEL_ID))
        val notification = Notification.Builder(app, CHANNEL_ID).setSmallIcon(android.R.drawable.ic_dialog_info).build()
        shadowOf(app).denyPermissions(Manifest.permission.POST_NOTIFICATIONS)
        assertEquals(Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU, postNotificationIfPermitted(app, 1, notification))
        assertNotNull(manager.getNotificationChannel(CHANNEL_ID))
        shadowOf(app).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
        assertTrue(postNotificationIfPermitted(app, 1, notification))
        assertEquals(1, shadowOf(manager).size())
    }

    @Test @Config(sdk = [26, Build.VERSION_CODES.TIRAMISU, 36])
    fun channelRecreationPreservesBlockedCanonicalChannel() {
        shadowOf(app).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
        manager.createNotificationChannel(NotificationChannel(CHANNEL_ID, "Blocked", NotificationManager.IMPORTANCE_NONE))
        assertFalse(postNotificationIfPermitted(app, 1,
            Notification.Builder(app, CHANNEL_ID).setSmallIcon(android.R.drawable.ic_dialog_info).build()))
        assertEquals(NotificationManager.IMPORTANCE_NONE, manager.getNotificationChannel(CHANNEL_ID).importance)
        assertEquals(0, shadowOf(manager).size())
    }

    @Test @Config(sdk = [27, Build.VERSION_CODES.P, 36])
    fun blockedChannelGroupCannotReportPosted() {
        shadowOf(app).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
        val group = NotificationChannelGroup("group", "Group")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) ReflectionHelpers.setField(group, "mBlocked", true)
        manager.createNotificationChannelGroup(group)
        manager.createNotificationChannel(NotificationChannel(channelId, "Grouped", NotificationManager.IMPORTANCE_LOW).apply {
            this.group = group.id
        })
        assertEquals(Build.VERSION.SDK_INT < Build.VERSION_CODES.P, postNotificationIfPermitted(app, 1,
            Notification.Builder(app, channelId).setSmallIcon(android.R.drawable.ic_dialog_info).build()))
    }

    @Test @Config(sdk = [32, Build.VERSION_CODES.TIRAMISU])
    fun notificationRequestUsesRuntimeDialogOnlyOn33AndDenialKeepsScreenOpen() {
        shadowOf(app).denyPermissions(Manifest.permission.POST_NOTIFICATIONS)
        shadowOf(manager).setNotificationsEnabled(false)
        val controller = Robolectric.buildActivity(NotificationPermissionActivity::class.java).setup()
        val activity = controller.get()
        try {
            requireNotNull(activity.findViewById<android.widget.Button>(R.id.open_notification_settings_btn)) {
                "notification settings button available"
            }.performClick()
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                val request = requireNotNull(shadowOf(activity).lastRequestedPermission) { "notification permission requested" }
                assertArrayEquals(arrayOf(Manifest.permission.POST_NOTIFICATIONS), request.requestedPermissions)
                activity.onRequestPermissionsResult(request.requestCode, request.requestedPermissions,
                    intArrayOf(PackageManager.PERMISSION_DENIED))
                assertFalse(activity.isFinishing)
            } else {
                assertNull(shadowOf(activity).lastRequestedPermission)
                assertNotNull(shadowOf(activity).nextStartedActivityForResult)
            }
        } finally { controller.pause().stop().destroy() }
    }

    class TestService : Service() { override fun onBind(intent: Intent?): IBinder? = null }

    @Test @Config(sdk = [Build.VERSION_CODES.S, Build.VERSION_CODES.UPSIDE_DOWN_CAKE, 36])
    fun promotionReportsBackgroundRefusal() {
        val service = Robolectric.buildService(TestService::class.java).create().get()
        shadowOf(service).setThrowInStartForeground(android.app.ForegroundServiceStartNotAllowedException("Test restriction"))
        assertFalse(promoteForegroundServiceIfPermitted(service, 1, notification()))
    }

    @Test @Config(sdk = [Build.VERSION_CODES.P, 29, Build.VERSION_CODES.TIRAMISU, Build.VERSION_CODES.UPSIDE_DOWN_CAKE, 36])
    fun promotionUsesSpecialUseAndDoesNotRequireNotificationGrant() {
        shadowOf(app).denyPermissions(Manifest.permission.POST_NOTIFICATIONS)
        // Robolectric does not enforce manifest service types; inspect the successful promotion.
        val service = Robolectric.buildService(TestService::class.java).create().get()
        val notification = notification()
        assertTrue(promoteForegroundServiceIfPermitted(service, 1, notification))
        if (Build.VERSION.SDK_INT >= 29) {
            assertEquals(if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE else 0,
                service.foregroundServiceType)
        }
        assertEquals(1, shadowOf(service).lastForegroundNotificationId)
    }

    @Test @Config(sdk = [30, Build.VERSION_CODES.S, 36])
    fun serviceStartReportsPermissionAndBackgroundRefusal() {
        var calls = 0
        var refusal: RuntimeException? = null
        val context = object : ContextWrapper(app) {
            override fun startForegroundService(intent: Intent): ComponentName? {
                calls++
                refusal?.let { throw it }
                return intent.component
            }
        }
        val intent = Intent(app, TestService::class.java)
        assertTrue(startForegroundServiceIfPermitted(context, intent))
        refusal = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S)
            android.app.ForegroundServiceStartNotAllowedException("Test refusal") else IllegalStateException("Test refusal")
        // Robolectric cannot decide whether an OS background-start exemption is still live.
        assertFalse(startForegroundServiceIfPermitted(context, intent))
        refusal = SecurityException("Test permission refusal")
        assertFalse(startForegroundServiceIfPermitted(context, intent))
        assertEquals(3, calls)
    }

    @Test @Config(sdk = [Build.VERSION_CODES.P, Build.VERSION_CODES.UPSIDE_DOWN_CAKE])
    fun promotionReportsSecurityRefusal() {
        val service = Robolectric.buildService(TestService::class.java).create().get()
        shadowOf(service).setThrowInStartForeground(SecurityException("Test restriction"))
        assertFalse(promoteForegroundServiceIfPermitted(service, 1, notification()))
    }

    private fun pending(): PendingIntent = PendingIntent.getBroadcast(app, 1,
        Intent(app, ExactAlarmPermissionReceiver::class.java), PendingIntent.FLAG_IMMUTABLE)

    @Test @Config(sdk = [30, Build.VERSION_CODES.S, Build.VERSION_CODES.UPSIDE_DOWN_CAKE, 36])
    fun exactAlarmGrantSelectsExactOrInexactWithoutAssumingDelivery() {
        ShadowAlarmManager.setCanScheduleExactAlarms(false)
        scheduleReminderAlarm(app, System.currentTimeMillis() + 60_000, pending())
        val inexactAlarm = requireNotNull(shadowOf(alarmManager).peekNextScheduledAlarm()) { "alarm scheduled" }
        assertNotEquals(ShadowAlarmManager.WINDOW_EXACT, inexactAlarm.windowLengthMs)
        ShadowAlarmManager.setCanScheduleExactAlarms(true)
        scheduleReminderAlarm(app, System.currentTimeMillis() + 60_000, pending())
        val alarm = requireNotNull(shadowOf(alarmManager).peekNextScheduledAlarm()) { "alarm scheduled" }
        assertEquals(Build.VERSION.SDK_INT >= Build.VERSION_CODES.S, alarm.windowLengthMs == ShadowAlarmManager.WINDOW_EXACT)
        assertTrue(alarm.isAllowWhileIdle)
    }

    @Test @Config(sdk = [26, 36])
    fun unavailableAlarmProviderSkipsArming() {
        val context = object : ContextWrapper(app) {
            override fun getSystemService(name: String): Any? =
                if (name == android.content.Context.ALARM_SERVICE) null else super.getSystemService(name)
        }
        assertFalse(scheduleReminderAlarm(context, System.currentTimeMillis() + 60_000, pending()))
        assertTrue(shadowOf(alarmManager).scheduledAlarms.isEmpty())
    }

    @Implements(AlarmManager::class)
    class RevokedAlarmManager : ShadowAlarmManager() {
        @Implementation override fun setExactAndAllowWhileIdle(type: Int, trigger: Long, pending: PendingIntent) {
            throw SecurityException("Access changed after preflight")
        }
    }

    @Test @Config(sdk = [Build.VERSION_CODES.S], shadows = [RevokedAlarmManager::class])
    fun revocationBetweenCheckAndCallFallsBackToInexact() {
        ShadowAlarmManager.setCanScheduleExactAlarms(true)
        scheduleReminderAlarm(app, System.currentTimeMillis() + 60_000, pending())
        val alarm = requireNotNull(shadowOf(alarmManager).peekNextScheduledAlarm()) { "alarm scheduled" }
        assertNotEquals(ShadowAlarmManager.WINDOW_EXACT, alarm.windowLengthMs)
    }

    @Test @Config(sdk = [30, Build.VERSION_CODES.S, 36])
    fun exactAlarmGrantBroadcastRechecksAccessBeforeSchedulingWork() = onPersistenceWorker {
        if (!WorkManager.isInitialized()) WorkManager.initialize(app, Configuration.Builder().setExecutor(Executor { }).build())
        val workManager = WorkManager.getInstance(app)
        workManager.cancelUniqueWork(NOTIFICATIONS_WORK_NAME).result.get(5, TimeUnit.SECONDS)
        workManager.pruneWork().result.get(5, TimeUnit.SECONDS)
        val receiver = ExactAlarmPermissionReceiver()
        val intent = Intent(AlarmManager.ACTION_SCHEDULE_EXACT_ALARM_PERMISSION_STATE_CHANGED)
        ShadowAlarmManager.setCanScheduleExactAlarms(false)
        receiver.onReceive(app, intent)
        assertTrue(workManager.getWorkInfosForUniqueWork(NOTIFICATIONS_WORK_NAME).get(5, TimeUnit.SECONDS).filter { !it.state.isFinished }.isEmpty())
        ShadowAlarmManager.setCanScheduleExactAlarms(true)
        receiver.onReceive(app, intent)
        assertEquals(Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && BuildConfig.ALLOW_PARTICIPANT_FORM_REMINDERS,
            workManager.getWorkInfosForUniqueWork(NOTIFICATIONS_WORK_NAME).get(5, TimeUnit.SECONDS).filter { !it.state.isFinished }.isNotEmpty())
    }

    @Test @Config(sdk = [26, 36])
    fun batteryRequestChecksExistingExemptionAndDistributionPermission() {
        val power = requireNotNull(app.getSystemService(PowerManager::class.java)) { "power manager available" }
        shadowOf(power).setIgnoringBatteryOptimizations(app.packageName, false)
        shadowOf(app).denyPermissions(Manifest.permission.REQUEST_IGNORE_BATTERY_OPTIMIZATIONS)
        assertEquals(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS, batteryOptimizationSettingsIntent(app).action)
        shadowOf(app).grantPermissions(Manifest.permission.REQUEST_IGNORE_BATTERY_OPTIMIZATIONS)
        assertEquals(if (BuildConfig.DIRECT_BATTERY_EXEMPTION_REQUEST) Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS
            else Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS, batteryOptimizationSettingsIntent(app).action)
        shadowOf(power).setIgnoringBatteryOptimizations(app.packageName, true)
        assertEquals(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS, batteryOptimizationSettingsIntent(app).action)
    }
}
