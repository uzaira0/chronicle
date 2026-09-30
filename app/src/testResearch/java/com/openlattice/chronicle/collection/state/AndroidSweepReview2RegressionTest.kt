package com.openlattice.chronicle.collection.state

import android.app.Application
import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import android.os.Looper
import android.os.SystemClock
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.work.Configuration
import androidx.work.WorkManager
import com.openlattice.chronicle.android.AndroidSensorType
import com.openlattice.chronicle.collection.AudioEventType
import com.openlattice.chronicle.collection.CollectionModuleId
import com.openlattice.chronicle.collection.SensorCollectionModules
import com.openlattice.chronicle.collection.audio.AudioCaptureController
import com.openlattice.chronicle.collection.core.ModuleResult
import com.openlattice.chronicle.collection.core.NoOpCollectionLog
import com.openlattice.chronicle.collection.directboot.DirectBootSampleBuffer
import com.openlattice.chronicle.collection.directboot.DirectBootStorageAdmission
import com.openlattice.chronicle.collection.sensors.FakeSensorGateway
import com.openlattice.chronicle.collection.sensors.FakeSensorRuntimeSettings
import com.openlattice.chronicle.collection.sensors.ManualSensorRuntimeScheduler
import com.openlattice.chronicle.collection.sensors.SensorRuntimeController
import com.openlattice.chronicle.collection.sink.SensorSampleSink
import com.openlattice.chronicle.collection.sink.SensorSampleWriter
import com.openlattice.chronicle.collection.usage.DaoUsagePollCheckpointStore
import com.openlattice.chronicle.preferences.*
import com.openlattice.chronicle.services.upload.LOCAL_STORAGE_RESERVE_BYTES
import com.openlattice.chronicle.services.withdrawal.ParticipantWithdrawalManager
import com.openlattice.chronicle.services.withdrawal.WithdrawalState
import com.openlattice.chronicle.services.withdrawal.WithdrawalStateStore
import com.openlattice.chronicle.sensors.USAGE_EVENTS_SENSOR_CHECKPOINT
import com.openlattice.chronicle.storage.*
import java.io.File
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Proxy
import java.time.OffsetDateTime
import java.util.concurrent.*
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [28])
class AndroidSweepReview2RegressionTest {
    @get:org.junit.Rule val backgroundPersistence = com.openlattice.chronicle.collection.state.BackgroundPersistenceRule()
    private val context: Context = ApplicationProvider.getApplicationContext()
    private lateinit var db: ChronicleDb
    private lateinit var prefs: SharedPreferences
    private val fence get() = ResearchErasureFence(context)
    private val module = SensorCollectionModules.moduleFor(AndroidSensorType.accelerometer)
    private val owner get() = db.uploadServerDao().getConfiguredServer()!!
    private val mainQueries = AtomicInteger()
    private val failQueries = AtomicBoolean()
    private val queryFailed = CountDownLatch(1)

    @Before fun setUp() {
        ResearchPersistenceGate.resetAfterLocalStoreRecovery()
        prefs = context.getSharedPreferences("sweep-review2", Context.MODE_PRIVATE)
        prefs.edit().clear().putString(STUDY_ID, "11111111-1111-1111-1111-111111111111")
            .putString(PARTICIPANT_ID, "participant-A").putString(PARTICIPATION_STATUS, "ENROLLED").commit()
        EncryptedPrefsHelper::class.java.getDeclaredField("instance").apply { isAccessible = true }.set(EncryptedPrefsHelper, prefs)
        context.getSharedPreferences("research_erasure_fence", Context.MODE_PRIVATE).edit().clear().commit()
        db = Room.inMemoryDatabaseBuilder(context, ChronicleDb::class.java).allowMainThreadQueries().build()
        ChronicleDb::class.java.getDeclaredField("INSTANCE").apply { isAccessible = true }.set(null, db)
        db.uploadServerDao().insert(UploadServerEntity(name = "study", url = "https://localhost",
            studyId = prefs.getString(STUDY_ID, "")!!, participantId = "participant-A", sourceDeviceId = "device"))
        CollectionModuleId.values().filter { it.active }.forEach { consent(it) }
        val dao = db.uploadServerDao()
        val proxy = Proxy.newProxyInstance(UploadServerDao::class.java.classLoader, arrayOf(UploadServerDao::class.java)) { _, method, args ->
            if (Thread.currentThread() === Looper.getMainLooper().thread) mainQueries.incrementAndGet()
            if (failQueries.get()) { queryFailed.countDown(); throw android.database.sqlite.SQLiteException("transient Room failure") }
            try { method.invoke(dao, *(args ?: emptyArray())) }
            catch (error: InvocationTargetException) { throw error.targetException }
        }
        db.javaClass.getDeclaredField("_uploadServerDao").apply { isAccessible = true }.set(db, lazyOf(proxy))
        ResearchPersistenceGate.initialize(context)
        if (!WorkManager.isInitialized()) WorkManager.initialize(context, Configuration.Builder().setExecutor(Executor { }).build())
        StorageAdmission.allowed(context, LOCAL_STORAGE_RESERVE_BYTES)
        mainQueries.set(0)
    }

    private fun consent(id: CollectionModuleId, enabled: Boolean = true) {
        db.collectionModuleStateDao().upsertAll(listOf(CollectionModuleStateEntity(id.id, enabled,
            ParticipantDecision.ACCEPTED.name, 0, false, 1, null, null)))
    }
    private fun awaitWorker() {
        val done = CountDownLatch(1)
        ResearchPersistenceGate.executeAsync { done.countDown() }
        assertTrue(done.await(5, TimeUnit.SECONDS))
    }
    private fun waitUntil(predicate: () -> Boolean) {
        val until = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
        while (!predicate() && System.nanoTime() < until) Thread.sleep(10)
        assertTrue(predicate())
    }
    @After fun tearDown() {
        failQueries.set(false)
        awaitWorker()
        AndroidSensorType.values().forEach { SensorSampleWriter.discardSensorSamples(it.name) }
        ResearchPersistenceGate.resetAfterLocalStoreRecovery()
        ChronicleDb::class.java.getDeclaredField("INSTANCE").apply { isAccessible = true }.set(null, null)
        EncryptedPrefsHelper::class.java.getDeclaredField("instance").apply { isAccessible = true }.set(EncryptedPrefsHelper, null)
        db.close()
    }

    @Test fun row1_lifecycleWithdrawalReconcilesAndPublishesOffMain() {
        WithdrawalStateStore(context).beginWithdrawal()
        ParticipantWithdrawalManager.resumePending(context)
        awaitWorker()
        assertEquals("lifecycle reconciliation must issue no MAIN DAO calls", 0, mainQueries.get())
        assertEquals("NOT_ENROLLED", prefs.getString(PARTICIPATION_STATUS, ""))
        waitUntil { WorkManager.getInstance(context).getWorkInfosForUniqueWork(ParticipantWithdrawalManager.WORK_NAME).get().isNotEmpty() }
    }

    @Test fun row2_failedWithdrawalAndEachPrivacyOperationRequireTheirOwnRetry() {
        val rawFence = context.getSharedPreferences("research_erasure_fence", Context.MODE_PRIVATE)
        val failingFence = Proxy.newProxyInstance(SharedPreferences::class.java.classLoader, arrayOf(SharedPreferences::class.java)) { _, method, args ->
            if (method.name == "edit") {
                val editor = rawFence.edit()
                Proxy.newProxyInstance(SharedPreferences.Editor::class.java.classLoader, arrayOf(SharedPreferences.Editor::class.java)) { proxy, edit, values ->
                    if (edit.name == "commit") false else {
                        val result = edit.invoke(editor, *(values ?: emptyArray()))
                        if (result is SharedPreferences.Editor) proxy else result
                    }
                }
            } else method.invoke(rawFence, *(args ?: emptyArray()))
        } as SharedPreferences
        val brokenContext = object : ContextWrapper(context) {
            override fun getApplicationContext(): Context = this
            override fun getSharedPreferences(name: String, mode: Int): SharedPreferences =
                if (name == "research_erasure_fence") failingFence else super.getSharedPreferences(name, mode)
        }
        assertFalse(ParticipantWithdrawalManager.begin(brokenContext))
        assertEquals(WithdrawalState.NONE, WithdrawalStateStore(context).state())
        ResearchPersistenceGate.initialize(context)
        ResearchPersistenceGate.stop { }
        assertFalse("an unrelated successful replay cannot reopen failed withdrawal", ResearchPersistenceGate.collectsNow(context, module))
        assertTrue(ParticipantWithdrawalManager.begin(context))
        WithdrawalStateStore(context).setState(WithdrawalState.NONE)
        prefs.edit().putString(PARTICIPATION_STATUS, "ENROLLED").commit()
        ResearchPersistenceGate.initialize(context)
        assertTrue(ResearchPersistenceGate.collectsNow(context, module))
        listOf("participant-decision", "erasure-replay", "settings-apply").forEach { kind ->
            val operation = ResearchPersistenceGate.PrivacyOperation(kind, "original")
            assertThrows(java.io.IOException::class.java) { ResearchPersistenceGate.stop(operation) { throw java.io.IOException("failed privacy write") } }
            ResearchPersistenceGate.stop { }
            ResearchPersistenceGate.stop(ResearchPersistenceGate.PrivacyOperation(kind, "unrelated")) { }
            assertFalse("$kind must retain its own failure", ResearchPersistenceGate.collectsNow(context, module))
            ResearchPersistenceGate.stop(operation) { }
            assertTrue(ResearchPersistenceGate.collectsNow(context, module))
        }
    }

    @Test fun row3_coldInitializationRetriesLocallyWithoutCallbacksOrNetwork() {
        failQueries.set(true)
        ResearchPersistenceGate.initializeAsync(context)
        assertTrue(queryFailed.await(5, TimeUnit.SECONDS))
        awaitWorker()
        assertFalse(ResearchPersistenceGate.collectsNow(context, module))
        failQueries.set(false)
        waitUntil { ResearchPersistenceGate.collectsNow(context, module) }
        awaitWorker()
        // A verified reset supersedes even a retry already backed off to thirty seconds.
        ResearchPersistenceGate::class.java.getDeclaredField("initializationAttempt").apply { isAccessible = true }
            .setInt(ResearchPersistenceGate, 7)
        failQueries.set(true)
        ResearchPersistenceGate.initializeAsync(context)
        waitUntil { !ResearchPersistenceGate.collectsNow(context, module) }
        awaitWorker()
        ResearchPersistenceGate.resetAfterLocalStoreRecovery()
        ResearchPersistenceGate.initializeAsync(context)
        awaitWorker()
        failQueries.set(false)
        waitUntil { ResearchPersistenceGate.collectsNow(context, module) }
    }

    @Test fun row4_serverEnablementPublishesOfflineAdmission() {
        val id = owner.id
        ResearchPersistenceGate.stop { db.uploadServerDao().setEnabled(id, false) }
        assertFalse(ResearchPersistenceGate.collectsNow(context, CollectionModuleId.USAGE_EVENTS))
        assertEquals(1, ResearchPersistenceGate.setServerEnabled(context, id, true))
        assertTrue(ResearchPersistenceGate.collectsNow(context, CollectionModuleId.USAGE_EVENTS))
        assertEquals(1, ResearchPersistenceGate.setServerEnabled(context, id, false))
        assertFalse(ResearchPersistenceGate.captureObservation(context, CollectionModuleId.USAGE_EVENTS).isCurrent())
    }

    @Test fun row6_expiredDirectBootAdmissionRefreshesForArmedOnChangeSensor() {
        val buffer = DirectBootSampleBuffer(context)
        assertTrue(DirectBootStorageAdmission.allowed(context, buffer))
        DirectBootStorageAdmission::class.java.getDeclaredField("lastCheckedAt").apply { isAccessible = true }
            .setLong(DirectBootStorageAdmission, Long.MIN_VALUE)
        assertFalse(DirectBootStorageAdmission.cachedAllowed())
        waitUntil { DirectBootStorageAdmission.cachedAllowed() }
        val runtime = SensorRuntimeController(FakeSensorGateway(), FakeSensorRuntimeSettings(), SensorSampleWriter { ModuleResult.Ok(it.size) },
            ManualSensorRuntimeScheduler(false), collectionAdmission = { DirectBootStorageAdmission.cachedAllowed() }, log = NoOpCollectionLog)
        runtime.recordSample(AndroidSensorType.accelerometer, floatArrayOf(1f), 1, OffsetDateTime.now())
        assertEquals(1, runtime.bufferedCount)
        runtime.stop()
    }

    @Test fun row7_recoveryReplayPreservesSplitDiagnosticsAndErasesUnsplitDatabase() {
        val root = File(context.noBackupFilesDir, "chronicle-recovery")
        val bundle = File(root, "split").apply { mkdirs() }
        File(bundle, "manifest.txt").writeText("owner_scope_sha256=unknown\ndiagnostics_separated=true\ndiagnostic_artifact=diagnostic-snapshot.enc\n")
        File(bundle, "artifact-chronicle.db.enc").writeText("encrypted research and diagnostics")
        val diagnostic = "encrypted immutable diagnostic journal".toByteArray()
        File(bundle, "diagnostic-snapshot.enc").writeBytes(diagnostic)
        LocalStoreRecoveryManager.erasePendingRecoveryArtifacts(context, null)
        assertFalse(bundle.exists())
        val preserved = File(context.noBackupFilesDir, "chronicle-diagnostics-recovery/split/diagnostic-snapshot.enc")
        assertArrayEquals(diagnostic, preserved.readBytes())
        val legacy = File(root, "legacy").apply { mkdirs() }
        File(legacy, "artifact-chronicle.db.enc").writeText("only remaining diagnostic journal")
        LocalStoreRecoveryManager.erasePendingRecoveryArtifacts(context, null)
        assertFalse(legacy.exists())
    }

    @Test fun row8_newEnrollmentCannotAdoptAnotherOwnersLegacyUsageCheckpoint() {
        db.usagePollCheckpointDao().upsert(UsagePollCheckpointEntity(USAGE_EVENTS_SENSOR_CHECKPOINT, 9000))
        val store = DaoUsagePollCheckpointStore(db.usagePollCheckpointDao(), "replacement-owner:1", 0)
        assertNull(store.readPreviousPollTimestamp())
        val verified = DaoUsagePollCheckpointStore(db.usagePollCheckpointDao(), "proven-upgrade-owner:1", 0, "proven-upgrade-owner")
        assertEquals(9000L, verified.readPreviousPollTimestamp())
    }

    @Test fun row9_legacyUnlockTransferRetainsAcceptedAndUncertainRamThroughHold() {
        val original = owner
        val identity = "${original.id}:${original.createdAt}:${original.studyId}:${original.participantId}:${original.sourceDeviceId}"
        val old = SensorRuntimeController(FakeSensorGateway(), FakeSensorRuntimeSettings(), SensorSampleWriter { ModuleResult.Retry("DE append unavailable") },
            ManualSensorRuntimeScheduler(false), log = NoOpCollectionLog)
        old.recordSample(AndroidSensorType.accelerometer, floatArrayOf(1f), 1, OffsetDateTime.now())
        ResearchPersistenceGate.stop { consent(module, false) }
        old.stop(true) { ResearchPersistenceGate.guardForLegacyRetainedRegistration(context, module, identity, System.currentTimeMillis()) }
        val replacement = SensorRuntimeController(FakeSensorGateway(), FakeSensorRuntimeSettings(),
            SensorSampleSink(db.sensorSampleDao(), NoOpCollectionLog, ResearchPersistenceGate.guard(context, module)),
            ManualSensorRuntimeScheduler(false), collectionGate = { ResearchPersistenceGate.collectsNow(context, module) }, retainOnStop = true, log = NoOpCollectionLog)
        assertEquals(1, replacement.bufferedCount)
        assertTrue(replacement.flushBuffer() is ModuleResult.Retry)
        ResearchPersistenceGate.stop { consent(module) }
        assertEquals(ModuleResult.Ok(1), replacement.flushBuffer())
        val uncertain = ResearchPersistenceGate.guardForLegacyRetainedRegistration(context, module, null, null)
        replacement.recordSample(AndroidSensorType.accelerometer, floatArrayOf(2f), 1, OffsetDateTime.now(), uncertain)
        assertTrue(replacement.flushBuffer() is ModuleResult.Failed)
        assertEquals("uncertain ownership must remain retryable", 1, replacement.bufferedCount)
        replacement.stop()
    }

    private class ScanCountingQueue : ArrayBlockingQueue<SensorSampleEntry>(5_000) {
        val scans = AtomicInteger()
        override fun contains(element: SensorSampleEntry?): Boolean { scans.incrementAndGet(); return super.contains(element) }
    }
    @Test fun row10_largeFailedFlushRequeuesWithoutQueueScansOrDuplicates() {
        val runtime = SensorRuntimeController(FakeSensorGateway(), FakeSensorRuntimeSettings(), SensorSampleWriter { ModuleResult.Retry("HOLD") },
            ManualSensorRuntimeScheduler(false), log = NoOpCollectionLog)
        val queue = ScanCountingQueue()
        runtime.javaClass.getDeclaredField("buffer").apply { isAccessible = true }.set(runtime, queue)
        repeat(5_000) { runtime.recordSample(AndroidSensorType.accelerometer, floatArrayOf(it.toFloat()), 1, OffsetDateTime.now()) }
        assertTrue(runtime.flushBuffer() is ModuleResult.Retry)
        assertEquals(5_000, runtime.bufferedCount)
        assertEquals("requeue must use ID membership, without scanning queue elements", 0, queue.scans.get())
        assertTrue(runtime.flushBuffer() is ModuleResult.Retry)
        assertEquals(5_000, runtime.bufferedCount)
        runtime.stop()
    }

    @Test fun row11_queuedAudioSurvivesUnrelatedModuleErasure() {
        val capture = AudioCaptureController(context)
        val executor = Executors.newSingleThreadExecutor()
        capture.javaClass.getDeclaredField("captureExecutor").apply { isAccessible = true }.set(capture, executor)
        val blocked = CountDownLatch(1); val release = CountDownLatch(1)
        executor.execute { blocked.countDown(); check(release.await(5, TimeUnit.SECONDS)) }
        assertTrue(blocked.await(5, TimeUnit.SECONDS))
        try {
            capture.javaClass.getDeclaredMethod("enqueueCapture", AudioEventType::class.java, Boolean::class.javaObjectType)
                .apply { isAccessible = true }.invoke(capture, AudioEventType.ROUTE_CHANGE, true)
            ResearchPersistenceGate.stop { fence.erase(setOf(CollectionModuleId.BATTERY_TELEMETRY), durableIntent = false) }
            release.countDown(); executor.submit { }.get(5, TimeUnit.SECONDS)
            assertEquals(1, db.audioActivitySampleDao().count())
        } finally { release.countDown(); executor.shutdownNow(); assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS)) }
    }
}
