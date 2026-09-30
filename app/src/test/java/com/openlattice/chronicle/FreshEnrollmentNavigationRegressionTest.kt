package com.openlattice.chronicle

import android.app.Activity
import android.app.Application
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Looper
import android.view.View
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.work.*
import androidx.work.impl.utils.taskexecutor.WorkManagerTaskExecutor
import com.openlattice.chronicle.api.ChronicleStudyApi
import com.openlattice.chronicle.api.EnrollmentPreviewResponse
import com.openlattice.chronicle.api.EnrollmentResponse
import com.openlattice.chronicle.collection.CollectionModuleId
import com.openlattice.chronicle.collection.CollectionModuleSetting
import com.openlattice.chronicle.collection.device.EXPANSION_COLLECTION_WORK_NAME
import com.openlattice.chronicle.collection.state.CollectionOrientationActivity
import com.openlattice.chronicle.collection.state.MinimalPlayArtifactState
import com.openlattice.chronicle.collection.state.ResearchPersistenceGate
import com.openlattice.chronicle.preferences.EncryptedPrefsHelper
import com.openlattice.chronicle.preferences.EnrollmentSettings
import com.openlattice.chronicle.preferences.DeviceInstanceIdentity
import com.openlattice.chronicle.serialization.ChronicleJson
import com.openlattice.chronicle.services.release.MinimalPlayBoundaryWorker
import com.openlattice.chronicle.services.sync.CHRONICLE_SYNC_WORK_NAME
import com.openlattice.chronicle.services.upload.UploadWorker
import com.openlattice.chronicle.storage.ChronicleDb
import com.openlattice.chronicle.utils.Utils
import java.lang.reflect.Proxy
import java.time.OffsetDateTime
import java.util.UUID
import java.util.concurrent.*
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements

/** Runs on the actual Play, Open and Research source graphs, starting with empty local stores. */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [28], shadows = [FreshEnrollmentNavigationRegressionTest.ShadowDeviceIdentity::class])
class FreshEnrollmentNavigationRegressionTest {
    private val context: Context = ApplicationProvider.getApplicationContext()

    // Robolectric has no AndroidKeyStore; keep the enrollment protocol and local writes real.
    @Implements(value = DeviceInstanceIdentity::class, isInAndroidSdk = false)
    class ShadowDeviceIdentity {
        @Implementation fun getOrCreate(context: Context): String = "22222222-2222-2222-2222-222222222222"
    }

    private fun <T> worker(action: () -> T): T {
        val future = CompletableFuture<T>()
        ResearchPersistenceGate.executeAsync {
            try { future.complete(action()) } catch (error: Throwable) { future.completeExceptionally(error) }
        }
        return future.get(10, TimeUnit.SECONDS)
    }

    @Test fun row3_freshInstallEnrollsAndDoneReachesMainWithCollectionScheduled() {
        ResearchPersistenceGate.resetAfterLocalStoreRecovery()
        val prefs = context.getSharedPreferences("fresh-enrollment-navigation", Context.MODE_PRIVATE)
        prefs.edit().clear().commit()
        context.getSharedPreferences("minimal_play_artifact_state", Context.MODE_PRIVATE).edit().clear().commit()
        val prefsField = EncryptedPrefsHelper::class.java.getDeclaredField("instance").apply { isAccessible = true }
        prefsField.set(EncryptedPrefsHelper, prefs)
        val db = Room.inMemoryDatabaseBuilder(context, ChronicleDb::class.java).build()
        val dbField = ChronicleDb::class.java.getDeclaredField("INSTANCE").apply { isAccessible = true }
        dbField.set(null, db)
        worker { ResearchPersistenceGate.initialize(context) }
        if (!WorkManager.isInitialized()) WorkManager.initialize(context, Configuration.Builder().setExecutor(Executor { }).build())
        val parsed = ChronicleJson.moshi.adapter(EnrollmentPreviewResponse::class.java)
            .fromJson(javaClass.getResource("/enrollment-preview.json")!!.readText())!!
        val module = CollectionModuleId.BATTERY_TELEMETRY
        val preview = parsed.copy(manifest = parsed.manifest.copy(
            serverOrigin = "https://localhost", issuedAt = OffsetDateTime.now(), expiresAt = OffsetDateTime.now().plusHours(1),
            collectionSettings = parsed.manifest.collectionSettings.copy(modules = CollectionModuleId.values().associateWith {
                CollectionModuleSetting(enabled = it == module || it == CollectionModuleId.UPLOAD_TELEMETRY)
            }),
        ))
        val enrollments = AtomicInteger()
        val api = Proxy.newProxyInstance(ChronicleStudyApi::class.java.classLoader, arrayOf(ChronicleStudyApi::class.java)) { _, method, args ->
            when (method.name) {
                "getEnrollmentPreview" -> preview
                "enroll" -> {
                    enrollments.incrementAndGet()
                    EnrollmentResponse(UUID.randomUUID(), apiKey = args!![7] as String)
                }
                "reportCollectionAck" -> Unit
                else -> error("unexpected study API ${method.name}")
            }
        } as ChronicleStudyApi
        @Suppress("UNCHECKED_CAST")
        val cache = UploadWorker::class.java.getDeclaredField("studyApiCache").apply { isAccessible = true }
            .get(null) as MutableMap<String, ChronicleStudyApi>
        cache["https://localhost|${Utils.mobileSigningSecretFingerprint(null)}"] = api
        val invitation = Intent(context, Enrollment::class.java).setAction(Intent.ACTION_VIEW).setData(Uri.parse(
            "chronicle://enroll?studyId=${preview.manifest.studyId}&participantId=${preview.manifest.participantId}" +
                "&serverUrl=https%3A%2F%2Flocalhost#accessCode=${"A".repeat(64)}"))
        val enrollmentController = Robolectric.buildActivity(Enrollment::class.java, invitation).setup()
        val enrollment = enrollmentController.get()
        var mainController: org.robolectric.android.controller.ActivityController<MainActivity>? = null
        try {
            waitUntil { enrollment.findViewById<View>(R.id.button).isEnabled }
            assertTrue(enrollment.findViewById<View>(R.id.button).performClick())
            waitUntil { shadowOf(enrollment).peekNextStartedActivityForResult() != null }
            val disclosureIntent = shadowOf(enrollment).nextStartedActivityForResult.intent
            val disclosureController = Robolectric.buildActivity(StudyDisclosureActivity::class.java, disclosureIntent).setup()
            val disclosure = disclosureController.get()
            assertTrue(disclosure.findViewById<View>(R.id.acceptStudyDisclosureButton).performClick())
            shadowOf(enrollment).receiveResult(disclosureIntent, shadowOf(disclosure).resultCode, shadowOf(disclosure).resultIntent)
            disclosureController.pause().stop().destroy()
            val orientationIntent = shadowOf(enrollment).nextStartedActivityForResult.intent
            val orientationController = Robolectric.buildActivity(CollectionOrientationActivity::class.java, orientationIntent).setup()
            val orientation = orientationController.get()
            repeat(CollectionModuleId.values().size) {
                if (!orientation.isFinishing) assertTrue(orientation.findViewById<View>(R.id.orientationAccept).performClick())
            }
            assertEquals(Activity.RESULT_OK, shadowOf(orientation).resultCode)
            shadowOf(enrollment).receiveResult(orientationIntent, shadowOf(orientation).resultCode, shadowOf(orientation).resultIntent)
            orientationController.pause().stop().destroy()
            waitUntil { enrollment.findViewById<View>(R.id.doneButton).visibility == View.VISIBLE }
            assertEquals(1, enrollments.get())
            assertTrue(worker { db.uploadServerDao().getConfiguredServer()!!.enrollmentSetupComplete })
            if (BuildConfig.DISTRIBUTION_CHANNEL == "PLAY") {
                assertFalse("fresh Play collection stays closed until boundary cleanup", MinimalPlayArtifactState.isReady(context))
                assertFalse(ResearchPersistenceGate.collectsNow(context, module))
            }
            assertTrue(enrollment.findViewById<View>(R.id.doneButton).performClick())
            val mainIntent = shadowOf(enrollment).nextStartedActivity
            assertEquals(MainActivity::class.java.name, mainIntent.component!!.className)
            while (shadowOf(enrollment).nextStartedActivity != null) { /* consume prior disclosure/consent launches */ }
            mainController = Robolectric.buildActivity(MainActivity::class.java, mainIntent).setup()
            val main = mainController.get()
            assertNull("Done must reach the dashboard without returning to enrollment", shadowOf(main).nextStartedActivity)
            assertTrue(main.findViewById<View>(R.id.mainBottomNav).isShown)
            assertTrue(EnrollmentSettings(main).isEnrolled())
            val wm = WorkManager.getInstance(context)
            assertTrue(wm.getWorkInfosForUniqueWork(CHRONICLE_SYNC_WORK_NAME).get(5, TimeUnit.SECONDS).isNotEmpty())
            assertTrue(wm.getWorkInfosForUniqueWork(EXPANSION_COLLECTION_WORK_NAME).get(5, TimeUnit.SECONDS).isNotEmpty())
            if (BuildConfig.DISTRIBUTION_CHANNEL == "PLAY") {
                val boundary = wm.getWorkInfosForUniqueWork("minimal_play_artifact_boundary").get(5, TimeUnit.SECONDS).single()
                assertEquals(WorkInfo.State.ENQUEUED, boundary.state)
                assertEquals(ListenableWorker.Result.success(), worker { MinimalPlayBoundaryWorker(context, workerParameters()).doWork() })
                assertTrue(MinimalPlayArtifactState.isReady(context))
            }
            assertTrue("accepted collection must become ready after boundary reconciliation", ResearchPersistenceGate.collectsNow(context, module))
        } finally {
            mainController?.pause()?.stop()?.destroy()
            enrollmentController.pause().stop().destroy()
            enrollment.javaClass.getDeclaredField("executor").apply { isAccessible = true }.get(enrollment).let {
                assertTrue((it as ExecutorService).awaitTermination(5, TimeUnit.SECONDS))
            }
            worker { }
            cache.clear()
            ResearchPersistenceGate.resetAfterLocalStoreRecovery()
            dbField.set(null, null); prefsField.set(EncryptedPrefsHelper, null); db.close()
        }
    }

    private fun waitUntil(predicate: () -> Boolean) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(8)
        while (!predicate() && System.nanoTime() < deadline) {
            shadowOf(Looper.getMainLooper()).idle()
            Thread.sleep(10)
        }
        assertTrue("enrollment did not advance before the deadline: ${org.robolectric.shadows.ShadowLog.getLogs().takeLast(12)}", predicate())
    }

    private fun workerParameters(): WorkerParameters {
        val executor = Executor { }
        val progress = Proxy.newProxyInstance(ProgressUpdater::class.java.classLoader, arrayOf(ProgressUpdater::class.java)) { _, _, _ ->
            error("no progress expected")
        } as ProgressUpdater
        val foreground = Proxy.newProxyInstance(ForegroundUpdater::class.java.classLoader, arrayOf(ForegroundUpdater::class.java)) { _, _, _ ->
            error("no foreground expected")
        } as ForegroundUpdater
        val factory = object : WorkerFactory() {
            override fun createWorker(appContext: Context, workerClassName: String, workerParameters: WorkerParameters): ListenableWorker? = null
        }
        return WorkerParameters(UUID.randomUUID(), Data.EMPTY, emptyList(), WorkerParameters.RuntimeExtras(), 0, 0,
            executor, kotlin.coroutines.EmptyCoroutineContext, WorkManagerTaskExecutor(executor), factory, progress, foreground)
    }
}
