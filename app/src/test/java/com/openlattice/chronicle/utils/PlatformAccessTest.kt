package com.openlattice.chronicle.utils

import android.app.AppOpsManager
import android.app.Application
import android.app.NotificationManager
import android.app.usage.UsageStatsManager
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ApplicationInfo
import android.content.pm.PackageInfo
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.UserManager
import android.provider.Settings
import androidx.test.core.app.ApplicationProvider
import com.openlattice.chronicle.MainActivity
import com.openlattice.chronicle.PermissionActivity
import com.openlattice.chronicle.BuildConfig
import com.openlattice.chronicle.R
import com.openlattice.chronicle.services.notifications.DeviceUnlockMonitoringService
import com.openlattice.chronicle.services.notifications.NOTIFICATION_DELETED_ACTION
import com.openlattice.chronicle.services.notifications.hasNotificationListenerAccess
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowApplication
import org.robolectric.util.ReflectionHelpers

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class PlatformAccessTest {
    private val app = ApplicationProvider.getApplicationContext<Application>()
    private val userManager = requireNotNull(app.getSystemService(UserManager::class.java)) { "user manager available" }
    private val privateReceiverPermission = app.packageName + ".DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION"

    private fun usageAccess(mode: Int) = shadowOf(requireNotNull(app.getSystemService(AppOpsManager::class.java)) { "app ops manager available" })
        .setMode(AppOpsManager.OPSTR_GET_USAGE_STATS, app.applicationInfo.uid, app.packageName, mode)

    @Test @Config(sdk = [26, 30, 36])
    fun usageReadsRequireAllowedAppOpRatherThanManifestPermission() {
        shadowOf(app).grantPermissions(android.Manifest.permission.PACKAGE_USAGE_STATS)
        usageAccess(AppOpsManager.MODE_IGNORED)
        assertFalse(UsageAccess.isGranted(app))
        assertNull(UsageAccess.queryEvents(app, 0, 1))
        assertNull(UsageAccess.queryUsageStats(app, UsageStatsManager.INTERVAL_BEST, 0, 1))
        usageAccess(AppOpsManager.MODE_ALLOWED)
        shadowOf(userManager).setUserUnlocked(true)
        assertTrue(UsageAccess.isGranted(app))
        assertNotNull(UsageAccess.queryEvents(app, 0, 1))
        assertNotNull(UsageAccess.queryUsageStats(app, UsageStatsManager.INTERVAL_BEST, 0, 1))
    }

    @Test @Config(sdk = [29, 30])
    fun lockedUserIsSkippedAtTheAndroid30Boundary() {
        usageAccess(AppOpsManager.MODE_ALLOWED)
        shadowOf(userManager).setUserUnlocked(false)
        // Robolectric returns usage data while locked; assert our documented preflight.
        if (Build.VERSION.SDK_INT >= 30) {
            assertNull(UsageAccess.queryEvents(app, 0, 1))
            assertNull(UsageAccess.queryUsageStats(app, UsageStatsManager.INTERVAL_BEST, 0, 1))
        } else {
            assertNotNull(UsageAccess.queryEvents(app, 0, 1))
        }
    }

    @Test @Config(sdk = [26, 36])
    fun accessRevokedDuringQueryIsSkippedNotEmpty() {
        usageAccess(AppOpsManager.MODE_ALLOWED)
        shadowOf(userManager).setUserUnlocked(true)
        // The platform answers a denied query with empty data; revoke between preflight and query.
        val context = object : ContextWrapper(app) {
            override fun getSystemService(name: String): Any? {
                if (name == Context.USAGE_STATS_SERVICE) usageAccess(AppOpsManager.MODE_IGNORED)
                return super.getSystemService(name)
            }
        }
        assertNull(UsageAccess.queryEvents(context, 0, 1))
        usageAccess(AppOpsManager.MODE_ALLOWED)
        assertNull(UsageAccess.queryUsageStats(context, UsageStatsManager.INTERVAL_BEST, 0, 1))
    }

    @Test @Config(sdk = [26, 36])
    fun missingUsageProviderSkipsBothQueries() {
        usageAccess(AppOpsManager.MODE_ALLOWED)
        shadowOf(userManager).setUserUnlocked(true)
        val context = object : ContextWrapper(app) {
            override fun getSystemService(name: String): Any? =
                if (name == Context.USAGE_STATS_SERVICE) null else super.getSystemService(name)
        }
        assertNull(UsageAccess.queryEvents(context, 0, 1))
        assertNull(UsageAccess.queryUsageStats(context, UsageStatsManager.INTERVAL_BEST, 0, 1))
    }

    @Test @Config(sdk = [26, 36])
    fun usageSettingsResumeChecksCurrentAppOpBeforeAdvancing() {
        usageAccess(AppOpsManager.MODE_IGNORED)
        val controller = Robolectric.buildActivity(PermissionActivity::class.java).setup()
        val activity = controller.get()
        try {
            assertFalse(activity.isFinishing)
            usageAccess(AppOpsManager.MODE_ALLOWED)
            controller.pause().resume()
            assertTrue(activity.isFinishing)
            val intent = requireNotNull(shadowOf(activity).nextStartedActivity) { "main activity started" }
            assertEquals(ComponentName(app, MainActivity::class.java), intent.component)
        } finally { controller.pause().stop().destroy() }
    }

    @Test @Config(sdk = [26, 27, 36])
    fun notificationListenerCheckTracksGrantAndRevocation() {
        val component = ComponentName(app.packageName, "com.openlattice.chronicle.services.notifications.NotificationListener")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            val manager = shadowOf(requireNotNull(app.getSystemService(NotificationManager::class.java)) { "notification manager available" })
            manager.setNotificationListenerAccessGranted(component, false)
            assertFalse(hasNotificationListenerAccess(app))
            manager.setNotificationListenerAccessGranted(component, true)
            assertEquals(BuildConfig.ALLOW_RESTRICTED_RESEARCH_PERMISSIONS, hasNotificationListenerAccess(app))
            manager.setNotificationListenerAccessGranted(component, false)
        } else {
            val key = "enabled_notification_listeners"
            Settings.Secure.putString(app.contentResolver, key, "")
            assertFalse(hasNotificationListenerAccess(app))
            Settings.Secure.putString(app.contentResolver, key, component.flattenToString())
            assertEquals(BuildConfig.ALLOW_RESTRICTED_RESEARCH_PERMISSIONS, hasNotificationListenerAccess(app))
            Settings.Secure.putString(app.contentResolver, key, "")
        }
        assertFalse(hasNotificationListenerAccess(app))
    }

    private fun assertReceiverExport(registration: ShadowApplication.Wrapper, exported: Boolean) {
        val modern = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU
        val flags = if (!modern) 0 else if (exported) Context.RECEIVER_EXPORTED else Context.RECEIVER_NOT_EXPORTED
        assertEquals(flags, registration.flags)
        if (exported || modern) assertNull(registration.broadcastPermission) else assertNotNull(registration.broadcastPermission)
    }

    @Test @Config(sdk = [26, 32, Build.VERSION_CODES.TIRAMISU, Build.VERSION_CODES.UPSIDE_DOWN_CAKE, 36])
    fun internalReceiverUsesExportFlagsAndStillReceivesOwnBroadcast() {
        shadowOf(app).grantPermissions(privateReceiverPermission)
        var delivered = false
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) { delivered = true }
        }
        val action = "${app.packageName}.GUARD_TEST"
        registerPrivateReceiver(app, receiver, IntentFilter(action))
        try {
            val registration = shadowOf(app).registeredReceivers.single { it.broadcastReceiver === receiver }
            // Robolectric does not enforce sender UID isolation; assert flags and permission.
            assertReceiverExport(registration, exported = false)
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
                assertEquals(privateReceiverPermission, registration.broadcastPermission)
            }
            app.sendBroadcast(Intent(action).setPackage(app.packageName))
            shadowOf(android.os.Looper.getMainLooper()).idle()
            assertTrue(delivered)
        } finally { app.unregisterReceiver(receiver) }
    }

    @Test @Config(sdk = [26, 32])
    fun privateReceiverRejectsMissingSignaturePermission() {
        shadowOf(app).denyPermissions(privateReceiverPermission)
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) = Unit
        }
        assertThrows(SecurityException::class.java) {
            registerPrivateReceiver(app, receiver, IntentFilter("${app.packageName}.GUARD_TEST"))
        }
        assertTrue(shadowOf(app).registeredReceivers.none { it.broadcastReceiver === receiver })
    }

    @Test @Config(sdk = [26, 32, Build.VERSION_CODES.TIRAMISU, 36])
    fun systemReceiverIsExportedWithoutASenderPermissionAndPreservesItsHandler() {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) = Unit
        }
        val handler = Handler(Looper.getMainLooper())
        registerSystemBroadcastReceiver(app, receiver, IntentFilter(Intent.ACTION_USER_PRESENT), handler)
        try {
            val registration = shadowOf(app).registeredReceivers.single { it.broadcastReceiver === receiver }
            assertReceiverExport(registration, exported = true)
            assertSame(handler, registration.scheduler)
        } finally { app.unregisterReceiver(receiver) }
    }

    @Test @Config(sdk = [26, 32, Build.VERSION_CODES.TIRAMISU, 36])
    fun unlockServiceSeparatesSystemAndPrivateActionsAndUnregistersBothInstances() {
        shadowOf(app).grantPermissions(privateReceiverPermission)
        // Exercise the registration body without starting the unrelated enrollment executor.
        val service = Robolectric.buildService(DeviceUnlockMonitoringService::class.java).get()
        val before = shadowOf(app).registeredReceivers
        ReflectionHelpers.callInstanceMethod<Unit>(service, "registerReceivers")
        ReflectionHelpers.setField(service, "receiversRegistered", true)
        val registrations = shadowOf(app).registeredReceivers.filterNot { it in before }
        try {
            assertEquals(4, registrations.size)
            val unlock = registrations.single { it.intentFilter.hasAction(Intent.ACTION_USER_PRESENT) }
            assertTrue(unlock.intentFilter.hasAction(Intent.ACTION_SCREEN_ON))
            assertEquals(2, unlock.intentFilter.countActions())
            assertReceiverExport(unlock, exported = true)
            val identify = registrations.single { it.intentFilter.hasAction(app.getString(R.string.action_identify_after_reboot)) }
            assertEquals(1, identify.intentFilter.countActions())
            assertReceiverExport(identify, exported = false)
            assertNotSame(unlock.broadcastReceiver, identify.broadcastReceiver)
            assertReceiverExport(registrations.single { it.intentFilter.hasAction(NOTIFICATION_DELETED_ACTION) }, exported = false)
            assertReceiverExport(registrations.single { it.intentFilter.hasAction(Intent.ACTION_POWER_CONNECTED) }, exported = true)
        } finally { service.onDestroy() }
        assertTrue(registrations.none { it in shadowOf(app).registeredReceivers })
    }

    @Test @Config(sdk = [29, 30, 36])
    fun packageLabelUsesVisibleLabelAndFallsBackWhenUnavailable() {
        val packageName = "com.example.visible"
        val info = PackageInfo().apply {
            this.packageName = packageName
            applicationInfo = ApplicationInfo().apply {
                this.packageName = packageName
                nonLocalizedLabel = "Visible app"
            }
        }
        shadowOf(app.packageManager).installPackage(info)
        assertEquals("Visible app", PackageLabels.getAppFullName(app, packageName))
        shadowOf(app.packageManager).removePackage(packageName)
        // Robolectric does not apply package visibility; simulate the filtered lookup result.
        assertEquals(packageName, PackageLabels.getAppFullName(app, packageName))
    }

    @Test @Config(sdk = [30, Build.VERSION_CODES.S, 36])
    fun ordinaryPendingIntentFlagsRemainImmutableAcrossAndroid12() {
        val base = android.app.PendingIntent.FLAG_UPDATE_CURRENT
        assertEquals(base or android.app.PendingIntent.FLAG_IMMUTABLE, Utils.getPendingIntentMutabilityFlag(base))
    }
}
