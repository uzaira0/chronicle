package com.openlattice.chronicle

import android.content.Context
import android.content.Intent
import android.database.sqlite.SQLiteFullException
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.TextView
import androidx.core.view.descendants
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.test.core.app.ApplicationProvider
import androidx.work.ListenableWorker
import com.openlattice.chronicle.audit.AuditStores
import com.openlattice.chronicle.audit.auditWorkerParameters
import com.openlattice.chronicle.audit.withStudyApi
import com.openlattice.chronicle.collection.state.onPersistenceWorker
import com.openlattice.chronicle.collection.state.ResearchPersistenceGate
import com.openlattice.chronicle.collection.capability.DistributionChannel
import com.openlattice.chronicle.collection.CollectionModuleId
import com.openlattice.chronicle.preferences.EnrollmentSettings
import com.openlattice.chronicle.preferences.INVALID_STUDY_ID
import com.openlattice.chronicle.services.withdrawal.ParticipantWithdrawalManager
import com.openlattice.chronicle.services.withdrawal.ParticipantWithdrawalWorker
import com.openlattice.chronicle.services.withdrawal.WithdrawalState
import com.openlattice.chronicle.services.withdrawal.WithdrawalStateStore
import com.openlattice.chronicle.storage.ChronicleDb
import com.openlattice.chronicle.storage.LocalStoreRecoveryReason
import com.openlattice.chronicle.storage.LocalStoreRecoveryRequiredException
import com.openlattice.chronicle.storage.PendingCollectionAckEntity
import com.openlattice.chronicle.study.EnrollmentWithdrawalResponse
import com.openlattice.chronicle.ui.DataSharingFragment
import com.openlattice.chronicle.ui.SettingsHomeFragment
import com.openlattice.chronicle.ui.awaitDashboardUi
import com.openlattice.chronicle.ui.storageFailureHandler
import java.net.SocketTimeoutException
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.android.controller.ActivityController
import org.robolectric.shadows.ShadowToast
import org.robolectric.annotation.Config
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements

@RunWith(RobolectricTestRunner::class)
class MainActivityWithdrawalRoutingTest {
    @get:Rule val workManagerRule = WorkManagerRule()
    private val context: Context = ApplicationProvider.getApplicationContext()
    private lateinit var controller: ActivityController<MainActivity>
    private val activity get() = controller.get()

    @Before fun setUp() {
        AuditStores.install(context, enrolled = true)
        EnrollmentSettings(context).apply {
            toggleBatteryOptimizationDialog(false)
            toggleHibernationExemptionDialog(false)
        }
        controller = Robolectric.buildActivity(MainActivity::class.java).setup()
        awaitDashboardUi { activity.supportFragmentManager.findFragmentById(R.id.mainFragmentContainer) != null }
        activity.selectTab(R.id.nav_settings)
        awaitDashboardUi { settingsIsVisible() }
        assertEquals(Lifecycle.State.RESUMED, activity.lifecycle.currentState)
        assertTrue(startedEnrollments().isEmpty())
    }

    @After fun tearDown() {
        controller.pause().stop().destroy()
    }

    @Test fun keepWithdrawalWhileSettingsIsResumedReturnsToEnrollmentOnce() {
        completeKeepWithdrawal()
        awaitDashboardUi { activity.isFinishing }
        val enrollmentIntents = startedEnrollments()
        assertEquals(1, enrollmentIntents.size)
        assertEnrollmentCleared()
        controller.pause().resume()
        assertTrue(startedEnrollments().isEmpty())
        val enrollment = Robolectric.buildActivity(Enrollment::class.java, enrollmentIntents.single()).setup()
        try {
            awaitDashboardUi { enrollment.get().findViewById<View>(R.id.button).isEnabled }
            assertFalse(enrollment.get().findViewById<View>(R.id.doneButton).isShown)
            assertEquals("", enrollment.get().findViewById<EditText>(R.id.participantIdText).text.toString())
        } finally {
            enrollment.pause().stop().destroy()
        }
    }

    @Test fun pausedUploadsKeepSettingsAvailableOnResume() {
        controller.pause()
        pauseUploads()
        controller.resume()
        awaitActivityJobs()
        assertPausedDashboard()
    }

    @Test fun unreadableAcknowledgmentsOpenRecoveryOnResume() {
        controller.pause().stop().destroy()
        EnrollmentTestStores.db!!.pendingCollectionAckDao().insertAll(listOf(
            PendingCollectionAckEntity(stableKey = "corrupt", record = "{")))
        controller = Robolectric.buildActivity(MainActivity::class.java).setup()
        awaitDashboardUi { activity.isFinishing }
        val started = generateSequence { shadowOf(activity).nextStartedActivity }.toList()
        assertTrue(started.any { it.component?.className == LocalStoreRecoveryActivity::class.java.name })
    }

    @Test fun notificationRationaleShowsBeforeThePermissionDialog() {
        controller.pause().stop().destroy()
        shadowOf(context as android.app.Application).denyPermissions(android.Manifest.permission.POST_NOTIFICATIONS)
        controller = Robolectric.buildActivity(MainActivity::class.java).setup()
        awaitDashboardUi { activity.supportFragmentManager.findFragmentByTag(NotificationRationaleDialog.TAG) != null }
        assertNull(shadowOf(activity).lastRequestedPermission)
        val dialog = activity.supportFragmentManager.findFragmentByTag(NotificationRationaleDialog.TAG)
            as NotificationRationaleDialog
        (dialog.dialog as androidx.appcompat.app.AlertDialog)
            .getButton(android.content.DialogInterface.BUTTON_POSITIVE).performClick()
        awaitDashboardUi { shadowOf(activity).lastRequestedPermission != null }
        assertTrue(shadowOf(activity).lastRequestedPermission.requestedPermissions
            .contains(android.Manifest.permission.POST_NOTIFICATIONS))
    }

    @Test fun storageFailureInAnActivityJobOpensRecoveryInsteadOfCrashing() {
        activity.lifecycleScope.launch(activity.storageFailureHandler()) {
            withContext(Dispatchers.IO) {
                throw LocalStoreRecoveryRequiredException(LocalStoreRecoveryReason.DATABASE_OPEN_FAILED)
            }
        }
        awaitDashboardUi { activity.isFinishing }
        val started = generateSequence { shadowOf(activity).nextStartedActivity }.toList()
        assertTrue(started.any { it.component?.className == LocalStoreRecoveryActivity::class.java.name })
    }

    /**
     * A full disk during the startup read keeps the dashboard open (Uploads, Settings and withdrawal stay
     * reachable) instead of closing the app on every launch; the next resume after space is freed routes normally.
     */
    @Test @Config(shadows = [ObservedEnrolledWork::class])
    fun fullDiskDuringTheStartupReadKeepsTheDashboardOpen() {
        awaitActivityJobs()
        controller.pause().stop().destroy()
        ObservedEnrolledWork.calls = 0
        onPersistenceWorker { ResearchPersistenceGate.initialize(context) }
        assertTrue(ResearchPersistenceGate.collectsNow(context, CollectionModuleId.BATTERY_TELEMETRY))
        ShadowToast.reset()
        EnrollmentTestStores.openFailure = LocalStoreRecoveryRequiredException(LocalStoreRecoveryReason.KEY_PERSISTENCE_FAILED,
            SQLiteFullException("database or disk is full"))
        try {
            controller = Robolectric.buildActivity(MainActivity::class.java).setup()
            awaitDashboardUi { ShadowToast.getTextOfLatestToast() == context.getString(R.string.settings_control_storage_paused) }
            awaitDashboardUi { activity.supportFragmentManager.findFragmentById(R.id.mainFragmentContainer) != null }
            assertFalse(activity.isFinishing)
            assertFalse("startup failure closes collection", ResearchPersistenceGate.collectsNow(context, CollectionModuleId.BATTERY_TELEMETRY))
            assertEquals(0, ObservedEnrolledWork.calls)
            assertTrue(generateSequence { shadowOf(activity).nextStartedActivity }
                .none { it.component?.className == LocalStoreRecoveryActivity::class.java.name })
        } finally {
            EnrollmentTestStores.openFailure = null
        }
        controller.pause().resume()
        awaitActivityJobs()
        assertFalse(activity.isFinishing)
        assertTrue(startedEnrollments().isEmpty())
        assertTrue(activity.supportFragmentManager.findFragmentById(R.id.mainFragmentContainer) != null)
        assertEquals("services start after the failed read recovers", 1, ObservedEnrolledWork.calls)
    }

    /** A full disk on a phone with no enrollment opens enrollment, never the enrolled dashboard. */
    @Test fun fullDiskOnAnUnenrolledPhoneOpensEnrollment() {
        controller.pause().stop().destroy()
        AuditStores.install(context, enrolled = false)
        EnrollmentTestStores.openFailure = LocalStoreRecoveryRequiredException(LocalStoreRecoveryReason.DATABASE_OPEN_FAILED,
            SQLiteFullException("database or disk is full"))
        try {
            controller = Robolectric.buildActivity(MainActivity::class.java).setup()
            awaitDashboardUi { activity.isFinishing }
        } finally {
            EnrollmentTestStores.openFailure = null
        }
        assertEquals(1, startedEnrollments().size)
        assertNull(activity.supportFragmentManager.findFragmentById(R.id.mainFragmentContainer))
    }

    /** Every enrolled participant is asked, restricted or not. */
    @Test fun anEnrolledUnrestrictedPhoneIsAskedToExemptBatteryOptimization() {
        controller.pause().stop().destroy()
        EnrollmentSettings(context).toggleBatteryOptimizationDialog(true)
        controller = Robolectric.buildActivity(MainActivity::class.java).setup()
        awaitDashboardUi { activity.supportFragmentManager.findFragmentByTag("batteryExemption") != null }
    }

    @Test @Config(shadows = [ObservedEnrolledWork::class])
    fun failedServiceStartupRetriesOnTheNextResumeAndSuccessStartsOnlyOnce() {
        awaitActivityJobs()
        controller.pause().stop().destroy()
        ObservedEnrolledWork.calls = 0
        ObservedEnrolledWork.fail = true
        try {
            controller = Robolectric.buildActivity(MainActivity::class.java).setup()
            awaitActivityJobs()
            assertEquals(1, ObservedEnrolledWork.calls)
            assertFalse(activity.isFinishing)
            ObservedEnrolledWork.fail = false
            controller.pause().resume()
            awaitActivityJobs()
            assertEquals(2, ObservedEnrolledWork.calls)
            controller.pause().resume()
            awaitActivityJobs()
            assertEquals("successful startup is not repeated", 2, ObservedEnrolledWork.calls)
        } finally {
            ObservedEnrolledWork.fail = false
        }
    }

    @Test fun failedQueuedEnrollmentReadClosesPublishedCollection() {
        onPersistenceWorker { ResearchPersistenceGate.initialize(context) }
        assertTrue(ResearchPersistenceGate.collectsNow(context, CollectionModuleId.BATTERY_TELEMETRY))
        ChronicleDb.getInstance(context).close()
        EnrollmentSettings(context).isEnrolled()
        awaitDashboardUi { !ResearchPersistenceGate.collectsNow(context, CollectionModuleId.BATTERY_TELEMETRY) }
        assertFalse(ResearchPersistenceGate.collectsNow(context, CollectionModuleId.BATTERY_TELEMETRY))
    }

    @Test fun pausedEnrollmentColdStartsOnDashboard() {
        controller.pause().stop().destroy()
        pauseUploads()
        controller = Robolectric.buildActivity(MainActivity::class.java,
            Intent(context, MainActivity::class.java).putExtra(MainActivity.EXTRA_SELECT_TAB, R.id.nav_settings)).setup()
        awaitActivityJobs()
        assertPausedDashboard()
    }

    @Test fun pausedEnrollmentEntryOffersReturnToDashboard() {
        pauseUploads()
        val enrollment = Robolectric.buildActivity(Enrollment::class.java).setup()
        try {
            awaitDashboardUi { enrollment.get().findViewById<View>(R.id.doneButton).isShown }
            enrollment.get().findViewById<View>(R.id.doneButton).performClick()
            assertEquals(MainActivity::class.java.name, shadowOf(enrollment.get()).nextStartedActivity.component?.className)
        } finally {
            enrollment.pause().stop().destroy()
        }
    }

    private fun pauseUploads() {
        val server = ChronicleDb.getInstance(context).uploadServerDao().getConfiguredServer()!!
        assertEquals(1, onPersistenceWorker { ResearchPersistenceGate.setServerEnabled(context, server.id, false) })
        assertFalse(onPersistenceWorker { EnrollmentSettings(context).isEnrolledOrThrow() })
    }

    private fun assertPausedDashboard() {
        assertFalse(activity.isFinishing)
        assertTrue(startedEnrollments().isEmpty())
        awaitDashboardUi { settingsIsVisible() }
        if (DistributionChannel.current() == DistributionChannel.RESEARCH) {
            awaitDashboardUi {
                activity.findViewById<ViewGroup>(R.id.settingsServerList).descendants
                    .filterIsInstance<TextView>().any { it.text == context.getString(R.string.settings_enable_uploads) }
            }
        }
    }

    @Test fun completionWhileStoppedReturnsOnResumeOnce() {
        controller.pause().saveInstanceState(Bundle()).stop()
        completeKeepWithdrawal()
        assertFalse(activity.isFinishing)
        assertTrue(startedEnrollments().isEmpty())
        controller.start().resume()
        awaitDashboardUi { activity.isFinishing }
        assertEquals(1, startedEnrollments().size)
        assertEnrollmentCleared()
        controller.pause().resume()
        assertTrue(startedEnrollments().isEmpty())
    }

    @Test fun completionWhileStartedButPausedReturnsWithoutResume() {
        controller.pause()
        assertEquals(Lifecycle.State.STARTED, activity.lifecycle.currentState)
        completeKeepWithdrawal()
        awaitDashboardUi { activity.isFinishing }
        assertEquals(1, startedEnrollments().size)
    }

    @Test fun pendingAndFailedWithdrawalsKeepSettingsAvailable() {
        assertTrue(onPersistenceWorker { ParticipantWithdrawalManager.begin(context) })
        withStudyApi({ throw SocketTimeoutException() }) {
            assertEquals(ListenableWorker.Result.retry(), onPersistenceWorker { worker().doWork() })
        }
        assertEquals(WithdrawalState.PENDING, WithdrawalStateStore(context).state())
        controller.pause().resume()
        awaitActivityJobs()
        assertTrue(settingsIsVisible())
        assertTrue(startedEnrollments().isEmpty())
        WithdrawalStateStore(context).requireServerDeletionSupport()
        completeKeepWithdrawal(expectedState = WithdrawalState.NEEDS_SUPPORT)
        assertEnrollmentCleared()
        controller.pause().resume()
        awaitActivityJobs()
        assertTrue(settingsIsVisible())
        assertFalse(activity.isFinishing)
        assertTrue(startedEnrollments().isEmpty())
    }

    /** Back on the dashboard sends the phone home; the app is not closed into the enrollment screen. */
    @Test @Config(sdk = [28]) fun backOnTheDashboardGoesHomeWithoutClosingTheApp() {
        generateSequence { shadowOf(activity).nextStartedActivity }.toList()
        activity.onBackPressedDispatcher.onBackPressed()
        val home = shadowOf(activity).nextStartedActivity
        assertEquals(Intent.ACTION_MAIN, home?.action)
        assertTrue(home!!.hasCategory(Intent.CATEGORY_HOME))
        assertFalse(activity.isFinishing)
    }

    /** The phone restarts while Leave study is still waiting for the server: the app opens on Settings, where it is. */
    @Test fun aColdStartDuringAPendingWithdrawalOpensSettings() {
        controller.pause().stop().destroy()
        assertTrue(onPersistenceWorker { ParticipantWithdrawalManager.begin(context) })
        controller = Robolectric.buildActivity(MainActivity::class.java).setup()
        awaitDashboardUi { settingsIsVisible() }
        assertTrue(startedEnrollments().isEmpty())
    }

    /** A notification asking for Data sharing, tapped while the dashboard is already open, switches to that tab. */
    @Test fun aNotificationTapWhileTheDashboardIsOpenShowsTheRequestedTab() {
        controller.newIntent(Intent(context, MainActivity::class.java).putExtra(MainActivity.EXTRA_SELECT_TAB, R.id.nav_data_sharing))
        awaitDashboardUi {
            activity.supportFragmentManager.findFragmentById(R.id.mainFragmentContainer) is DataSharingFragment
        }
    }

    private fun completeKeepWithdrawal(expectedState: WithdrawalState = WithdrawalState.COMPLETE) {
        assertTrue(onPersistenceWorker { ParticipantWithdrawalManager.begin(context) })
        withStudyApi({ method ->
            check(method == "withdrawCurrentEnrollment")
            // KEEP_PRIOR_DATA acknowledges withdrawal without creating a server deletion job.
            EnrollmentWithdrawalResponse(
                requestId = UUID.fromString(WithdrawalStateStore(context).withdrawalRequestIdForRetry()),
                deletionJobIds = emptyList(),
                alreadyWithdrawn = false,
            )
        }) {
            assertEquals(ListenableWorker.Result.success(), onPersistenceWorker { worker().doWork() })
        }
        assertEquals(expectedState, WithdrawalStateStore(context).state())
    }

    private fun worker() = ParticipantWithdrawalWorker(context, auditWorkerParameters())

    private fun awaitActivityJobs() = awaitDashboardUi {
        activity.lifecycleScope.coroutineContext[Job]!!.children.none { it.isActive }
    }

    private fun assertEnrollmentCleared() {
        assertNull(ChronicleDb.getInstance(context).uploadServerDao().getConfiguredServer())
        assertFalse(onPersistenceWorker { EnrollmentSettings(context).isEnrolledOrThrow() })
        assertEquals(INVALID_STUDY_ID, EnrollmentSettings(context).getStudyId())
        assertEquals("", EnrollmentSettings(context).getParticipantId())
    }

    private fun settingsIsVisible() =
        activity.supportFragmentManager.findFragmentById(R.id.mainFragmentContainer) is SettingsHomeFragment

    private fun startedEnrollments(): List<Intent> =
        generateSequence { shadowOf(activity).nextStartedActivity }
            .filter { it.component?.className == Enrollment::class.java.name }.toList()
}

/** Observes the enrolled-service boundary, including a transient failure before scheduling finishes. */
@Implements(value = EnrolledWork::class, isInAndroidSdk = false)
class ObservedEnrolledWork {
    @Implementation fun reconcile(context: Context, refreshSettings: Boolean) {
        check(context === context.applicationContext && refreshSettings)
        calls++
        if (fail) throw LocalStoreRecoveryRequiredException(LocalStoreRecoveryReason.DATABASE_OPEN_FAILED,
            SQLiteFullException("disk full during service reconciliation"))
    }

    companion object {
        var calls = 0
        var fail = false
    }
}
