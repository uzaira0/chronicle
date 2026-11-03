package com.openlattice.chronicle

import android.app.Application
import android.app.Activity
import android.os.Looper
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.DialogFragment
import androidx.test.core.app.ApplicationProvider
import com.openlattice.chronicle.storage.LocalStoreRecoveryReason
import com.openlattice.chronicle.storage.LocalStoreRecoveryRequiredException
import com.openlattice.chronicle.collection.state.ResearchPersistenceGate
import com.openlattice.chronicle.services.notifications.DeviceUnlockMonitoringService
import com.openlattice.chronicle.ui.SettingsHomeFragment
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/** android:L6: an enrollment link that cold-starts the app opens the encrypted store off the main thread. */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class EnrollmentStoreRecoveryTest {
    @After fun tearDown() {
        EnrollmentTestStores.openFailure = null
        EnrollmentTestStores.failedOpenThread = null
        EnrollmentTestStores.failedPreferenceOpenThread.set(null)
        EnrollmentTestStores.prefs = null
        EnrollmentTestStores.db?.close()
        EnrollmentTestStores.db = null
    }

    @Test fun unreadableStoreRoutesToRecoveryWithoutOpeningItOnTheMainThread() {
        EnrollmentTestStores.install(ApplicationProvider.getApplicationContext())
        EnrollmentTestStores.openFailure = LocalStoreRecoveryRequiredException(LocalStoreRecoveryReason.DATABASE_OPEN_FAILED)
        val enrollment = Robolectric.buildActivity(Enrollment::class.java).create().get()
        assertRecovery(enrollment)
        assertNotEquals(Looper.getMainLooper().thread, EnrollmentTestStores.failedOpenThread)
    }

    @Test fun dashboardOpensSecurePreferencesOffMain() = assertSecureRecovery(MainActivity::class.java)

    @Test fun identificationOpensSecurePreferencesOffMain() = assertSecureRecovery(UserIdentificationActivity::class.java)

    @Test fun restoredSettingsOpensSecurePreferencesOffMain() = assertSecureRecovery(CrashTestHostActivity::class.java) { host ->
        host.supportFragmentManager.beginTransaction().add(android.R.id.content, SettingsHomeFragment()).commitNow()
    }

    @Test fun batteryDialogOpensSecurePreferencesOffMain() = assertDialogRecovery(BatteryOptimizationExemptionDialog())

    @Test fun hibernationDialogOpensSecurePreferencesOffMain() = assertDialogRecovery(AppHibernationExemptionDialog())

    @Test fun backgroundDataDialogOpensSecurePreferencesOffMain() = assertDialogRecovery(BackgroundDataRestrictionDialog())

    @Test fun oemDialogOpensSecurePreferencesOffMain() {
        org.robolectric.shadows.ShadowBuild.setManufacturer("samsung")
        assertDialogRecovery(OemBackgroundGuidanceDialog())
    }

    private fun assertDialogRecovery(fragment: DialogFragment) = assertSecureRecovery(CrashTestHostActivity::class.java) { host ->
        fragment.showNow(host.supportFragmentManager, "exemption")
        (fragment.requireDialog() as AlertDialog).getButton(AlertDialog.BUTTON_NEGATIVE).performClick()
    }

    private fun <T : Activity> assertSecureRecovery(activityClass: Class<T>, show: (T) -> Unit = {}) {
        EnrollmentTestStores.install(ApplicationProvider.getApplicationContext())
        EnrollmentTestStores.prefs = null
        val controller = Robolectric.buildActivity(activityClass).setup()
        try {
            val activity = controller.get()
            show(activity)
            assertRecovery(activity)
            assertNotNull(EnrollmentTestStores.failedPreferenceOpenThread.get())
            assertNotEquals(Looper.getMainLooper().thread, EnrollmentTestStores.failedPreferenceOpenThread.get())
        } finally { controller.pause().stop().destroy() }
    }

    private fun assertRecovery(activity: Activity) {
        val deadline = System.currentTimeMillis() + 5_000
        while (!activity.isFinishing && System.currentTimeMillis() < deadline) {
            Thread.sleep(20)
            shadowOf(Looper.getMainLooper()).idle()
        }
        assertTrue(activity.isFinishing)
        assertEquals(LocalStoreRecoveryActivity::class.java.name, shadowOf(activity).nextStartedActivity.component?.className)
    }

    @Test fun monitoringServiceDoesNotOpenSecurePreferencesOnMain() {
        EnrollmentTestStores.install(ApplicationProvider.getApplicationContext())
        EnrollmentTestStores.prefs = null
        val controller = Robolectric.buildService(DeviceUnlockMonitoringService::class.java).create().startCommand(0, 1)
        try {
            val drained = CompletableFuture<Unit>()
            ResearchPersistenceGate.executeAsync { drained.complete(Unit) }
            drained.get(10, TimeUnit.SECONDS)
            assertNotNull(EnrollmentTestStores.failedPreferenceOpenThread.get())
            assertNotEquals(Looper.getMainLooper().thread, EnrollmentTestStores.failedPreferenceOpenThread.get())
        } finally { controller.destroy() }
    }
}
