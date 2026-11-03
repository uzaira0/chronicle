package com.openlattice.chronicle

import android.database.SQLException
import android.os.Bundle
import android.widget.TextView
import com.openlattice.chronicle.collection.state.onPersistenceWorker
import com.openlattice.chronicle.collection.state.stopOnPersistenceWorker
import com.openlattice.chronicle.receivers.lifecycle.PowerSaveModeReceiver
import org.junit.Assume

import android.app.Application
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.net.Uri
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import androidx.core.view.descendants
import androidx.appcompat.app.AppCompatActivity
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.openlattice.chronicle.collection.CollectionModuleId
import com.openlattice.chronicle.collection.HealthConnectRecordType
import com.openlattice.chronicle.collection.device.HealthConnectScopeStore
import com.openlattice.chronicle.collection.state.ResearchPersistenceGate
import com.openlattice.chronicle.collection.state.CollectionConsentCopy
import com.google.android.material.materialswitch.MaterialSwitch
import com.openlattice.chronicle.preferences.EnrollmentSettings
import com.openlattice.chronicle.preferences.PARTICIPANT_ID
import com.openlattice.chronicle.preferences.PARTICIPATION_STATUS
import com.openlattice.chronicle.preferences.STUDY_ID
import com.openlattice.chronicle.receivers.lifecycle.StartOnBoot
import com.openlattice.chronicle.services.notifications.UnlockMonitoringRuntimeStatus
import com.openlattice.chronicle.storage.ChronicleDb
import com.openlattice.chronicle.storage.CollectionModuleStateEntity
import com.openlattice.chronicle.storage.LocalStoreRecoveryRequiredException
import com.openlattice.chronicle.storage.LocalStoreRecoveryReason
import com.openlattice.chronicle.storage.UploadServerEntity
import com.openlattice.chronicle.ui.DataSharingFragment
import com.openlattice.chronicle.ui.OverviewFragment
import java.lang.reflect.Proxy
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.android.controller.ActivityController
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [28])
class CrashBoundaryRegressionTest {
    @get:Rule val workManagerRule = WorkManagerRule()
    private val context: Context = ApplicationProvider.getApplicationContext()
    private lateinit var prefs: SharedPreferences
    private lateinit var db: ChronicleDb

    @Before fun setUp() {
        ResearchPersistenceGate.resetAfterLocalStoreRecovery()
        prefs = context.getSharedPreferences("crash-boundary", Context.MODE_PRIVATE)
        prefs.edit().clear().putString(STUDY_ID, "11111111-1111-1111-1111-111111111111")
            .putString(PARTICIPANT_ID, "participant").putString(PARTICIPATION_STATUS, "ENROLLED").commit()
        installPrefs(prefs)
        db = Room.inMemoryDatabaseBuilder(context, ChronicleDb::class.java).allowMainThreadQueries().build()
        EnrollmentTestStores.db = db
        db.uploadServerDao().insert(UploadServerEntity(name = "study", url = "https://localhost",
            studyId = prefs.getString(STUDY_ID, "")!!, participantId = "participant", sourceRandomDeviceId = "device", apiKey = "test-key"))
    }

    @After fun tearDown() {
        ResearchPersistenceGate.resetAfterLocalStoreRecovery()
        installPrefs(null)
        EnrollmentTestStores.db = null
        db.close()
    }

    @Test fun securePreferenceFailureRoutesLauncherAndIdentificationToRecovery() {
        installPrefs(null) // Robolectric deliberately has no AndroidKeyStore.
        val main = Robolectric.buildActivity(MainActivity::class.java).create().get()
        awaitUi { main.isFinishing }
        assertTrue(main.isFinishing)
        assertEquals(LocalStoreRecoveryActivity::class.java.name, shadowOf(main).nextStartedActivity.component?.className)
        val identify = Robolectric.buildActivity(UserIdentificationActivity::class.java).create().get()
        awaitUi { identify.isFinishing }
        assertTrue(identify.isFinishing)
        assertEquals(LocalStoreRecoveryActivity::class.java.name, shadowOf(identify).nextStartedActivity.component?.className)
    }

    @Test fun powerSaveReceiverContainsSecurePreferenceFailure() {
        Assume.assumeTrue(BuildConfig.ALLOW_RESTRICTED_RESEARCH_PERMISSIONS)
        installPrefs(null)
        val delivered = runCatching {
            PowerSaveModeReceiver().onReceive(context, Intent("android.os.action.POWER_SAVE_MODE_CHANGED"))
        }
        assertTrue("an unreadable secure store must not crash the receiver: ${delivered.exceptionOrNull()}", delivered.isSuccess)
    }

    @Test fun workManagerInitializationHandlerContainsPersistentDatabaseFailure() {
        val config = ChronicleApplication().workManagerConfiguration
        val handler = config.initializationExceptionHandler
        assertNotNull("ForceStopRunnable must have a nonthrowing exception handler", handler)
        repeat(3) { handler!!.accept(IllegalStateException("database full")) }
        assertTrue(WorkSchedulingStatus.unavailable)
    }

    @Test fun deferredStatusCommitAndCryptoFailureCannotEscapeRejectionReporting() {
        failCommits()
        assertTrue(markDeferredOnAuthorizationWorker().isSuccess)
        assertFalse(prefs.getBoolean("unlock_monitoring_start_deferred", false))
        installPrefs(null)
        assertTrue(markDeferredOnAuthorizationWorker().isSuccess)
    }

    /** The status writer runs on the authorization worker, where an escaped failure would be lost in the executor. */
    private fun markDeferredOnAuthorizationWorker(): Result<Unit> {
        val outcome = java.util.concurrent.CompletableFuture<Result<Unit>>()
        ResearchPersistenceGate.executeAsync {
            outcome.complete(runCatching { UnlockMonitoringRuntimeStatus.markDeferred(context, true) })
        }
        return outcome.get(10, TimeUnit.SECONDS)
    }

    @Test fun orphanedScopeCleanupFailureReportsRecoveryAndRetainsIdentity() {
        db.openHelper.writableDatabase.execSQL("DELETE FROM upload_servers")
        HealthConnectScopeStore.of(context).replace(setOf(HealthConnectRecordType.STEPS))
        failCommits()
        val settings = EnrollmentSettings(context)
        try {
            onPersistenceWorker { settings.isEnrolled() }
            fail("failed reconciliation must report storage recovery")
        } catch (error: LocalStoreRecoveryRequiredException) {
            assertNotNull(error.cause)
        }
        assertEquals("participant", settings.getParticipantId())
        assertEquals("participant", prefs.getString(PARTICIPANT_ID, null))
        assertEquals(setOf(HealthConnectRecordType.STEPS.id), prefs.getStringSet("health_connect_study_record_types", null))
    }

    @Test fun dashboardQueryFailureRoutesLifecycleJobToRecovery() {
        db.openHelper.writableDatabase.execSQL("DROP TABLE dataQueue")
        val host = Robolectric.buildActivity(CrashTestHostActivity::class.java).setup().get()
        host.supportFragmentManager.beginTransaction().add(android.R.id.content, OverviewFragment()).commitNow()
        awaitUi { host.isFinishing }
        assertEquals(LocalStoreRecoveryActivity::class.java.name, shadowOf(host).nextStartedActivity.component?.className)
    }

    @Test fun bootTaskFinishesOnSecurePreferenceFailure() {
        installPrefs(null)
        var finished = false
        StartOnBoot().runBootTask(context) { finished = true }
        assertTrue(finished)
    }

    @Test fun failedConsentWriteClosesCollectionAndShowsRecovery() {
        db.collectionModuleStateDao().upsertAll(listOf(CollectionModuleStateEntity(
            moduleId = CollectionModuleId.CONNECTIVITY_STATE.id, serverEnabled = true, decision = "ACCEPTED",
            decidedAtEpochMillis = 1, requiredApplied = false, appliedVersion = 1)))
        onPersistenceWorker { ResearchPersistenceGate.initialize(context) }
        db.openHelper.writableDatabase.execSQL(
            "CREATE TRIGGER fail_consent BEFORE INSERT ON collection_module_state BEGIN SELECT RAISE(ABORT, 'disk full'); END")
        val host = Robolectric.buildActivity(CrashTestHostActivity::class.java).setup().get()
        val fragment = DataSharingFragment()
        host.supportFragmentManager.beginTransaction().add(android.R.id.content, fragment).commitNow()
        val description = context.getString(R.string.ds_module_toggle_cd,
            CollectionConsentCopy.label(context, CollectionModuleId.CONNECTIVITY_STATE))
        awaitUi { (fragment.requireView() as ViewGroup).descendants.filterIsInstance<MaterialSwitch>()
            .any { it.contentDescription == description && it.isEnabled && it.isChecked } }
        (fragment.requireView() as ViewGroup).descendants.filterIsInstance<MaterialSwitch>()
            .single { it.contentDescription == description }.performClick()
        awaitUi { host.isFinishing }
        assertFalse(ResearchPersistenceGate.isActiveEnrollment(context))
        assertEquals(LocalStoreRecoveryActivity::class.java.name, shadowOf(host).nextStartedActivity.component?.className)
        assertEquals("ACCEPTED", db.collectionModuleStateDao().getAll().single().decision)
    }

    @Test fun failedPrivacyMutationClosesAdmissionBeforeItsGateLeaseIsReleased() {
        db.openHelper.writableDatabase.execSQL(
            "CREATE TRIGGER fail_privacy BEFORE UPDATE ON upload_servers BEGIN SELECT RAISE(ABORT, 'disk full'); END")
        try {
            stopOnPersistenceWorker { db.uploadServerDao().setEnabled(db.uploadServerDao(
                ).getConfiguredServer()!!.id, false) }
            fail("the failed mutation must be reported")
        } catch (_: SQLException) {
            assertFalse(ResearchPersistenceGate.isActiveEnrollment(context))
            assertTrue(db.uploadServerDao().getConfiguredServer()!!.enabled)
        }
    }

    @Test fun destroyedEnrollmentDiscardsQueuedLauncherCompletion() {
        prefs.edit().remove(STUDY_ID).remove(PARTICIPANT_ID).commit()
        val executor = Executors.newSingleThreadExecutor()
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        executor.execute { entered.countDown(); check(release.await(5, TimeUnit.SECONDS)) }
        assertTrue(entered.await(5, TimeUnit.SECONDS))
        val controller = ActivityController.of(Enrollment(executor)).setup()
        val activity = controller.get()
        EnrollmentTestStores.openFailure = LocalStoreRecoveryRequiredException(LocalStoreRecoveryReason.DATABASE_OPEN_FAILED)
        try {
            controller.pause().stop().destroy()
            release.countDown()
            assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS))
            shadowOf(Looper.getMainLooper()).idle()
            assertNull("a destroyed launcher must not open recovery after its queued read finishes", shadowOf(activity).nextStartedActivity)
        } finally { release.countDown(); EnrollmentTestStores.openFailure = null; executor.shutdownNow() }
    }

    @Test fun opaqueAndWrongDestinationEnrollmentIntentsAreRejectedBeforeQueryParsing() {
        prefs.edit().remove(STUDY_ID).remove(PARTICIPANT_ID).commit()
        db.uploadServerDao().delete(db.uploadServerDao().getConfiguredServer()!!.id)
        val controller = Robolectric.buildActivity(Enrollment::class.java).setup()
        val activity = controller.get()
        try {
            awaitUi { activity.findViewById<View>(R.id.button).isEnabled }
            for (uri in listOf("chronicle:enroll", "chronicle://other", "https://enroll", "chronicle://enroll/other")) {
                controller.newIntent(Intent(Intent.ACTION_VIEW, Uri.parse(uri)))
                awaitUi { activity.findViewById<TextView>(R.id.statusMessage).text.toString() ==
                    context.getString(R.string.enrollment_invitation_unverified) }
                assertEquals(context.getString(R.string.enrollment_invitation_unverified),
                    activity.findViewById<TextView>(R.id.statusMessage).text.toString())
            }
        } finally { controller.pause().stop().destroy() }
    }

    private fun installPrefs(value: SharedPreferences?) {
        EnrollmentTestStores.prefs = value
    }

    private fun failCommits() {
        val editor = Proxy.newProxyInstance(SharedPreferences.Editor::class.java.classLoader, arrayOf(SharedPreferences.Editor::class.java)) { proxy,
            method, _ ->
            if (method.name == "commit") false else proxy
        } as SharedPreferences.Editor
        installPrefs(Proxy.newProxyInstance(SharedPreferences::class.java.classLoader, arrayOf(SharedPreferences::class.java)) { _, method, args ->
            if (method.name == "edit") editor else method.invoke(prefs, *(args ?: emptyArray()))
        } as SharedPreferences)
    }

    private fun awaitUi(predicate: () -> Boolean) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
        while (!predicate() && System.nanoTime() < deadline) { shadowOf(Looper.getMainLooper()).idle(); Thread.sleep(10) }
        assertTrue("UI result must arrive", predicate())
    }
}

class CrashTestHostActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        setTheme(R.style.AppTheme)
        super.onCreate(savedInstanceState)
    }
}
