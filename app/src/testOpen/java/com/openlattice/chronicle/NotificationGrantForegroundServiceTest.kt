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
 * allowed, and keeps it hidden after the participant allows them. A participant enrolls (the sensor and unlock monitor
 * services start), then allows notifications in Chronicle's own dialog: the running services must be promoted again
 * so their ongoing notifications appear, not only after a module is switched off and on.
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

    @Test fun allowingNotificationsInTheAppsDialogPromotesTheRunningServicesAgain() {
        assertTrue(BuildConfig.ALLOW_RESTRICTED_RESEARCH_PERMISSIONS)
        AuditStores.install(context, enrolled = true, manifestOverrides = mapOf(
            CollectionModuleId.SENSOR_ACCELEROMETER to CollectionModuleSetting(enabled = true)))
        shadowOf(app).denyPermissions(Manifest.permission.POST_NOTIFICATIONS)
        EnrollmentSettings(context).toggleBatteryOptimizationDialog(false)
        EnrollmentSettings(context).toggleHibernationExemptionDialog(false)

        controller = Robolectric.buildActivity(MainActivity::class.java).setup()
        val activity = controller!!.get()
        // Enrollment's landing starts the services while notifications are still not allowed.
        awaitStarts(HardwareSensorService::class.java, DeviceUnlockMonitoringService::class.java)
        awaitDashboardUi { activity.supportFragmentManager.findFragmentByTag(NotificationRationaleDialog.TAG) != null }
        awaitDashboardUi { true }
        drainStarts()
        started.clear()

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

        awaitStarts(HardwareSensorService::class.java, DeviceUnlockMonitoringService::class.java)
    }
}
