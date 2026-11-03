package com.openlattice.chronicle

import android.app.Activity
import android.app.Application
import android.app.Notification
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.view.View
import androidx.test.core.app.ApplicationProvider
import androidx.work.WorkManager
import com.google.android.material.checkbox.MaterialCheckBox
import com.openlattice.chronicle.api.EnrollmentPreviewResponse
import com.openlattice.chronicle.api.EnrollmentResponse
import com.openlattice.chronicle.audit.studyApi
import com.openlattice.chronicle.base.OK
import com.openlattice.chronicle.collection.CollectionModuleId
import com.openlattice.chronicle.collection.CollectionModuleSetting
import com.openlattice.chronicle.collection.device.EXPANSION_COLLECTION_WORK_NAME
import com.openlattice.chronicle.collection.state.CollectionLoopCoordinator
import com.openlattice.chronicle.collection.state.SettingsApplicationOutcome
import com.openlattice.chronicle.collection.state.CollectionOrientationActivity
import com.openlattice.chronicle.collection.state.ResearchErasureFence
import com.openlattice.chronicle.collection.state.ResearchPersistenceGate
import com.openlattice.chronicle.collection.state.validateEnrollmentModulePartition
import com.openlattice.chronicle.preferences.EnrollmentSettings
import com.openlattice.chronicle.services.sync.CHRONICLE_SYNC_WORK_NAME
import com.openlattice.chronicle.storage.ChronicleDb
import com.openlattice.chronicle.ui.awaitDashboardUi
import java.time.OffsetDateTime
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
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
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowLog

/** Runs on the actual Play, Open and Research source graphs, starting with empty local stores. */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [28], shadows = [
    EnrollmentTestStores.Database::class, EnrollmentTestStores.Preferences::class, EnrollmentTestStores.StudyApi::class])
class FreshEnrollmentNavigationRegressionTest {
    @get:Rule val workManagerRule = WorkManagerRule()
    private val context: Context = ApplicationProvider.getApplicationContext()

    private fun <T> worker(action: () -> T): T {
        val future = CompletableFuture<T>()
        ResearchPersistenceGate.executeAsync {
            try { future.complete(action()) } catch (error: Throwable) { future.completeExceptionally(error) }
        }
        return future.get(10, TimeUnit.SECONDS)
    }

    @Test fun row3_freshInstallEnrollsAndDoneReachesMainWithCollectionScheduled() {
        val db = installStores()
        val module = CollectionModuleId.BATTERY_TELEMETRY
        val preview = EnrollmentTestStores.enrollmentPreview(
            enabled = setOf(module, CollectionModuleId.UPLOAD_TELEMETRY), issuedAt = OffsetDateTime.now())
        val enrollments = AtomicInteger()
        installApi(preview, enrollments).use {
            EnrollmentTestStores.expectedApiRequest = EnrollmentTestStores.ApiRequest(
                EnrollmentTestStores.TEST_SERVER_URL,
            )
            val invitation = Intent(context, Enrollment::class.java).setAction(Intent.ACTION_VIEW).setData(Uri.parse(
                "chronicle://enroll?studyId=${preview.manifest.studyId}&participantId=${preview.manifest.participantId}" +
                    "&serverUrl=https%3A%2F%2Flocalhost#accessCode=${"A".repeat(64)}"))
            val enrollmentController = Robolectric.buildActivity(Enrollment::class.java, invitation).setup()
            val enrollment = enrollmentController.get()
            var mainController: ActivityController<MainActivity>? = null
            try {
                waitUntil { enrollment.findViewById<View>(R.id.button).isEnabled }
                assertTrue(enrollment.findViewById<View>(R.id.button).performClick())
                waitUntil { shadowOf(enrollment).peekNextStartedActivityForResult() != null }
                acceptDisclosure(enrollment)
                acceptOrientation(enrollment)
                waitUntil { enrollment.findViewById<View>(R.id.doneButton).visibility == View.VISIBLE }
                assertEquals(1, enrollments.get())
                assertTrue(worker { db.uploadServerDao().getConfiguredServer()!!.enrollmentSetupComplete })
                assertScheduledBackgroundWork()
                assertTrue(enrollment.findViewById<View>(R.id.doneButton).performClick())
                val mainIntent = shadowOf(enrollment).nextStartedActivity
                assertEquals(MainActivity::class.java.name, mainIntent.component!!.className)
                while (shadowOf(enrollment).nextStartedActivity != null) { /* consume prior disclosure/consent launches */ }
                mainController = Robolectric.buildActivity(MainActivity::class.java, mainIntent).setup()
                val main = mainController.get()
                assertNull("Done must reach the dashboard without returning to enrollment", shadowOf(main).nextStartedActivity)
                assertTrue(main.findViewById<View>(R.id.mainBottomNav).isShown)
                assertTrue(EnrollmentSettings(main).isEnrolled())
                assertScheduledCollection(module)
                assertOnlyALaterModuleIsAnnounced(preview)
            } finally {
                mainController?.pause()?.stop()?.destroy()
                enrollmentController.pause().stop().destroy()
                closeStores(db)
            }
        }
    }

    @Test fun rotationWhileReservationIsPendingStillReceivesSubmissionCompletion() {
        val db = installStores()
        val preview = EnrollmentTestStores.enrollmentPreview(
            enabled = setOf(CollectionModuleId.BATTERY_TELEMETRY, CollectionModuleId.UPLOAD_TELEMETRY))
        val submitted = CountDownLatch(1)
        val release = CountDownLatch(1)
        try {
            installApi(preview, AtomicInteger(), onEnrolling = {
                submitted.countDown()
                check(release.await(10, TimeUnit.SECONDS))
            }).use {
                val invitation = Intent(context, Enrollment::class.java).setAction(Intent.ACTION_VIEW).setData(Uri.parse(
                    "chronicle://enroll?studyId=${preview.manifest.studyId}&participantId=${preview.manifest.participantId}" +
                        "&serverUrl=https%3A%2F%2Flocalhost#accessCode=${"A".repeat(64)}"))
                androidx.test.core.app.ActivityScenario.launch<Enrollment>(invitation).use { scenario ->
                    lateinit var activity: Enrollment
                    scenario.onActivity { activity = it }
                    waitUntil { activity.findViewById<View>(R.id.button).isEnabled }
                    activity.findViewById<View>(R.id.button).performClick()
                    waitUntil { shadowOf(activity).peekNextStartedActivityForResult() != null }
                    acceptDisclosure(activity)
                    waitUntil { shadowOf(activity).peekNextStartedActivityForResult() != null }
                    acceptOrientation(activity)
                    waitUntil("Enrollment submission did not reach the server") { submitted.count == 0L }
                    scenario.recreate()
                    scenario.onActivity { activity = it }
                    release.countDown()
                    waitUntil { worker { db.uploadServerDao().getConfiguredServer()?.enrollmentSetupComplete == true } }
                    waitUntil("A rotated submission must receive completion") {
                        activity.findViewById<View>(R.id.doneButton).visibility == View.VISIBLE
                    }
                }
            }
        } finally {
            release.countDown()
            closeStores(db)
        }
    }

    @Test fun pendingEnrollmentRecoverySchedulesWorkWithoutOpeningTheDashboard() {
        val db = installStores()
        val preview = EnrollmentTestStores.enrollmentPreview()
        val settings = preview.manifest.collectionSettings
        try {
            worker {
                reserveEnrollment(context, EnrollmentSubmission(
                    preview = preview,
                    partition = validateEnrollmentModulePartition(settings, settings.effectiveEnabledModuleIds(), emptySet(), emptySet()),
                    serverUrl = preview.manifest.serverOrigin,
                    enrollmentAccessCode = "recovery-invitation",
                ))
            }
            installApi(preview, AtomicInteger()).use {
                assertEquals(EnrollmentRecoveryResult.COMPLETED, worker { EnrollmentRecoveryManager.resumeIfNeeded(context) })
                assertScheduledBackgroundWork()
            }
        } finally {
            closeStores(db)
        }
    }

    /**
     * Local-store recovery rebuilt the stores, and the identity file of the earlier install is unreadable (here:
     * Robolectric has no AndroidKeyStore, as a lost keyset has none). Enrollment needs nothing from it and completes.
     */
    @Test fun enrollmentAfterLocalStoreRecoveryCompletes() {
        val db = installStores()
        context.getSharedPreferences("chronicle_random_device_id_prefs", Context.MODE_PRIVATE).edit()
            .putString("unreadable", "ciphertext under a lost keyset").commit()
        val preview = EnrollmentTestStores.enrollmentPreview()
        val settings = preview.manifest.collectionSettings
        val enrollments = AtomicInteger()
        try {
            worker {
                reserveEnrollment(context, EnrollmentSubmission(
                    preview = preview,
                    partition = validateEnrollmentModulePartition(settings, settings.effectiveEnabledModuleIds(), emptySet(), emptySet()),
                    serverUrl = preview.manifest.serverOrigin,
                    enrollmentAccessCode = "after-recovery",
                ))
            }
            installApi(preview, enrollments).use {
                assertEquals(EnrollmentRecoveryResult.COMPLETED, worker { EnrollmentRecoveryManager.resumeIfNeeded(context) })
            }
            assertEquals(1, enrollments.get())
        } finally {
            closeStores(db)
        }
    }

    /** Opening Chronicle after enrollment crashed on a main-thread Room read in the work reconcile. */
    @Test fun anEnrolledParticipantOpensTheDashboardAndItsWorkIsScheduled() {
        val db = installStores(enrolled = true)
        var mainController: ActivityController<MainActivity>? = null
        try {
            mainController = Robolectric.buildActivity(MainActivity::class.java).setup()
            val main = mainController.get()
            waitUntil("the dashboard did not open") {
                main.supportFragmentManager.findFragmentById(R.id.mainFragmentContainer) != null
            }
            waitUntil("the enrolled work was not reconciled") {
                WorkManager.getInstance(context).getWorkInfosForUniqueWork(CHRONICLE_SYNC_WORK_NAME).get(5, TimeUnit.SECONDS)
                    .any { !it.state.isFinished }
            }
        } finally {
            mainController?.pause()?.stop()?.destroy()
            closeStores(db)
        }
    }

    @Test fun completedEnrollmentReentryRepairsMissingBackgroundSchedules() {
        val db = installStores(enrolled = true)
        try {
            assertEquals(EnrollmentRecoveryResult.NONE, worker { EnrollmentRecoveryManager.resumeIfNeeded(context) })
            assertScheduledBackgroundWork()
        } finally {
            closeStores(db)
        }
    }

    /** Room refuses main-thread reads here, as on a phone: these flows cross the enrollment and dashboard entry points. */
    private fun installStores(enrolled: Boolean = false): ChronicleDb {
        val db = EnrollmentTestStores.install(context, enrolled = enrolled, mainThreadQueries = false)
        worker { ResearchPersistenceGate.initialize(context) }
        return db
    }

    private fun closeStores(db: ChronicleDb) {
        worker { }
        ResearchPersistenceGate.resetAfterLocalStoreRecovery()
        context.getSystemService(NotificationManager::class.java).cancelAll()
        db.close()
    }

    private fun assertScheduledBackgroundWork() {
        val wm = WorkManager.getInstance(context)
        EnrolledWork.uniqueWorkNames().forEach { name ->
            assertTrue("$name must be scheduled before enrollment success",
                wm.getWorkInfosForUniqueWork(name).get(5, TimeUnit.SECONDS).any { !it.state.isFinished })
        }
    }

    private fun assertScheduledCollection(module: CollectionModuleId) {
        val wm = WorkManager.getInstance(context)
        assertTrue(wm.getWorkInfosForUniqueWork(CHRONICLE_SYNC_WORK_NAME).get(5, TimeUnit.SECONDS).isNotEmpty())
        assertTrue(wm.getWorkInfosForUniqueWork(EXPANSION_COLLECTION_WORK_NAME).get(5, TimeUnit.SECONDS).isNotEmpty())
        assertTrue("accepted collection is ready once enrollment completes", worker { ResearchPersistenceGate.collectsNow(context, module) })
    }

    /**
     * H-FRESH-CONSENT-NOTICE: the modules decided during enrollment post no "a data collection option was added" notice;
     * a module the study adds afterwards posts exactly one, and the next poll of the same revision posts none.
     */
    private fun assertOnlyALaterModuleIsAnnounced(preview: EnrollmentPreviewResponse) {
        val manager = context.getSystemService(NotificationManager::class.java)
        fun announced() = shadowOf(manager).allNotifications.count {
            it.extras.getCharSequence(Notification.EXTRA_TITLE)?.toString() == context.getString(R.string.notif_action_needed)
        }
        assertEquals("fresh enrollment announces nothing", 0, announced())
        val enrolled = preview.manifest.collectionSettings
        val later = enrolled.copy(settingsVersion = enrolled.settingsVersion + 1,
            modules = enrolled.modules + (CollectionModuleId.DEVICE_LIFECYCLE to CollectionModuleSetting(enabled = true)))
        fun apply() = assertEquals(SettingsApplicationOutcome.APPLIED, worker {
            val server = ChronicleDb.getInstance(context).uploadServerDao().getConfiguredServer()!!
            CollectionLoopCoordinator(context).applyFetchedSettings(server, ResearchErasureFence(context).settingsGeneration(), later)
        })
        apply()
        assertEquals("a module added later is announced once", 1, announced())
        manager.cancelAll()
        apply()
        assertEquals("the same revision is not announced again", 0, announced())
    }

    private fun installApi(preview: EnrollmentPreviewResponse, enrollments: AtomicInteger, onEnrolling: () -> Unit = {}) =
        EnrollmentTestStores.installApi(studyApi { method, args ->
            when (method) {
                "getEnrollmentPreview" -> preview
                "enroll" -> {
                    enrollments.incrementAndGet()
                    onEnrolling()
                    EnrollmentResponse(UUID.randomUUID(), apiKey = args!![7] as String)
                }
                "reportCollectionAck" -> OK()
                else -> error("unexpected study API $method")
            }
        })

    private fun acceptDisclosure(enrollment: Enrollment) {
        val disclosureIntent = shadowOf(enrollment).nextStartedActivityForResult.intent
        val disclosureController = Robolectric.buildActivity(StudyDisclosureActivity::class.java, disclosureIntent).setup()
        val disclosure = disclosureController.get()
        assertFalse(disclosure.findViewById<View>(R.id.acceptStudyDisclosureButton).isEnabled)
        disclosure.findViewById<MaterialCheckBox>(R.id.studyEligibilityAttestation).isChecked = true
        assertTrue(disclosure.findViewById<View>(R.id.acceptStudyDisclosureButton).performClick())
        assertEquals(Activity.RESULT_OK, shadowOf(disclosure).resultCode)
        shadowOf(enrollment).receiveResult(disclosureIntent, shadowOf(disclosure).resultCode, shadowOf(disclosure).resultIntent)
        disclosureController.pause().stop().destroy()
    }

    private fun acceptOrientation(enrollment: Enrollment) {
        val orientationIntent = shadowOf(enrollment).nextStartedActivityForResult.intent
        val orientationController = Robolectric.buildActivity(CollectionOrientationActivity::class.java, orientationIntent).setup()
        val orientation = orientationController.get()
        repeat(CollectionModuleId.values().size) {
            if (!orientation.isFinishing) assertTrue(orientation.findViewById<View>(R.id.orientationAccept).performClick())
        }
        assertEquals(Activity.RESULT_OK, shadowOf(orientation).resultCode)
        shadowOf(enrollment).receiveResult(orientationIntent, shadowOf(orientation).resultCode, shadowOf(orientation).resultIntent)
        orientationController.pause().stop().destroy()
    }

    private fun waitUntil(message: String = "enrollment did not advance before the deadline", predicate: () -> Boolean) {
        awaitDashboardUi(attempts = 800, condition = predicate)
        assertTrue("$message: ${ShadowLog.getLogs().takeLast(12)}", predicate())
    }

}
