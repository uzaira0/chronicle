package com.openlattice.chronicle

import android.app.ActivityManager
import android.app.Application
import android.app.usage.UsageStatsManager
import android.content.Context
import android.content.Intent
import android.database.sqlite.SQLiteFullException
import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import com.openlattice.chronicle.audit.AuditStores
import com.openlattice.chronicle.preferences.EnrollmentSettings
import com.openlattice.chronicle.storage.LocalStoreRecoveryReason
import com.openlattice.chronicle.storage.LocalStoreRecoveryRequiredException
import com.openlattice.chronicle.ui.SettingsHomeFragment
import com.openlattice.chronicle.ui.awaitDashboardUi
import com.openlattice.chronicle.services.withdrawal.WithdrawalState
import com.openlattice.chronicle.services.withdrawal.WithdrawalStateStore
import kotlinx.coroutines.asCoroutineDispatcher
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.android.controller.ActivityController
import org.robolectric.android.util.concurrent.PausedExecutorService
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
class MainActivityPlatformPolicyTest {
    @get:Rule val workManagerRule = WorkManagerRule()
    private val context: Context = ApplicationProvider.getApplicationContext()
    private var controller: ActivityController<MainActivity>? = null

    @After fun tearDown() {
        EnrollmentTestStores.openFailure = null
        controller?.pause()?.stop()?.destroy()
    }

    @Test @Config(sdk = [36]) fun modernAndroidUsesThePlatformBackBehavior() {
        val activity = launch()
        assertFalse(activity.onBackPressedDispatcher.hasEnabledCallbacks())
    }

    @Test @Config(sdk = [28]) fun oldAndroidKeepsTheRootTaskBackCallback() {
        val activity = launch()
        assertTrue(activity.onBackPressedDispatcher.hasEnabledCallbacks())
    }

    // Every enrolled participant without the exemption is asked, whatever Android has measured about the app.
    @Test @Config(sdk = [36]) fun activeUnrestrictedDeviceAsksForBatteryExemption() {
        val activity = launch()
        assertTrue(activity.supportFragmentManager.findFragmentByTag("batteryExemption") != null)
    }

    @Test @Config(sdk = [36]) fun rareStandbyBucketOffersBatteryExemption() {
        val activity = launch(bucket = UsageStatsManager.STANDBY_BUCKET_RARE)
        assertTrue(activity.supportFragmentManager.findFragmentByTag("batteryExemption") != null)
    }

    @Test @Config(sdk = [36]) fun frequentStandbyBucketAsksForBatteryExemption() {
        val activity = launch(bucket = UsageStatsManager.STANDBY_BUCKET_FREQUENT)
        assertTrue(activity.supportFragmentManager.findFragmentByTag("batteryExemption") != null)
    }

    @Test @Config(sdk = [36]) fun restrictedStandbyBucketOffersBatteryExemption() {
        val activity = launch(bucket = UsageStatsManager.STANDBY_BUCKET_RESTRICTED)
        assertTrue(activity.supportFragmentManager.findFragmentByTag("batteryExemption") != null)
    }

    @Test @Config(sdk = [27]) fun olderPlatformWithoutRestrictionMeasurementAsksForExemption() {
        val activity = launch()
        assertTrue(activity.supportFragmentManager.findFragmentByTag("batteryExemption") != null)
    }

    @Test @Config(sdk = [36]) fun backgroundRestrictedDeviceOffersBatteryExemption() {
        val activity = launch(restricted = true)
        assertTrue(activity.supportFragmentManager.findFragmentByTag("batteryExemption") != null)
    }

    @Test fun fullDiskWithoutEnrollmentReturnsToEnrollmentWithoutOpeningDashboard() {
        val activity = launch(enrolled = false, fullDisk = true)
        awaitDashboardUi { activity.isFinishing }
        val intents = generateSequence { shadowOf(activity).nextStartedActivity }.toList()
        assertTrue(intents.any { it.component?.className == Enrollment::class.java.name })
        assertNull(activity.supportFragmentManager.findFragmentById(R.id.mainFragmentContainer))
    }

    @Test fun fullDiskKeepsPendingWithdrawalOnSettings() {
        AuditStores.install(context, enrolled = true)
        WithdrawalStateStore(context).setState(WithdrawalState.NEEDS_SUPPORT)
        EnrollmentTestStores.openFailure = diskFull()
        controller = Robolectric.buildActivity(MainActivity::class.java).setup()
        val activity = controller!!.get()
        awaitDashboardUi {
            activity.supportFragmentManager.findFragmentById(R.id.mainFragmentContainer) is SettingsHomeFragment
        }
        assertFalse(activity.isFinishing)
        assertEquals(WithdrawalState.NEEDS_SUPPORT, WithdrawalStateStore(context).state())
    }

    @Test fun enrolledFullDiskHonorsTheRequestedSettingsTab() {
        AuditStores.install(context, enrolled = true)
        EnrollmentTestStores.openFailure = diskFull()
        val intent = Intent(context, MainActivity::class.java).putExtra(MainActivity.EXTRA_SELECT_TAB, R.id.nav_settings)
        controller = Robolectric.buildActivity(MainActivity::class.java, intent).setup()
        val activity = controller!!.get()
        awaitDashboardUi { activity.supportFragmentManager.findFragmentById(R.id.mainFragmentContainer) != null }
        assertTrue(activity.supportFragmentManager.findFragmentById(R.id.mainFragmentContainer) is SettingsHomeFragment)
        assertFalse(activity.isFinishing)
    }

    @Test @Config(application = Application::class, sdk = [34])
    fun fullDiskThenUnreadableSecurePreferencesOpensRecoveryWithoutUncaughtRoutingFailure() {
        AuditStores.install(context, enrolled = true)
        val retainedPreferences = checkNotNull(EnrollmentTestStores.prefs)
        val executor = PausedExecutorService()
        val dispatcher = executor.asCoroutineDispatcher()
        EnrollmentTestStores.openFailure = null
        controller = ActivityController.of(MainActivity(dispatcher))
        try {
            controller!!.setup()
            assertTrue("initial identity read is queued", executor.runNext())
            shadowOf(Looper.getMainLooper()).idle()
            assertTrue("database lookup follows the initial identity read", executor.hasQueuedTasks())
            EnrollmentTestStores.openFailure = diskFull()
            EnrollmentTestStores.prefs = null
            val activity = controller!!.get()
            awaitDashboardUi {
                executor.runAll()
                activity.isFinishing
            }
            val started = generateSequence { shadowOf(activity).nextStartedActivity }.toList()
            assertTrue("recovery expected; started=${started.map { it.component?.className }}",
                started.any { it.component?.className == LocalStoreRecoveryActivity::class.java.name })
            val failedThread = checkNotNull(EnrollmentTestStores.failedPreferenceOpenThread.get())
            assertTrue("the fallback opens secure preferences off MAIN", failedThread !== Looper.getMainLooper().thread)
        } finally {
            EnrollmentTestStores.openFailure = null
            EnrollmentTestStores.prefs = retainedPreferences
            controller?.pause()?.stop()?.destroy()
            controller = null
            dispatcher.close()
        }
    }

    private fun launch(
        enrolled: Boolean = true,
        bucket: Int = UsageStatsManager.STANDBY_BUCKET_ACTIVE,
        restricted: Boolean = false,
        fullDisk: Boolean = false,
    ): MainActivity {
        AuditStores.install(context, enrolled = enrolled)
        EnrollmentSettings(context).toggleHibernationExemptionDialog(false)
        assertFalse("fixture must be unexempt", hasIgnoreBatteryOptimization(context))
        shadowOf(context.getSystemService(Context.USAGE_STATS_SERVICE) as UsageStatsManager)
            .setCurrentAppStandbyBucket(bucket)
        shadowOf(context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager).setBackgroundRestricted(restricted)
        if (fullDisk) EnrollmentTestStores.openFailure = diskFull()
        controller = Robolectric.buildActivity(MainActivity::class.java).setup()
        return controller!!.get().also { activity ->
            awaitDashboardUi {
                activity.isFinishing || activity.supportFragmentManager.findFragmentById(R.id.mainFragmentContainer) != null
            }
        }
    }

    private fun diskFull() = LocalStoreRecoveryRequiredException(LocalStoreRecoveryReason.DATABASE_OPEN_FAILED,
        SQLiteFullException("database or disk is full"))
}
