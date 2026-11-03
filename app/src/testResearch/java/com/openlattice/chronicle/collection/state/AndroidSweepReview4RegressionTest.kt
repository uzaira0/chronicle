@file:Suppress("DEPRECATION")

package com.openlattice.chronicle.collection.state

import com.openlattice.chronicle.receivers.lifecycle.SurveyNotificationsReceiver
import com.openlattice.chronicle.audit.auditWorkerParameters
import android.app.Application
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.IntentFilter
import android.content.SharedPreferences
import android.database.sqlite.SQLiteException
import android.graphics.Rect
import android.os.Looper
import android.os.SystemClock
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.work.ListenableWorker
import androidx.work.WorkManager
import com.google.android.gms.common.internal.safeparcel.SafeParcelableSerializer
import com.google.android.gms.location.ActivityTransitionEvent
import com.google.android.gms.location.ActivityTransitionResult
import com.google.android.gms.location.DetectedActivity
import com.openlattice.chronicle.ChronicleApplication
import com.openlattice.chronicle.EnrollmentTestStores
import com.openlattice.chronicle.WorkManagerRule
import com.openlattice.chronicle.WorkSchedulingStatus
import com.openlattice.chronicle.collection.CollectionModuleId
import com.openlattice.chronicle.collection.HealthMetricType
import com.openlattice.chronicle.collection.InteractionPolicy
import com.openlattice.chronicle.collection.activity.SleepActivityReceiver
import com.openlattice.chronicle.collection.core.ModuleResult
import com.openlattice.chronicle.collection.device.HealthMetricCollectionModule
import com.openlattice.chronicle.collection.device.HealthMetricReading
import com.openlattice.chronicle.collection.device.HealthMetricSource
import com.openlattice.chronicle.collection.directboot.clearDirectBootSensorBuffer
import com.openlattice.chronicle.collection.interaction.InteractionCollectionService
import com.openlattice.chronicle.collection.sink.HealthMetricSampleSink
import com.openlattice.chronicle.preferences.InteractionPolicySettings
import com.openlattice.chronicle.preferences.PARTICIPANT_ID
import com.openlattice.chronicle.preferences.PARTICIPATION_STATUS
import com.openlattice.chronicle.preferences.STUDY_ID
import com.openlattice.chronicle.services.sync.CHRONICLE_SYNC_WORK_NAME
import com.openlattice.chronicle.services.sync.scheduleChronicleSyncWork
import com.openlattice.chronicle.services.upload.LOCAL_STORAGE_RESERVE_BYTES
import com.openlattice.chronicle.services.withdrawal.ParticipantWithdrawalWorker
import com.openlattice.chronicle.services.withdrawal.WithdrawalState
import com.openlattice.chronicle.services.withdrawal.WithdrawalStateStore
import com.openlattice.chronicle.storage.BatterySampleEntry
import com.openlattice.chronicle.storage.ChronicleDb
import com.openlattice.chronicle.storage.checkedSQLiteOpenHelperFactory
import com.openlattice.chronicle.storage.CollectionModuleStateEntity
import com.openlattice.chronicle.storage.UploadServerEntity
import com.openlattice.chronicle.storage.activityRecognitionSampleDao
import com.openlattice.chronicle.storage.healthMetricSampleDao
import com.openlattice.chronicle.storage.interactionSampleDao
import java.lang.reflect.Proxy
import java.util.UUID
import java.util.concurrent.*
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [28])
class AndroidSweepReview4RegressionTest {
    @get:Rule val workManagerRule = WorkManagerRule()
    private val context: Context = ApplicationProvider.getApplicationContext()
    private lateinit var db: ChronicleDb
    private lateinit var prefs: SharedPreferences
    private val module = CollectionModuleId.BATTERY_TELEMETRY
    private val mainQueries = AtomicInteger()

    private fun <T> worker(action: () -> T): T {
        val result = CompletableFuture<T>()
        ResearchPersistenceGate.executeAsync {
            try { result.complete(action()) } catch (error: Throwable) { result.completeExceptionally(error) }
        }
        return try { result.get(10, TimeUnit.SECONDS) }
        catch (error: ExecutionException) { throw error.cause ?: error }
    }

    @Before fun setUp() = worker {
        ResearchPersistenceGate.resetAfterLocalStoreRecovery()
        prefs = context.getSharedPreferences("sweep-review4", Context.MODE_PRIVATE)
        prefs.edit().clear().putString(STUDY_ID, "11111111-1111-1111-1111-111111111111")
            .putString(PARTICIPANT_ID, "participant-A").putString(PARTICIPATION_STATUS, "ENROLLED").commit()
        EnrollmentTestStores.prefs = prefs
        context.getSharedPreferences("research_erasure_fence", Context.MODE_PRIVATE).edit().clear().commit()
        db = Room.inMemoryDatabaseBuilder(context, ChronicleDb::class.java)
            .openHelperFactory(checkedSQLiteOpenHelperFactory { sql ->
                if ("upload_servers" in sql && Thread.currentThread() === Looper.getMainLooper().thread) mainQueries.incrementAndGet()
            }).build()
        EnrollmentTestStores.db = db
        db.uploadServerDao().insert(UploadServerEntity(name = "study",
            url = "https://localhost", apiKey = "test-device-key",
            studyId = prefs.getString(STUDY_ID, "")!!, participantId = "participant-A", sourceRandomDeviceId = "device"))
        CollectionModuleId.values().filter { it.active }.forEach {
            db.collectionModuleStateDao().upsertAll(listOf(CollectionModuleStateEntity(it.id, true,
                ParticipantDecision.ACCEPTED.name, 0, false, 1)))
        }
        ResearchPersistenceGate.initialize(context)
        assertTrue(StorageAdmission.allowed(context, LOCAL_STORAGE_RESERVE_BYTES))
        Unit
    }

    @After fun tearDown() = worker {
        ResearchPersistenceGate.resetAfterLocalStoreRecovery()
        InteractionPolicySettings.invalidateMemoryCache()
        EnrollmentTestStores.db = null
        EnrollmentTestStores.prefs = null
        db.close()
    }

    private fun coldSnapshot() = EnrollmentTestStores.leaveEnrollmentUnpublished(context)

    private fun application(base: Context = context): ChronicleApplication = ChronicleApplication().also {
        shadowOf(it).callAttach(base)
    }

    @Test @Config(sdk = [26]) fun minSdkStartupAndDirectBootErasureUseSupportedApis() {
        coldSnapshot()
        application().onCreate()
        assertTrue(worker { clearDirectBootSensorBuffer(context) })
        assertTrue("Oldest supported API must initialize authorization", ResearchPersistenceGate.collectsNow(context, module))
        val receiver = SurveyNotificationsReceiver()
        context.registerReceiver(receiver, IntentFilter("test.minsdk.survey"), Context.RECEIVER_NOT_EXPORTED)
        try {
            context.sendBroadcast(Intent("test.minsdk.survey"))
            shadowOf(Looper.getMainLooper()).idle()
            assertTrue(shadowOf(receiver).wentAsync())
            shadowOf(shadowOf(receiver).originalPendingResult).getFuture().get(5, TimeUnit.SECONDS)
        } finally { context.unregisterReceiver(receiver) }
        assertEquals(0, mainQueries.get())
    }

    @Test fun admissionExceptionsRejectBothEmptyAndNonemptyHealthReadWindows() = worker {
        listOf(false, true).forEach { empty ->
            var pending = false
            var acknowledgments = 0
            var rejections = 0
            val source = object : HealthMetricSource {
                override fun read(): List<HealthMetricReading> {
                    check(!pending) { "previous window still pending" }
                    pending = true
                    return if (empty) emptyList() else listOf(HealthMetricReading(HealthMetricType.STEPS, 42.0,
                        "count", 1000, 2000, "test.health", "record"))
                }
                override fun acknowledgeRead() { pending = false; acknowledgments++ }
                override fun rejectRead() { pending = false; rejections++ }
            }
            val fail = AtomicBoolean(true)
            val guard = CollectionPersistenceGuard { write ->
                if (fail.get()) throw SQLiteException("transient admission read failure")
                write(); true
            }
            val health = HealthMetricCollectionModule(
                HealthMetricSampleSink(db.healthMetricSampleDao(), persistenceGuard = guard), source,
                enrolled = { true }, persistLease = { it() })
            runCatching { health.sample() }
            assertFalse("failed admission must release the source window", pending)
            assertEquals(1, rejections)
            fail.set(false)
            assertEquals(ModuleResult.Ok(if (empty) 0 else 1), health.sample())
            assertEquals(1, acknowledgments)
        }
    }

    @Test fun erasureWaitsForServiceMonitorAndNextEventStartsFresh() {
        val service = Robolectric.buildService(InteractionCollectionService::class.java).create().get()
        val erasing = CountDownLatch(1)
        val executor = Executors.newSingleThreadExecutor()
        try {
            val policy = InteractionPolicySettings(context)
            assertTrue(policy.save(UUID.fromString(prefs.getString(STUDY_ID, "")!!), 1, true, InteractionPolicy.DEFAULT))
            org.robolectric.util.ReflectionHelpers.callInstanceMethod<Unit>(service, "onServiceConnected")
            service.onAccessibilityEvent(click(1))
            waitUntil { worker { db.interactionSampleDao().count() == 1 } }
            val initial = worker { db.interactionSampleDao().getOldest(10).single() }
            val callbackEntered = CountDownLatch(1)
            val captured = synchronized(service) {
                val task = executor.submit { callbackEntered.countDown(); service.onAccessibilityEvent(click(2)) }
                assertTrue(callbackEntered.await(5, TimeUnit.SECONDS))
                assertThrows("the real callback must serialize its rolling-state update", TimeoutException::class.java) {
                    task.get(200, TimeUnit.MILLISECONDS)
                }
                task
            }
            captured.get(5, TimeUnit.SECONDS)
            waitUntil { worker { db.interactionSampleDao().count() == 2 } }
            val beforeErasure = worker { db.interactionSampleDao().getOldest(10).last() }
            assertEquals(initial.episodeId, beforeErasure.episodeId)
            assertEquals(1L, beforeErasure.dwellMillisSincePrev)
            val erased = synchronized(service) {
                val task = executor.submit { erasing.countDown(); service.eraseResearchObservations() }
                assertTrue(erasing.await(5, TimeUnit.SECONDS))
                assertThrows("erasure must serialize with the whole rolling-state update", TimeoutException::class.java) {
                    task.get(200, TimeUnit.MILLISECONDS)
                }
                task
            }
            erased.get(5, TimeUnit.SECONDS)
            service.onAccessibilityEvent(click(3))
            waitUntil { worker { db.interactionSampleDao().count() == 3 } }
            val latest = worker { db.interactionSampleDao().getOldest(10).last() }
            assertNotEquals(beforeErasure.episodeId, latest.episodeId)
            assertNull(latest.dwellMillisSincePrev)
        } finally {
            executor.shutdownNow(); executor.awaitTermination(5, TimeUnit.SECONDS)
            service.onDestroy()
        }
    }

    private fun click(time: Long): AccessibilityEvent {
        val node = AccessibilityNodeInfo.obtain().apply { setBoundsInScreen(Rect(0, 0, 100, 100)) }
        return AccessibilityEvent.obtain(AccessibilityEvent.TYPE_VIEW_CLICKED).apply {
            packageName = "test.app"; className = "android.widget.Button"; eventTime = time
            shadowOf(this).setSourceNode(node)
        }
    }

    @Test fun coldReceiverRetainsDeliveryUntilInitializationThenChecksRegistration() {
        val module = CollectionModuleId.ACTIVITY_RECOGNITION
        val stamp = ResearchPersistenceGate.observationScope(context, module)!!.key
        coldSnapshot()
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        ResearchPersistenceGate.executeAsync { entered.countDown(); check(release.await(5, TimeUnit.SECONDS)) }
        assertTrue(entered.await(5, TimeUnit.SECONDS))
        val receiver = SleepActivityReceiver()
        val action = "test.cold.activity"
        context.registerReceiver(receiver, IntentFilter(action), Context.RECEIVER_NOT_EXPORTED)
        fun deliver(scope: String) {
            val intent = Intent(action).putExtra("registration_module", module.id).putExtra("registration_scope", scope)
            SafeParcelableSerializer.serializeToIntentExtra(ActivityTransitionResult(listOf(
                ActivityTransitionEvent(DetectedActivity.WALKING, 0, SystemClock.elapsedRealtimeNanos()))), intent,
                "com.google.android.location.internal.EXTRA_ACTIVITY_TRANSITION_RESULT")
            context.sendBroadcast(intent)
            shadowOf(Looper.getMainLooper()).idle()
        }
        try {
            deliver(stamp)
            assertTrue("cold delivery must be kept with goAsync", shadowOf(receiver).wentAsync())
            assertEquals(0, mainQueries.get())
            release.countDown()
            waitUntil { worker { db.activityRecognitionSampleDao().count() == 1 } }
            deliver("retired-registration")
            val pending = shadowOf(receiver).originalPendingResult
            if (pending != null) shadowOf(pending).getFuture().get(5, TimeUnit.SECONDS)
            assertEquals(1, worker { db.activityRecognitionSampleDao().count() })
        } finally { release.countDown(); context.unregisterReceiver(receiver); worker { } }
    }

    @Test fun schedulingResumesAfterTransientInitializationFailure() {
        application().workManagerConfiguration.initializationExceptionHandler!!.accept(SQLiteException("temporary WorkManager failure"))
        // Initialization recovery must not depend on a new Activity scheduling work.
        waitUntil {
            WorkManager.getInstance(context).getWorkInfosForUniqueWork(CHRONICLE_SYNC_WORK_NAME).get(5, TimeUnit.SECONDS).isNotEmpty()
        }
        waitUntil { !WorkSchedulingStatus.unavailable }
        WorkSchedulingStatus.initializationFailed(SQLiteException("another temporary failure"))
        worker { scheduleChronicleSyncWork(context) }
        val infos = WorkManager.getInstance(context).getWorkInfosForUniqueWork(CHRONICLE_SYNC_WORK_NAME).get(5, TimeUnit.SECONDS)
        assertTrue("recovered storage must accept periodic sync again", infos.isNotEmpty())
        waitUntil { !WorkSchedulingStatus.unavailable }
    }

    private fun waitUntil(predicate: () -> Boolean) {
        val until = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
        while (!predicate() && System.nanoTime() < until) Thread.sleep(10)
        assertTrue(predicate())
    }

    @Test fun acknowledgedWithdrawalRetriesFenceFailureAndFinishesAfterRecovery() = worker {
        val state = WithdrawalStateStore(context)
        state.beginWithdrawal()
        state.acknowledgeServer(db.uploadServerDao().getConfiguredServer()!!.id)
        db.batterySampleDao().insertAll(listOf(BatterySampleEntry("retained", "2026-09-30T00:00:00Z", "UTC",
            50, "DISCHARGING", "NONE", 200, 4000, "GOOD")))
        val raw = context.getSharedPreferences("research_erasure_fence", Context.MODE_PRIVATE)
        val fail = AtomicBoolean(true)
        val failing = Proxy.newProxyInstance(SharedPreferences::class.java.classLoader, arrayOf(SharedPreferences::class.java)) { _, method, args ->
            if (method.name == "edit") {
                val editor = raw.edit()
                Proxy.newProxyInstance(SharedPreferences.Editor::class.java.classLoader,
                    arrayOf(SharedPreferences.Editor::class.java)) { proxy, edit, values ->
                    if (edit.name == "commit" && fail.get()) false else {
                        val result = edit.invoke(editor, *(values ?: emptyArray()))
                        if (result is SharedPreferences.Editor) proxy else result
                    }
                }
            } else method.invoke(raw, *(args ?: emptyArray()))
        } as SharedPreferences
        val broken = object : ContextWrapper(context) {
            override fun getApplicationContext(): Context = this
            override fun getSharedPreferences(name: String, mode: Int): SharedPreferences =
                if (name == "research_erasure_fence") failing else super.getSharedPreferences(name, mode)
        }
        val withdrawal = ParticipantWithdrawalWorker(broken, auditWorkerParameters())
        assertEquals(ListenableWorker.Result.retry(), withdrawal.doWork())
        assertEquals(WithdrawalState.PENDING, state.state())
        assertEquals("the queue is cleared even while the fence write fails", 0, db.batterySampleDao().count())
        fail.set(false)
        assertEquals(ListenableWorker.Result.success(), withdrawal.doWork())
        assertEquals(WithdrawalState.COMPLETE, state.state())
    }

}
