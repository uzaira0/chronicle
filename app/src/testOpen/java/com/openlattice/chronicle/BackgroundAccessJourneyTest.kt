package com.openlattice.chronicle

import android.app.ActivityManager
import android.app.Application
import android.content.ComponentName
import android.content.Intent
import android.content.IntentFilter
import android.net.ConnectivityManager
import android.os.Looper
import android.os.PowerManager
import android.provider.Settings
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.DialogFragment
import androidx.lifecycle.lifecycleScope
import androidx.test.core.app.ApplicationProvider
import com.openlattice.chronicle.ui.awaitDashboardUi
import com.openlattice.chronicle.audit.AuditStores
import kotlinx.coroutines.Job
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.android.controller.ActivityController
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowBuild

/**
 * On an enrolled Open phone the dashboard walks the participant through every background restriction,
 * one dialog at a time: each Settings button opens a destination the phone can resolve (or the general Settings screen),
 * a launch failure never crashes, Cancel moves on, a rotation keeps exactly one dialog, and a fixed restriction is not
 * asked again.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [34])
class BackgroundAccessJourneyTest {
    @get:Rule val workManagerRule = WorkManagerRule()
    private val app = ApplicationProvider.getApplicationContext<Application>()
    private val packageManager = shadowOf(app.packageManager)

    private fun ActivityController<MainActivity>.awaitDialog(tag: String) = awaitDashboardUi {
        get().supportFragmentManager.findFragmentByTag(tag)?.let { (it as DialogFragment).dialog?.isShowing } == true
    }

    /** The one exemption dialog on screen, by tag; fails if none or more than one is open. */
    private fun ActivityController<MainActivity>.dialog(): Pair<String, AlertDialog> {
        val open = get().supportFragmentManager.fragments.filterIsInstance<DialogFragment>().filter { it.tag in TAGS }
        assertEquals(open.map { it.tag }.toString(), 1, open.size)
        return open.single().tag!! to (open.single().dialog as AlertDialog)
    }

    private fun ActivityController<MainActivity>.noDialog() = assertEquals(emptyList<String>(),
        get().supportFragmentManager.fragments.filterIsInstance<DialogFragment>().mapNotNull { it.tag }.filter { it in TAGS })

    /** Leaves for another screen and comes back, as a Settings page or a permission prompt does. */
    private fun ActivityController<MainActivity>.returnFromSettings() { pause().stop(); start().resume() }

    private fun resolvable(intent: Intent, owner: String = "com.android.settings") {
        val target = ComponentName(owner, "$owner.Target")
        packageManager.addActivityIfNotPresent(target)
        packageManager.addIntentFilterForActivity(target, IntentFilter(intent.action).apply { addCategory(Intent.CATEGORY_DEFAULT) })
    }

    /** Presses [button] of the open dialog and returns the one screen that press started. */
    private fun ActivityController<MainActivity>.press(button: Int): Intent? {
        generateSequence { shadowOf(get()).nextStartedActivity }.toList()
        dialog().second.getButton(button).performClick()
        shadowOf(Looper.getMainLooper()).idle()
        return shadowOf(get()).nextStartedActivity.also { assertNull("one screen per press", shadowOf(get()).nextStartedActivity) }
    }

    @Test fun everyRestrictionIsOfferedInTurnAndEachSettingsButtonLandsOnAResolvableScreen() {
        ShadowBuild.setManufacturer("Xiaomi")
        AuditStores.install(app, enrolled = true)
        shadowOf(checkNotNull(app.getSystemService(ActivityManager::class.java))).setBackgroundRestricted(true)
        shadowOf(app.getSystemService(ConnectivityManager::class.java))
            .setRestrictBackgroundStatus(ConnectivityManager.RESTRICT_BACKGROUND_STATUS_ENABLED)
        val batterySettings = Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
        resolvable(batterySettings)
        // Xiaomi's autostart screen is absent on this phone; its hidden-apps screen is present.
        val hiddenApps = Intent().setClassName("com.miui.powerkeeper", "com.miui.powerkeeper.ui.HiddenAppsConfigActivity")
        packageManager.addActivityIfNotPresent(checkNotNull(hiddenApps.component))
        val controller = Robolectric.buildActivity(MainActivity::class.java).setup()
        try {
            shadowOf(Looper.getMainLooper()).idle()
            // 1. Battery: the Open build opens the system exemption list.
            controller.awaitDialog("batteryExemption")
            assertEquals("batteryExemption", controller.dialog().first)
            assertEquals(batterySettings.action, controller.press(AlertDialog.BUTTON_POSITIVE)?.action)
            shadowOf(app.getSystemService(PowerManager::class.java)).setIgnoringBatteryOptimizations(app.packageName, true)
            controller.returnFromSettings()

            // 2. Hibernation: rotation keeps one dialog; Cancel moves on.
            controller.awaitDialog("hibernationExemption")
            assertEquals("hibernationExemption", controller.dialog().first)
            controller.recreate()
            shadowOf(Looper.getMainLooper()).idle()
            controller.awaitDialog("hibernationExemption")
            assertEquals("hibernationExemption", controller.dialog().first)
            assertNull(controller.press(AlertDialog.BUTTON_NEGATIVE))
            controller.returnFromSettings()

            // 3. Background data: its per-app screen is absent, the general Settings screen opens instead.
            controller.awaitDialog("backgroundDataRestricted")
            assertEquals("backgroundDataRestricted", controller.dialog().first)
            assertEquals(Settings.ACTION_SETTINGS, controller.press(AlertDialog.BUTTON_POSITIVE)?.action)
            shadowOf(app.getSystemService(ConnectivityManager::class.java))
                .setRestrictBackgroundStatus(ConnectivityManager.RESTRICT_BACKGROUND_STATUS_DISABLED)
            controller.returnFromSettings()

            // 4. OEM: the first vendor screen the phone has, with the app named for it.
            controller.awaitDialog("oemBackgroundGuidance")
            assertEquals("oemBackgroundGuidance", controller.dialog().first)
            val oem = controller.press(AlertDialog.BUTTON_POSITIVE)
            assertEquals(hiddenApps.component, oem?.component)
            assertEquals(app.packageName, oem?.getStringExtra("package_name"))
            controller.returnFromSettings()

            awaitDashboardUi { controller.get().lifecycleScope.coroutineContext[Job]!!.children.none { it.isActive } }
            controller.noDialog()
        } finally { controller.pause().stop().destroy() }
    }

    @Test fun aSettingsScreenThatCannotOpenLeavesTheDashboardAndAsksAgainLater() {
        AuditStores.install(app, enrolled = true)
        shadowOf(checkNotNull(app.getSystemService(ActivityManager::class.java))).setBackgroundRestricted(true)
        shadowOf(app).checkActivities(true)
        val controller = Robolectric.buildActivity(MainActivity::class.java).setup()
        try {
            shadowOf(Looper.getMainLooper()).idle()
            controller.awaitDialog("batteryExemption")
            assertEquals("batteryExemption", controller.dialog().first)
            // Nothing resolves, not even the general Settings screen: the launch throws inside the dialog's handler.
            controller.press(AlertDialog.BUTTON_POSITIVE)
            assertEquals(false, controller.get().isFinishing)
            controller.returnFromSettings()
            controller.awaitDialog("batteryExemption")
            assertEquals("the restriction is still there, so it is asked again", "batteryExemption", controller.dialog().first)
        } finally { controller.pause().stop().destroy() }
    }

    private companion object {
        val TAGS = setOf("batteryExemption", "hibernationExemption", "backgroundDataRestricted", "oemBackgroundGuidance")
    }
}
