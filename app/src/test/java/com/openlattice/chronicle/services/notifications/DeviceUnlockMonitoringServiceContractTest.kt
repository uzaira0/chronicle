package com.openlattice.chronicle.services.notifications

import android.Manifest
import android.app.Application
import android.app.Notification
import android.app.NotificationManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import com.openlattice.chronicle.R
import com.openlattice.chronicle.audit.AuditStores
import com.openlattice.chronicle.collection.core.ModuleResult
import com.openlattice.chronicle.collection.identification.TargetUserRouter
import com.openlattice.chronicle.collection.identification.UserIdentificationModuleHolder
import com.openlattice.chronicle.collection.state.MinimalPlayArtifactState
import com.openlattice.chronicle.collection.state.ResearchPersistenceGate
import com.openlattice.chronicle.collection.state.onPersistenceWorker
import com.openlattice.chronicle.preferences.EncryptedPrefsHelper
import com.openlattice.chronicle.preferences.EnrollmentSettings
import com.openlattice.chronicle.preferences.PARTICIPANT_ID
import com.openlattice.chronicle.receivers.lifecycle.DeviceLifecycleReceiver
import com.openlattice.chronicle.receivers.lifecycle.NotificationDismissedReceiver
import com.openlattice.chronicle.receivers.lifecycle.UnlockDeviceReceiver
import com.openlattice.chronicle.storage.ChronicleDb
import com.openlattice.chronicle.utils.Utils
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.android.controller.ServiceController
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowSystemClock
import org.robolectric.util.ReflectionHelpers
import java.time.Duration
import java.util.concurrent.ExecutorService
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [34])
class DeviceUnlockMonitoringServiceContractTest {
    private val app = ApplicationProvider.getApplicationContext<Application>()
    private val manager = app.getSystemService(NotificationManager::class.java)
    private val previousUser = "previous-user"

    @Before fun setUp() = onPersistenceWorker {
        AuditStores.install(app, enrolled = true)
        MinimalPlayArtifactState.markBoundaryApplied(app)
        MinimalPlayArtifactState.markPolicyCompatible(app)
        ResearchPersistenceGate.resetAfterLocalStoreRecovery()
        ResearchPersistenceGate.initialize(app)
        Utils.createNotificationChannel(app)
        assertTrue(TargetUserRouter.setTargetUser(app, previousUser) is ModuleResult.Ok)
        ShadowSystemClock.advanceBy(Duration.ofMillis(1))
    }

    @After fun tearDown() = onPersistenceWorker {
        ReflectionHelpers.setStaticField(UserIdentificationModuleHolder::class.java, "instance", null)
        ChronicleDb.getInstance(app).close()
        ReflectionHelpers.setStaticField(ChronicleDb::class.java, "INSTANCE", null)
        EncryptedPrefsHelper::class.java.getDeclaredField("instance").apply { isAccessible = true }
            .set(EncryptedPrefsHelper, null)
        ResearchPersistenceGate.resetAfterLocalStoreRecovery()
    }

    @Test
    fun foregroundNotificationCreatesAndUsesTheCanonicalSilentChannel() {
        manager.deleteNotificationChannel(UNLOCK_MONITORING_CHANNEL_ID)
        val controller = Robolectric.buildService(DeviceUnlockMonitoringService::class.java).create()
        try {
            val notification = requireNotNull(shadowOf(controller.get()).lastForegroundNotification)
            assertEquals(UNLOCK_MONITORING_CHANNEL_ID, notification.channelId)
            assertEquals(NotificationManager.IMPORTANCE_LOW, manager.getNotificationChannel(notification.channelId).importance)
            assertEquals(app.getString(R.string.interactivity_monitoring_notification_title),
                notification.extras.getCharSequence(Notification.EXTRA_TITLE).toString())
            assertTrue(notification.flags and Notification.FLAG_ONGOING_EVENT != 0)
        } finally { controller.destroy() }
    }

    @Test
    fun manifestUnlockReceiverIsNotExported() {
        val receiver = app.packageManager.getReceiverInfo(ComponentName(app, UnlockDeviceReceiver::class.java),
            PackageManager.ComponentInfoFlags.of(0))
        assertFalse(receiver.exported)
    }

    @Test
    fun rebootIdentificationBroadcastHasTheCorrectActionAndExplicitPackage() {
        val controller = startForReboot()
        try {
            val action = app.getString(R.string.action_identify_after_reboot)
            val broadcast = shadowOf(app).broadcastIntents.single { it.action == action }
            assertEquals(app.packageName, broadcast.`package`)
            assertEquals(4, monitoringReceivers().size)
        } finally { controller.destroy() }
        assertTrue(monitoringReceivers().isEmpty())
    }

    @Test
    fun receiversAndRebootPromptWaitForAuthorization() {
        shadowOf(app).denyPermissions(Manifest.permission.POST_NOTIFICATIONS)
        val controller = startForReboot()
        try {
            assertTrue(shadowOf(controller.get()).isStoppedBySelf)
            assertTrue(monitoringReceivers().isEmpty())
            assertTrue(shadowOf(app).broadcastIntents.none { it.action == app.getString(R.string.action_identify_after_reboot) })
        } finally { controller.destroy() }
    }

    @Test
    fun identificationRequiresActiveEnrollmentLocalOptInAndNotificationDelivery() = onPersistenceWorker {
        assertTrue(userIdentificationMayRun(app))
        manager.deleteNotificationChannel(IDENTIFY_USER_CHANNEL_ID)
        assertFalse(userIdentificationMayRun(app))
        Utils.createNotificationChannel(app)
        assertTrue(userIdentificationMayRun(app))
        val prefs = EncryptedPrefsHelper.getEncryptedPrefs(app)
        prefs.edit().putBoolean(app.getString(R.string.identify_user), false).commit()
        assertFalse(userIdentificationMayRun(app))
        prefs.edit().putBoolean(app.getString(R.string.identify_user), true).commit()
        shadowOf(app).denyPermissions(Manifest.permission.POST_NOTIFICATIONS)
        assertFalse(userIdentificationMayRun(app))
        shadowOf(app).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
        shadowOf(manager).setNotificationsEnabled(false)
        assertFalse(userIdentificationMayRun(app))
        shadowOf(manager).setNotificationsEnabled(true)
        assertTrue(userIdentificationMayRun(app))
        ResearchPersistenceGate.stop { prefs.edit().remove(PARTICIPANT_ID).commit() }
        assertFalse(userIdentificationMayRun(app))
    }

    @Test
    fun postedIdentifyPromptResetsTheUserInEveryBuild() {
        receiveUnlock()
        val notification = shadowOf(manager).allNotifications.single()
        assertEquals(IDENTIFY_USER_CHANNEL_ID, notification.channelId)
        assertEquals(app.getString(R.string.on_wake_notification_title),
            notification.extras.getCharSequence(Notification.EXTRA_TITLE).toString())
        assertEquals(app.getString(R.string.on_wake_notification_message),
            notification.extras.getCharSequence(Notification.EXTRA_BIG_TEXT).toString())
        assertEquals(app.getString(R.string.user_unassigned), EnrollmentSettings(app).getCurrentUser())
    }

    @Test
    fun suppressedIdentifyPromptKeepsThePreviousUser() {
        shadowOf(app).denyPermissions(Manifest.permission.POST_NOTIFICATIONS)
        receiveUnlock()
        assertTrue(shadowOf(manager).allNotifications.isEmpty())
        assertEquals(previousUser, EnrollmentSettings(app).getCurrentUser())
    }

    @Test
    fun backgroundStickyRestartDefersInsteadOfCrashing() {
        val controller = Robolectric.buildService(DeviceUnlockMonitoringService::class.java)
        val service = controller.get()
        shadowOf(service).setThrowInStartForeground(android.app.ForegroundServiceStartNotAllowedException("Test refusal"))
        try {
            controller.create()
            assertTrue(UnlockMonitoringRuntimeStatus.isDeferred(app))
            assertTrue(shadowOf(service).isStoppedBySelf)
            assertTrue(monitoringReceivers().isEmpty())
        } finally { controller.destroy() }
    }

    private fun monitoringReceivers() = shadowOf(app).registeredReceivers.filter {
        it.broadcastReceiver is UnlockDeviceReceiver ||
            it.broadcastReceiver is NotificationDismissedReceiver ||
            it.broadcastReceiver is DeviceLifecycleReceiver
    }

    private fun receiveUnlock() = onPersistenceWorker {
        ReflectionHelpers.callInstanceMethod<Unit>(UnlockDeviceReceiver(), "handleReceive",
            ReflectionHelpers.ClassParameter.from(Context::class.java, app),
            ReflectionHelpers.ClassParameter.from(Intent::class.java, Intent(Intent.ACTION_USER_PRESENT)))
    }

    private fun startForReboot(): ServiceController<DeviceUnlockMonitoringService> {
        assertTrue(DeviceUnlockMonitoringService.startAuthorizedService(app, restartOnBoot = true))
        val intent = requireNotNull(shadowOf(app).nextStartedService)
        val controller = Robolectric.buildService(DeviceUnlockMonitoringService::class.java).create()
        try {
            controller.get().onStartCommand(intent, 0, 1)
            ReflectionHelpers.getStaticField<ExecutorService>(DeviceUnlockMonitoringService::class.java, "AUTHORIZATION_EXECUTOR")
                .submit {}.get(10, TimeUnit.SECONDS)
            shadowOf(Looper.getMainLooper()).idle()
            ReflectionHelpers.getStaticField<ExecutorService>(UnlockDeviceReceiver::class.java, "IO_EXECUTOR")
                .submit {}.get(10, TimeUnit.SECONDS)
            return controller
        } catch (error: Throwable) {
            controller.destroy()
            throw error
        }
    }
}
