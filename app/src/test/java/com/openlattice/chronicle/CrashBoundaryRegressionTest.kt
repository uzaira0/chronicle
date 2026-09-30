package com.openlattice.chronicle

import android.app.Application
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.net.Uri
import android.os.Looper
import android.view.View
import androidx.appcompat.app.AppCompatActivity
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.openlattice.chronicle.collection.CollectionModuleId
import com.openlattice.chronicle.collection.HealthConnectRecordType
import com.openlattice.chronicle.collection.device.HealthConnectScopeStore
import com.openlattice.chronicle.collection.state.ResearchPersistenceGate
import com.openlattice.chronicle.preferences.EncryptedPrefsHelper
import com.openlattice.chronicle.preferences.EnrollmentSettings
import com.openlattice.chronicle.preferences.PARTICIPANT_ID
import com.openlattice.chronicle.preferences.PARTICIPATION_STATUS
import com.openlattice.chronicle.preferences.STUDY_ID
import com.openlattice.chronicle.receivers.lifecycle.StartOnBoot
import com.openlattice.chronicle.services.notifications.UnlockMonitoringRuntimeStatus
import com.openlattice.chronicle.storage.ChronicleDb
import com.openlattice.chronicle.storage.CollectionModuleStateEntity
import com.openlattice.chronicle.storage.LocalStoreRecoveryRequiredException
import com.openlattice.chronicle.storage.UploadServerEntity
import com.openlattice.chronicle.ui.DashboardDataRepository
import com.openlattice.chronicle.ui.DataSharingFragment
import com.openlattice.chronicle.ui.OverviewFragment
import com.openlattice.chronicle.ui.SettingsHomeFragment
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Proxy
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutorService
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [28])
class CrashBoundaryRegressionTest {
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
        ChronicleDb::class.java.getDeclaredField("INSTANCE").apply { isAccessible = true }.set(null, db)
        db.uploadServerDao().insert(UploadServerEntity(name = "study", url = "https://localhost",
            studyId = prefs.getString(STUDY_ID, "")!!, participantId = "participant", sourceDeviceId = "device", apiKey = "test-key"))
    }

    @After fun tearDown() {
        ResearchPersistenceGate.resetAfterLocalStoreRecovery()
        WorkSchedulingStatus::class.java.getDeclaredField("unavailable").apply { isAccessible = true }.setBoolean(WorkSchedulingStatus, false)
        installPrefs(null)
        ChronicleDb::class.java.getDeclaredField("INSTANCE").apply { isAccessible = true }.set(null, null)
        db.close()
    }

    @Test fun securePreferenceFailureRoutesLauncherAndIdentificationToRecovery() {
        installPrefs(null) // Robolectric deliberately has no AndroidKeyStore.
        val main = Robolectric.buildActivity(MainActivity::class.java).create().get()
        assertTrue(main.isFinishing)
        assertEquals(LocalStoreRecoveryActivity::class.java.name, shadowOf(main).nextStartedActivity.component?.className)
        val identify = Robolectric.buildActivity(UserIdentificationActivity::class.java).create().get()
        assertTrue(identify.isFinishing)
        assertEquals(LocalStoreRecoveryActivity::class.java.name, shadowOf(identify).nextStartedActivity.component?.className)
    }

    @Test fun powerSaveReceiverContainsSecurePreferenceFailure() {
        org.junit.Assume.assumeTrue(BuildConfig.ALLOW_RESTRICTED_RESEARCH_PERMISSIONS)
        installPrefs(null)
        com.openlattice.chronicle.receivers.lifecycle.PowerSaveModeReceiver().onReceive(context,
            Intent("android.os.action.POWER_SAVE_MODE_CHANGED"))
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
        UnlockMonitoringRuntimeStatus.markDeferred(context, true)
        assertFalse(prefs.getBoolean("unlock_monitoring_start_deferred", false))
        installPrefs(null)
        UnlockMonitoringRuntimeStatus.markDeferred(context, true)
    }

    @Test fun orphanedScopeCleanupFailureReportsRecoveryAndRetainsIdentity() {
        db.openHelper.writableDatabase.execSQL("DELETE FROM upload_servers")
        HealthConnectScopeStore.of(context).replace(setOf(HealthConnectRecordType.STEPS))
        failCommits()
        val settings = EnrollmentSettings(context)
        try {
            com.openlattice.chronicle.collection.state.onPersistenceWorker { settings.isEnrolled() }
            fail("failed reconciliation must report storage recovery")
        } catch (error: LocalStoreRecoveryRequiredException) {
            assertNotNull(error.cause)
        }
        assertEquals("participant", settings.getParticipantId())
        assertEquals("participant", prefs.getString(PARTICIPANT_ID, null))
        assertEquals(setOf(HealthConnectRecordType.STEPS.id), prefs.getStringSet("health_connect_study_record_types", null))
    }

    @Test fun optionalDiagnosticsImportFailureKeepsLegacyDataAndRendersUnavailable() = runBlocking {
        prefs.edit().putString("local_upload_issue_history", "[]").commit()
        db.openHelper.writableDatabase.execSQL("CREATE TRIGGER fail_import BEFORE INSERT ON diagnostic_import_checkpoints BEGIN SELECT RAISE(ABORT, 'disk full'); END")
        val snapshot = DashboardDataRepository.load(context)
        assertFalse(snapshot.localUploadDiagnosticsAvailable)
        assertEquals("[]", prefs.getString("local_upload_issue_history", null))
        db.openHelper.writableDatabase.execSQL("DROP TRIGGER fail_import")
        assertTrue(DashboardDataRepository.load(context).localUploadDiagnosticsAvailable)
        assertNull(prefs.getString("local_upload_issue_history", null))
    }

    @Test fun legacyCleanupCommitFailureKeepsCheckpointForIdempotentRetry() = runBlocking {
        prefs.edit().putString("local_upload_issue_history", "[]").commit()
        failCommits()
        assertFalse(DashboardDataRepository.load(context).localUploadDiagnosticsAvailable)
        assertEquals("[]", prefs.getString("local_upload_issue_history", null))
        installPrefs(prefs)
        assertTrue(DashboardDataRepository.load(context).localUploadDiagnosticsAvailable)
        assertEquals(1, db.openHelper.readableDatabase.query("SELECT COUNT(*) FROM diagnostic_import_checkpoints").use { it.moveToFirst(); it.getInt(0) })
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
            decidedAtEpochMillis = 1, requiredApplied = false, appliedVersion = 1,
            appliedPolicySnapshot = null, lastDisposition = null)))
        db.openHelper.writableDatabase.execSQL("CREATE TRIGGER fail_consent BEFORE INSERT ON collection_module_state BEGIN SELECT RAISE(ABORT, 'disk full'); END")
        val host = Robolectric.buildActivity(CrashTestHostActivity::class.java).setup().get()
        val fragment = DataSharingFragment()
        host.supportFragmentManager.beginTransaction().add(android.R.id.content, fragment).commitNow()
        invoke(fragment, "applyRequiredDecision", arrayOf(Set::class.java, Set::class.java, Set::class.java),
            emptySet<CollectionModuleId>(), setOf(CollectionModuleId.CONNECTIVITY_STATE), null)
        awaitUi { host.isFinishing }
        assertFalse(ResearchPersistenceGate.isActiveEnrollment(context))
        assertEquals("ACCEPTED", db.collectionModuleStateDao().getAll().single().decision)
    }

    @Test fun failedPrivacyMutationClosesAdmissionBeforeItsGateLeaseIsReleased() {
        db.openHelper.writableDatabase.execSQL("CREATE TRIGGER fail_privacy BEFORE UPDATE ON upload_servers BEGIN SELECT RAISE(ABORT, 'disk full'); END")
        try {
            com.openlattice.chronicle.collection.state.stopOnPersistenceWorker { db.uploadServerDao().setEnabled(db.uploadServerDao().getConfiguredServer()!!.id, false) }
            fail("the failed mutation must be reported")
        } catch (_: android.database.SQLException) {
            assertFalse(ResearchPersistenceGate.isActiveEnrollment(context))
            assertTrue(db.uploadServerDao().getConfiguredServer()!!.enabled)
        }
    }

    @Test fun failedServerToggleShowsRecoveryAndKeepsRow() {
        db.openHelper.writableDatabase.execSQL("CREATE TRIGGER fail_server BEFORE UPDATE ON upload_servers BEGIN SELECT RAISE(ABORT, 'disk full'); END")
        val host = Robolectric.buildActivity(CrashTestHostActivity::class.java).setup().get()
        val fragment = SettingsHomeFragment()
        host.supportFragmentManager.beginTransaction().add(android.R.id.content, fragment).commitNow()
        invoke(fragment, "setServerEnabled", arrayOf(java.lang.Long.TYPE, java.lang.Boolean.TYPE),
            db.uploadServerDao().getConfiguredServer()!!.id, false)
        awaitUi { host.isFinishing }
        assertTrue(db.uploadServerDao().getConfiguredServer()!!.enabled)
    }

    @Test fun serverEditorReadFailuresBecomeUiErrors() {
        org.junit.Assume.assumeTrue(BuildConfig.DISTRIBUTION_CHANNEL == "RESEARCH")
        val activity = Robolectric.buildActivity(ServerEnrollmentActivity::class.java).setup().get()
        db.openHelper.writableDatabase.execSQL("DROP TABLE upload_servers")
        invoke(activity, "loadServer", arrayOf(java.lang.Long.TYPE), 1L)
        awaitExecutor(activity)
        awaitUi { activity.findViewById<View>(R.id.serverStatusMessage).visibility == View.VISIBLE }
        activity.javaClass.getDeclaredField("editServerId").apply { isAccessible = true }.setLong(activity, 1L)
        invoke(activity, "doDelete")
        awaitExecutor(activity)
        assertFalse(activity.isFinishing)
    }

    @Test fun serverDeleteReadFailureBecomesUiError() {
        org.junit.Assume.assumeTrue(BuildConfig.DISTRIBUTION_CHANNEL == "RESEARCH")
        val activity = Robolectric.buildActivity(ServerEnrollmentActivity::class.java).setup().get()
        activity.javaClass.getDeclaredField("editServerId").apply { isAccessible = true }.setLong(activity, 1L)
        db.openHelper.writableDatabase.execSQL("DROP TABLE upload_servers")
        invoke(activity, "doDelete")
        awaitExecutor(activity)
        awaitUi { activity.findViewById<View>(R.id.serverStatusMessage).visibility == View.VISIBLE }
        assertFalse(activity.isFinishing)
    }

    @Test fun destroyedEnrollmentDiscardsQueuedLauncherCompletion() {
        prefs.edit().remove(STUDY_ID).remove(PARTICIPANT_ID).commit()
        val controller = Robolectric.buildActivity(Enrollment::class.java).setup()
        val activity = controller.get()
        awaitExecutor(activity)
        shadowOf(Looper.getMainLooper()).idle()
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val executor = executor(activity)
        executor.execute { entered.countDown(); release.await(5, TimeUnit.SECONDS) }
        assertTrue(entered.await(5, TimeUnit.SECONDS))
        var delivered = false
        invoke(activity, "postIfCurrent", arrayOf(Function0::class.java), { delivered = true })
        controller.pause().stop().destroy()
        release.countDown()
        shadowOf(Looper.getMainLooper()).idle()
        assertFalse("a destroyed activity must not launch its disclosure UI", delivered)
    }

    @Test fun destroyedServerEditorDiscardsQueuedDeleteDialog() {
        org.junit.Assume.assumeTrue(BuildConfig.DISTRIBUTION_CHANNEL == "RESEARCH")
        val controller = Robolectric.buildActivity(ServerEnrollmentActivity::class.java).setup()
        val activity = controller.get()
        activity.javaClass.getDeclaredField("editServerId").apply { isAccessible = true }.setLong(activity, 1L)
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        executor(activity).execute { entered.countDown(); release.await(5, TimeUnit.SECONDS) }
        assertTrue(entered.await(5, TimeUnit.SECONDS))
        invoke(activity, "doDelete")
        controller.pause().stop().destroy()
        release.countDown()
        assertTrue(executor(activity).awaitTermination(5, TimeUnit.SECONDS))
        shadowOf(Looper.getMainLooper()).idle()
        assertNull(shadowOf(activity).nextStartedActivity)
        assertNull(org.robolectric.shadows.ShadowDialog.getLatestDialog())
    }

    @Test fun opaqueAndWrongDestinationEnrollmentIntentsAreRejectedBeforeQueryParsing() {
        prefs.edit().remove(STUDY_ID).remove(PARTICIPANT_ID).commit()
        val activity = Robolectric.buildActivity(Enrollment::class.java).setup().get()
        awaitExecutor(activity)
        shadowOf(Looper.getMainLooper()).idle()
        for (uri in listOf("chronicle:enroll", "chronicle://other", "https://enroll", "chronicle://enroll/other")) {
            invoke(activity, "handleIntent", arrayOf(Intent::class.java, String::class.java), Intent(Intent.ACTION_VIEW, Uri.parse(uri)), null)
            assertEquals(context.getString(R.string.enrollment_invitation_unverified),
                activity.findViewById<android.widget.TextView>(R.id.statusMessage).text.toString())
        }
    }

    private fun installPrefs(value: SharedPreferences?) {
        EncryptedPrefsHelper::class.java.getDeclaredField("instance").apply { isAccessible = true }.set(EncryptedPrefsHelper, value)
    }

    private fun failCommits() {
        val editor = Proxy.newProxyInstance(SharedPreferences.Editor::class.java.classLoader, arrayOf(SharedPreferences.Editor::class.java)) { proxy, method, _ ->
            if (method.name == "commit") false else proxy
        } as SharedPreferences.Editor
        installPrefs(Proxy.newProxyInstance(SharedPreferences::class.java.classLoader, arrayOf(SharedPreferences::class.java)) { _, method, args ->
            if (method.name == "edit") editor else method.invoke(prefs, *(args ?: emptyArray()))
        } as SharedPreferences)
    }

    private fun executor(activity: Any): ExecutorService = activity.javaClass.getDeclaredField("executor").apply { isAccessible = true }.get(activity) as ExecutorService
    private fun awaitExecutor(activity: Any) { executor(activity).submit {}.get(5, TimeUnit.SECONDS) }
    private fun invoke(target: Any, name: String, types: Array<Class<*>> = emptyArray(), vararg args: Any?) {
        try { target.javaClass.getDeclaredMethod(name, *types).apply { isAccessible = true }.invoke(target, *args) }
        catch (error: InvocationTargetException) { throw error.targetException }
    }
    private fun awaitUi(predicate: () -> Boolean) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
        while (!predicate() && System.nanoTime() < deadline) { shadowOf(Looper.getMainLooper()).idle(); Thread.sleep(10) }
        assertTrue("UI result must arrive", predicate())
    }
}

class CrashTestHostActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: android.os.Bundle?) {
        setTheme(R.style.AppTheme)
        super.onCreate(savedInstanceState)
    }
}
