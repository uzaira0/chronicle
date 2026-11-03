package com.openlattice.chronicle

import android.app.AlarmManager
import android.content.Context
import android.content.DialogInterface
import android.content.SharedPreferences
import android.view.View
import android.widget.TextView
import android.os.Looper
import androidx.appcompat.app.AlertDialog
import androidx.lifecycle.Observer
import androidx.lifecycle.ViewModelProvider
import androidx.test.core.app.ApplicationProvider
import com.openlattice.chronicle.preferences.PARTICIPANT_ID
import com.openlattice.chronicle.preferences.STUDY_ID
import com.openlattice.chronicle.services.notifications.alarmOperation
import com.openlattice.chronicle.storage.LocalStoreRecoveryManager
import com.openlattice.chronicle.storage.LocalStoreRecoveryReason
import com.openlattice.chronicle.storage.LocalStoreResetConfirmation
import com.openlattice.chronicle.storage.LocalStoreResetResult
import java.io.File
import java.lang.reflect.Proxy
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
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
import org.robolectric.annotation.Config
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements
import org.robolectric.shadows.ShadowDialog

private const val AWAIT_SECONDS = 30L

@RunWith(RobolectricTestRunner::class)
@Config(shadows = [LocalStoreRecoveryRecreationTest.PreservedStore::class])
class LocalStoreRecoveryRecreationTest {
    @get:Rule val workManagerRule = WorkManagerRule()

    @Test fun confirmedResetFinishesAfterRotation() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val db = EnrollmentTestStores.install(context)
        PreservedStore.entered = CountDownLatch(1)
        PreservedStore.released = CountDownLatch(1)
        val controller = Robolectric.buildActivity(LocalStoreRecoveryActivity::class.java,
            LocalStoreRecoveryActivity.intent(context, LocalStoreRecoveryReason.DATABASE_OPEN_FAILED)).setup()
        val alarms = shadowOf(context.getSystemService(AlarmManager::class.java))
        assertFalse(restartArmed(alarms))
        // Registered before the recreated Activity's observer, so it sees the outcome that observer consumes.
        val reset = ViewModelProvider(controller.get())[LocalStoreRecoveryActivity.ResetState::class.java]
        var outcome: Result<Unit>? = null
        val recordOutcome = Observer<Result<Unit>?> { if (it != null) outcome = it }
        reset.outcome.observeForever(recordOutcome)
        try {
            controller.get().findViewById<View>(R.id.localStoreRecoveryReset).performClick()
            repeat(2) {
                (ShadowDialog.getLatestDialog() as AlertDialog).getButton(DialogInterface.BUTTON_POSITIVE).performClick()
                shadowOf(Looper.getMainLooper()).idle()
            }
            // The reset runs real I/O off the main thread; wait for it, not for a UI polling budget.
            awaitIdlingMain { PreservedStore.entered.count == 0L }
            controller.recreate()
            PreservedStore.released.countDown()
            awaitIdlingMain { outcome != null }
            assertTrue(outcome.toString(), outcome!!.isSuccess)
            assertFalse(reset.running)
            assertTrue(restartArmed(alarms))
            assertNull("no enrollment, so the bundle is bound to no participant", PreservedStore.ownerScope)
        } finally {
            reset.outcome.removeObserver(recordOutcome)
            PreservedStore.released.countDown()
            controller.pause().stop().destroy()
            db.close()
        }
    }

    /**
     * The store of an enrolled phone cannot be opened and the participant confirms the reset: the preserved bundle is
     * bound to that study participant, so only their re-enrollment (or their withdrawal) can claim or remove it.
     */
    @Test fun aResetDuringAnEnrollmentBindsThePreservedBundleToThatParticipant() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val db = EnrollmentTestStores.install(context, enrolled = true)
        val prefs = checkNotNull(EnrollmentTestStores.prefs)
        val expected = LocalStoreRecoveryManager.scopeDigest(prefs.getString(STUDY_ID, null)!!, prefs.getString(PARTICIPANT_ID, null)!!)
        PreservedStore.entered = CountDownLatch(1)
        PreservedStore.released = CountDownLatch(0)
        PreservedStore.ownerScope = null
        val controller = Robolectric.buildActivity(LocalStoreRecoveryActivity::class.java,
            LocalStoreRecoveryActivity.intent(context, LocalStoreRecoveryReason.DATABASE_OPEN_FAILED)).setup()
        try {
            controller.get().findViewById<View>(R.id.localStoreRecoveryReset).performClick()
            repeat(2) {
                (ShadowDialog.getLatestDialog() as AlertDialog).getButton(DialogInterface.BUTTON_POSITIVE).performClick()
                shadowOf(Looper.getMainLooper()).idle()
            }
            awaitIdlingMain { PreservedStore.entered.count == 0L }
            assertEquals(expected, PreservedStore.ownerScope)
        } finally {
            controller.pause().stop().destroy()
            db.close()
        }
    }

    /** Preferences that open but fail every read (a refused write, a lost key) bind the bundle to no one; the reset runs. */
    @Test fun aPreferenceReadFailureStillReachesTheReset() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val db = EnrollmentTestStores.install(context, enrolled = true)
        val readable = checkNotNull(EnrollmentTestStores.prefs)
        EnrollmentTestStores.prefs = Proxy.newProxyInstance(SharedPreferences::class.java.classLoader,
            arrayOf(SharedPreferences::class.java)) { _, method, args ->
            if (method.name == "getString") throw SecurityException("keyset no longer decrypts")
            method.invoke(readable, *(args ?: emptyArray()))
        } as SharedPreferences
        PreservedStore.entered = CountDownLatch(1)
        PreservedStore.released = CountDownLatch(0)
        PreservedStore.ownerScope = "unset"
        val controller = Robolectric.buildActivity(LocalStoreRecoveryActivity::class.java,
            LocalStoreRecoveryActivity.intent(context, LocalStoreRecoveryReason.DATABASE_OPEN_FAILED)).setup()
        try {
            controller.get().findViewById<View>(R.id.localStoreRecoveryReset).performClick()
            repeat(2) {
                (ShadowDialog.getLatestDialog() as AlertDialog).getButton(DialogInterface.BUTTON_POSITIVE).performClick()
                shadowOf(Looper.getMainLooper()).idle()
            }
            awaitIdlingMain { PreservedStore.entered.count == 0L }
            assertNull(PreservedStore.ownerScope)
        } finally {
            controller.pause().stop().destroy()
            db.close()
        }
    }

    /** Back on the recovery screen leaves the app in the background; it never drops the participant into collection. */
    @Test fun backOnTheRecoveryScreenDoesNotLeaveRecovery() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val db = EnrollmentTestStores.install(context)
        val controller = Robolectric.buildActivity(LocalStoreRecoveryActivity::class.java,
            LocalStoreRecoveryActivity.intent(context, LocalStoreRecoveryReason.INVALID_KEY_MATERIAL)).setup()
        try {
            controller.get().onBackPressedDispatcher.onBackPressed()
            assertFalse(controller.get().isFinishing)
            assertNull(shadowOf(controller.get()).nextStartedActivity)
        } finally {
            controller.pause().stop().destroy()
            db.close()
        }
    }

    /** Each local-store failure tells the participant its own cause. */
    @Test fun everyRecoveryReasonShowsItsOwnExplanation() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val db = EnrollmentTestStores.install(context)
        try {
            val shown = LocalStoreRecoveryReason.values().associateWith { reason ->
                val controller = Robolectric.buildActivity(LocalStoreRecoveryActivity::class.java,
                    LocalStoreRecoveryActivity.intent(context, reason)).setup()
                try { controller.get().findViewById<TextView>(R.id.localStoreRecoveryReason).text.toString() }
                finally { controller.pause().stop().destroy() }
            }
            assertTrue(shown.toString(), shown.values.none(String::isBlank))
            assertEquals(shown.toString(), shown.size, shown.values.toSet().size)
        } finally { db.close() }
    }

    private fun awaitIdlingMain(condition: () -> Boolean) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(AWAIT_SECONDS)
        while (!condition()) {
            check(System.nanoTime() < deadline) { "Recovery reset did not reach the awaited state" }
            shadowOf(Looper.getMainLooper()).idle()
            Thread.sleep(10)
        }
    }

    @Implements(value = LocalStoreRecoveryManager::class, isInAndroidSdk = false)
    class PreservedStore {
        @Implementation fun preserveAndReset(context: Context, reason: LocalStoreRecoveryReason,
            confirmation: LocalStoreResetConfirmation, ownerScope: String?): LocalStoreResetResult {
            assertEquals(LocalStoreRecoveryReason.DATABASE_OPEN_FAILED, reason)
            PreservedStore.ownerScope = ownerScope
            confirmation.requireExplicitApproval()
            entered.countDown()
            check(released.await(AWAIT_SECONDS, TimeUnit.SECONDS))
            return LocalStoreResetResult(File(context.filesDir, "verified-recovery-fixture"))
        }

        companion object {
            lateinit var entered: CountDownLatch
            lateinit var released: CountDownLatch
            var ownerScope: String? = null
        }
    }

    /** The verified reset's restart alarm (it opens MainActivity); other alarms are not this test's. */
    private fun restartArmed(alarms: org.robolectric.shadows.ShadowAlarmManager) = alarms.scheduledAlarms.any {
        alarmOperation(it)?.let { shadowOf(it).savedIntent.component?.className } ==
            MainActivity::class.java.name
    }
}
