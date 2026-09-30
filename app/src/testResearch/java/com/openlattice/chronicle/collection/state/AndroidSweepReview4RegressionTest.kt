@file:Suppress("DEPRECATION")

package com.openlattice.chronicle.collection.state

import android.app.Application
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.IntentFilter
import android.content.SharedPreferences
import android.content.pm.ApplicationInfo
import android.database.sqlite.SQLiteException
import android.graphics.Rect
import android.os.Looper
import android.os.SystemClock
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.work.*
import androidx.work.impl.utils.taskexecutor.WorkManagerTaskExecutor
import com.google.android.gms.common.internal.safeparcel.SafeParcelableSerializer
import com.google.android.gms.location.ActivityTransitionEvent
import com.google.android.gms.location.ActivityTransitionResult
import com.google.android.gms.location.DetectedActivity
import com.openlattice.chronicle.ChronicleApplication
import com.openlattice.chronicle.WorkSchedulingStatus
import com.openlattice.chronicle.collection.CollectionModuleId
import com.openlattice.chronicle.collection.HealthMetricType
import com.openlattice.chronicle.collection.InteractionPolicy
import com.openlattice.chronicle.collection.activity.SleepActivityReceiver
import com.openlattice.chronicle.collection.core.ModuleResult
import com.openlattice.chronicle.collection.core.NoOpCollectionLog
import com.openlattice.chronicle.collection.device.HealthMetricCollectionModule
import com.openlattice.chronicle.collection.device.HealthMetricReading
import com.openlattice.chronicle.collection.device.HealthMetricSource
import com.openlattice.chronicle.collection.directboot.DirectBootStorageAdmission
import com.openlattice.chronicle.collection.interaction.InteractionCollectionService
import com.openlattice.chronicle.collection.sink.HealthMetricSampleSink
import com.openlattice.chronicle.crypto.EncryptedEnvelope
import com.openlattice.chronicle.crypto.EncryptedPayloadType
import com.openlattice.chronicle.preferences.*
import com.openlattice.chronicle.services.crypto.*
import com.openlattice.chronicle.services.sync.*
import com.openlattice.chronicle.services.upload.LOCAL_STORAGE_RESERVE_BYTES
import com.openlattice.chronicle.services.withdrawal.*
import com.openlattice.chronicle.storage.*
import java.io.File
import java.io.IOException
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Proxy
import java.util.UUID
import java.util.concurrent.*
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [28])
class AndroidSweepReview4RegressionTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private lateinit var db: ChronicleDb
    private lateinit var prefs: SharedPreferences
    private lateinit var previousEnvelopes: SealedEnvelopeStore
    private val fence get() = ResearchErasureFence(context)
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
        previousEnvelopes = PayloadSealer.sealedEnvelopeStore
        prefs = context.getSharedPreferences("sweep-review4", Context.MODE_PRIVATE)
        prefs.edit().clear().putString(STUDY_ID, "11111111-1111-1111-1111-111111111111")
            .putString(PARTICIPANT_ID, "participant-A").putString(PARTICIPATION_STATUS, "ENROLLED").commit()
        EncryptedPrefsHelper::class.java.getDeclaredField("instance").apply { isAccessible = true }.set(EncryptedPrefsHelper, prefs)
        context.getSharedPreferences("research_erasure_fence", Context.MODE_PRIVATE).edit().clear().commit()
        db = Room.inMemoryDatabaseBuilder(context, ChronicleDb::class.java).build()
        ChronicleDb::class.java.getDeclaredField("INSTANCE").apply { isAccessible = true }.set(null, db)
        db.uploadServerDao().insert(UploadServerEntity(name = "study", url = "https://localhost",
            studyId = prefs.getString(STUDY_ID, "")!!, participantId = "participant-A", sourceDeviceId = "device"))
        CollectionModuleId.values().filter { it.active }.forEach {
            db.collectionModuleStateDao().upsertAll(listOf(CollectionModuleStateEntity(it.id, true,
                ParticipantDecision.ACCEPTED.name, 0, false, 1, null, null)))
        }
        val dao = db.uploadServerDao()
        val proxy = Proxy.newProxyInstance(UploadServerDao::class.java.classLoader, arrayOf(UploadServerDao::class.java)) { _, method, args ->
            if (Thread.currentThread() === Looper.getMainLooper().thread) mainQueries.incrementAndGet()
            try { method.invoke(dao, *(args ?: emptyArray())) }
            catch (error: InvocationTargetException) { throw error.targetException }
        }
        db.javaClass.getDeclaredField("_uploadServerDao").apply { isAccessible = true }.set(db, lazyOf(proxy))
        if (!WorkManager.isInitialized()) WorkManager.initialize(context, Configuration.Builder().setExecutor(Executor { }).build())
        ResearchPersistenceGate.initialize(context)
        StorageAdmission.allowed(context, LOCAL_STORAGE_RESERVE_BYTES)
        StorageAdmission::class.java.getDeclaredField("lastAllowed").apply { isAccessible = true }.setBoolean(StorageAdmission, true)
        StorageAdmission::class.java.getDeclaredField("lastCheckedAt").apply { isAccessible = true }
            .setLong(StorageAdmission, SystemClock.elapsedRealtime())
        Unit
    }

    @After fun tearDown() = worker {
        ResearchPersistenceGate.resetAfterLocalStoreRecovery()
        PayloadSealer.sealedEnvelopeStore = previousEnvelopes
        WorkSchedulingStatus::class.java.getDeclaredField("unavailable").apply { isAccessible = true }.setBoolean(WorkSchedulingStatus, false)
        InteractionPolicySettings.invalidateMemoryCache()
        ChronicleDb::class.java.getDeclaredField("INSTANCE").apply { isAccessible = true }.set(null, null)
        EncryptedPrefsHelper::class.java.getDeclaredField("instance").apply { isAccessible = true }.set(EncryptedPrefsHelper, null)
        db.close()
    }

    private fun coldSnapshot() {
        val field = ResearchPersistenceGate::class.java.getDeclaredField("authorization").apply { isAccessible = true }
        field.set(ResearchPersistenceGate, field.type.getDeclaredConstructor().apply { isAccessible = true }.newInstance())
    }

    private fun application(base: Context = context): ChronicleApplication = ChronicleApplication().also {
        Application::class.java.getDeclaredMethod("attach", Context::class.java).apply { isAccessible = true }.invoke(it, base)
    }

    @Test @Config(sdk = [23]) fun row1_android6StartupAndDirectBootErasureUseSupportedApis() {
        coldSnapshot()
        application().onCreate()
        worker { DirectBootStorageAdmission.clear(context) }
        assertTrue("Android 6 must initialize authorization", ResearchPersistenceGate.collectsNow(context, module))
        val receiver = com.openlattice.chronicle.receivers.lifecycle.SurveyNotificationsReceiver()
        context.registerReceiver(receiver, IntentFilter("test.api23.survey"))
        try {
            context.sendBroadcast(Intent("test.api23.survey"))
            shadowOf(Looper.getMainLooper()).idle()
            assertTrue(shadowOf(receiver).wentAsync())
            shadowOf(shadowOf(receiver).originalPendingResult).getFuture().get(5, TimeUnit.SECONDS)
        } finally { context.unregisterReceiver(receiver) }
        assertEquals(0, mainQueries.get())
    }

    @Test fun row2_admissionExceptionsRejectBothEmptyAndNonemptyHealthReadWindows() = worker {
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
                enrolled = { true }, log = NoOpCollectionLog)
            runCatching { health.sample() }
            assertFalse("failed admission must release the source window", pending)
            assertEquals(1, rejections)
            fail.set(false)
            assertEquals(ModuleResult.Ok(if (empty) 0 else 1), health.sample())
            assertEquals(1, acknowledgments)
        }
    }

    @Test fun row3_erasureWaitsForRollingInteractionStateAndNextEventStartsFresh() {
        val service = Robolectric.buildService(InteractionCollectionService::class.java).create().get()
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val erasing = CountDownLatch(1)
        val executor = Executors.newFixedThreadPool(2)
        try {
            val policy = InteractionPolicySettings(context)
            assertTrue(policy.save(UUID.fromString(prefs.getString(STUDY_ID, "")!!), 1, true, InteractionPolicy.DEFAULT))
            service.javaClass.getDeclaredField("policySettings").apply { isAccessible = true }.set(service, policy)
            service.javaClass.getDeclaredField("rollingOrigin").apply { isAccessible = true }.set(service,
                object : CollectionPersistenceGuard {
                    override fun persist(persist: () -> Unit): Boolean = false
                    override fun isCurrent(): Boolean { entered.countDown(); check(release.await(5, TimeUnit.SECONDS)); return true }
                })
            service.javaClass.getDeclaredField("currentEpisodeId").apply { isAccessible = true }.set(service, "old-episode")
            service.javaClass.getDeclaredField("lastEventUptimeMillis").apply { isAccessible = true }.set(service, 1L)
            val event = executor.submit { service.onAccessibilityEvent(click(2)) }
            assertTrue(entered.await(5, TimeUnit.SECONDS))
            val erased = executor.submit { erasing.countDown(); service.eraseResearchObservations() }
            assertTrue(erasing.await(5, TimeUnit.SECONDS))
            assertThrows("erasure must serialize with the whole rolling-state update", TimeoutException::class.java) {
                erased.get(200, TimeUnit.MILLISECONDS)
            }
            release.countDown()
            event.get(5, TimeUnit.SECONDS)
            erased.get(5, TimeUnit.SECONDS)
            service.onAccessibilityEvent(click(3))
            drainInteraction(service)
            val latest = worker { db.interactionSampleDao().getOldest(10).last() }
            assertNotEquals("old-episode", latest.episodeId)
            assertNull(latest.dwellMillisSincePrev)
        } finally {
            release.countDown(); executor.shutdownNow(); executor.awaitTermination(5, TimeUnit.SECONDS)
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

    private fun drainInteraction(service: InteractionCollectionService) {
        val tasks = service.javaClass.getDeclaredField("writeExecutor").apply { isAccessible = true }.get(service)
        val executor = tasks.javaClass.getDeclaredField("executor").apply { isAccessible = true }.get(tasks) as ExecutorService
        executor.submit {}.get(5, TimeUnit.SECONDS)
    }

    @Test fun row4_coldReceiverRetainsDeliveryUntilInitializationThenChecksRegistration() {
        val module = CollectionModuleId.ACTIVITY_RECOGNITION
        val stamp = ResearchPersistenceGate.observationScope(context, module)!!.first
        coldSnapshot()
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        ResearchPersistenceGate.executeAsync { entered.countDown(); check(release.await(5, TimeUnit.SECONDS)) }
        assertTrue(entered.await(5, TimeUnit.SECONDS))
        val receiver = SleepActivityReceiver()
        val action = "test.cold.activity"
        context.registerReceiver(receiver, IntentFilter(action))
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

    @Test fun row5_schedulingResumesAfterTransientInitializationFailure() {
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

    private fun envelope(store: SealedEnvelopeStore) {
        store.save("study|participant|BATTERY", SealedEnvelopeEntry("batch", EncryptedEnvelope(keyId = "key",
            payloadType = EncryptedPayloadType.BATTERY, encryptedKey = "wrapped", iv = "nonce", ciphertext = "sealed", sampleCount = 1)))
    }

    @Test fun row6_startupInstallsDiskEnvelopesBeforeAnyAuthorizationReplay() {
        val disk = FileSealedEnvelopeStore(File(context.applicationInfo.dataDir, "no_backup/sealed-envelopes"))
        envelope(disk)
        PayloadSealer.sealedEnvelopeStore = InMemorySealedEnvelopeStore()
        worker { ResearchPersistenceGate.stop { fence.erase(setOf(module)) } }
        val pausedMain = object : ContextWrapper(context) {
            override fun getApplicationContext(): Context = this
            // Drain work queued before the application constructs its disk store.
            override fun getApplicationInfo(): ApplicationInfo { worker { }; return context.applicationInfo }
        }
        application(pausedMain).onCreate()
        worker { }
        assertFalse(worker { fence.hasPendingErasures() })
        assertNull("startup replay must erase the persisted ciphertext", disk.load("study|participant|BATTERY"))
    }

    @Test fun row7_verifiedRecoveryRetainsDiscardUntilAuxiliaryErasureSucceeds() = worker {
        val disk = FileSealedEnvelopeStore(File(context.noBackupFilesDir, "review4-recovery-sealed"))
        envelope(disk)
        val fail = AtomicBoolean(true)
        PayloadSealer.sealedEnvelopeStore = object : SealedEnvelopeStore by disk {
            override fun erase(types: Set<EncryptedPayloadType>) {
                if (fail.get()) throw IOException("transient sealed-envelope erasure failure")
                disk.erase(types)
            }
        }
        ResearchPersistenceGate.stop { fence.erase(setOf(module)) }
        val owner = db.uploadServerDao().getConfiguredServer()!!
        val coordinator = CollectionLoopCoordinator(context)
        assertThrows(IOException::class.java) { coordinator.reconcileVerifiedRecoveryErasures(owner.studyId to owner.participantId) }
        assertTrue("failed auxiliary erasure must remain a durable DISCARD", module in fence.pending())
        assertNotNull(disk.load("study|participant|BATTERY"))
        fail.set(false)
        coordinator.reconcileVerifiedRecoveryErasures(owner.studyId to owner.participantId)
        assertFalse(fence.hasPendingErasures())
        assertNull(disk.load("study|participant|BATTERY"))
    }

    @Test fun row8_acknowledgedWithdrawalRetriesFenceFailureAndFinishesAfterRecovery() = worker {
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
                Proxy.newProxyInstance(SharedPreferences.Editor::class.java.classLoader, arrayOf(SharedPreferences.Editor::class.java)) { proxy, edit, values ->
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
        val withdrawal = ParticipantWithdrawalWorker(broken, workerParameters())
        assertEquals(ListenableWorker.Result.retry(), withdrawal.doWork())
        assertEquals(WithdrawalState.PENDING, state.state())
        assertEquals(1, db.batterySampleDao().count())
        fail.set(false)
        assertEquals(ListenableWorker.Result.success(), withdrawal.doWork())
        assertEquals(WithdrawalState.COMPLETE, state.state())
        assertEquals(0, db.batterySampleDao().count())
    }

    private fun workerParameters(): WorkerParameters {
        val executor = Executor { }
        val progress = Proxy.newProxyInstance(ProgressUpdater::class.java.classLoader,
            arrayOf(ProgressUpdater::class.java)) { _, _, _ -> error("no progress expected") } as ProgressUpdater
        val foreground = Proxy.newProxyInstance(ForegroundUpdater::class.java.classLoader,
            arrayOf(ForegroundUpdater::class.java)) { _, _, _ -> error("no foreground expected") } as ForegroundUpdater
        val factory = object : WorkerFactory() {
            override fun createWorker(appContext: Context, workerClassName: String, workerParameters: WorkerParameters): ListenableWorker? = null
        }
        return WorkerParameters(UUID.randomUUID(), Data.EMPTY, emptyList(), WorkerParameters.RuntimeExtras(),
            0, 0, executor, kotlin.coroutines.EmptyCoroutineContext, WorkManagerTaskExecutor(executor), factory, progress, foreground)
    }
}
