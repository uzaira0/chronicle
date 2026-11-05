package com.openlattice.chronicle

import android.Manifest
import android.app.Activity
import android.app.Application
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import androidx.activity.result.contract.ActivityResultContracts
import androidx.test.core.app.ApplicationProvider
import com.openlattice.chronicle.audit.AuditStores
import com.openlattice.chronicle.collection.CollectionModuleId
import com.openlattice.chronicle.collection.CollectionModuleSetting
import com.openlattice.chronicle.preferences.EnrollmentSettings
import com.openlattice.chronicle.services.notifications.DeviceUnlockMonitoringService
import com.openlattice.chronicle.services.sensors.HardwareSensorService
import com.openlattice.chronicle.ui.awaitDashboardUi
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.android.controller.ActivityController
import org.robolectric.annotation.Config

/**
 * Android 13+ hides the ongoing notification of a foreground service that was promoted while notifications were not
 * allowed, and keeps it hidden after the participant allows them. A participant enrolls (the sensor service starts; the
 * unlock monitor waits, since its prompt needs notifications), then allows notifications in Chronicle's own dialog:
 * the sensor service must be promoted again so its ongoing notification appears, and the unlock monitor starts.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [36])
class NotificationGrantForegroundServiceTest {
    @get:Rule val workManagerRule = WorkManagerRule()
    private val app = ApplicationProvider.getApplicationContext<Application>()
    private val context: Context = app
    private var controller: ActivityController<MainActivity>? = null
    private val started = mutableListOf<String>()

    @After fun tearDown() {
        controller?.pause()?.stop()?.destroy()
    }

    private fun drainStarts() {
        generateSequence { shadowOf(app).nextStartedService }.mapNotNullTo(started) { it.component?.className }
    }

    private fun awaitStarts(vararg services: Class<*>) = awaitDashboardUi {
        drainStarts()
        services.all { it.name in started }
    }

    /** Enrolled with the accelerometer, notifications not allowed; returns once the landing has started the services. */
    private fun launchWithNotificationsDenied(): MainActivity {
        assertTrue(BuildConfig.ALLOW_RESTRICTED_RESEARCH_PERMISSIONS)
        AuditStores.install(context, enrolled = true, manifestOverrides = mapOf(
            CollectionModuleId.SENSOR_ACCELEROMETER to CollectionModuleSetting(enabled = true)))
        shadowOf(app).denyPermissions(Manifest.permission.POST_NOTIFICATIONS)
        EnrollmentSettings(context).toggleBatteryOptimizationDialog(false)
        EnrollmentSettings(context).toggleHibernationExemptionDialog(false)

        controller = Robolectric.buildActivity(MainActivity::class.java).setup()
        val activity = controller!!.get()
        // Enrollment's landing starts the sensor service while notifications are still not allowed; the unlock monitor
        // runs only for user identification, whose prompt cannot be shown yet.
        awaitStarts(HardwareSensorService::class.java)
        awaitDashboardUi { activity.supportFragmentManager.findFragmentByTag(NotificationRationaleDialog.TAG) != null }
        awaitDashboardUi { true }
        drainStarts()
        assertFalse(started.toString(), DeviceUnlockMonitoringService::class.java.name in started)
        started.clear()
        return activity
    }

    /** Each service started exactly once more: the grant starts them, later resumes do not. */
    private fun assertEachServiceStartedOnceMore() {
        awaitStarts(HardwareSensorService::class.java, DeviceUnlockMonitoringService::class.java)
        controller!!.pause().resume()
        // Let any start a resume would issue (they run off the main thread) arrive before counting.
        repeat(50) {
            awaitDashboardUi { true }
            drainStarts()
            Thread.sleep(20)
        }
        assertEquals(listOf(DeviceUnlockMonitoringService::class.java.name, HardwareSensorService::class.java.name),
            started.sorted())
    }

    @Test fun allowingNotificationsInTheAppsDialogPromotesTheRunningServicesAgain() {
        val activity = launchWithNotificationsDenied()

        // Continue on Chronicle's explanation, then Allow in Android's dialog, which pauses and resumes the dashboard.
        activity.requestNotificationPermission()
        val request = shadowOf(activity).lastRequestedPermission
        assertEquals(listOf(Manifest.permission.POST_NOTIFICATIONS), request.requestedPermissions.toList())
        controller!!.pause()
        shadowOf(app).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
        activity.activityResultRegistry.dispatchResult(request.requestCode, Activity.RESULT_OK, Intent()
            .putExtra(ActivityResultContracts.RequestMultiplePermissions.EXTRA_PERMISSIONS, request.requestedPermissions)
            .putExtra(ActivityResultContracts.RequestMultiplePermissions.EXTRA_PERMISSION_GRANT_RESULTS,
                intArrayOf(PackageManager.PERMISSION_GRANTED)))
        controller!!.resume()

        assertEachServiceStartedOnceMore()
    }

    @Test fun allowingNotificationsInAndroidSettingsPromotesTheRunningServicesOnReturn() {
        launchWithNotificationsDenied()

        // "Not now" in Chronicle, then Android Settings > Apps > Chronicle > Notifications: Chronicle is stopped
        // meanwhile and no permission callback reaches it; the grant is seen when the participant comes back.
        controller!!.pause().stop()
        shadowOf(app).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
        controller!!.start().resume()

        assertEachServiceStartedOnceMore()
    }
}
