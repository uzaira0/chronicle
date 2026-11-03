package com.openlattice.chronicle.collection.state

import android.database.sqlite.SQLiteException
import com.openlattice.chronicle.storage.audioActivitySampleDao
import java.io.IOException
import org.junit.Rule

import android.app.Application
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.SharedPreferences
import android.os.Looper
import android.media.AudioManager
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.work.WorkManager
import com.openlattice.chronicle.EnrollmentTestStores
import com.openlattice.chronicle.WorkManagerRule
import com.openlattice.chronicle.android.AndroidSensorType
import com.openlattice.chronicle.collection.AudioEventType
import com.openlattice.chronicle.collection.CollectionModuleId
import com.openlattice.chronicle.collection.SensorCollectionModules
import com.openlattice.chronicle.collection.audio.AudioCaptureController
import com.openlattice.chronicle.collection.core.ModuleResult
import com.openlattice.chronicle.collection.sensors.FakeSensorGateway
import com.openlattice.chronicle.collection.sensors.FakeSensorRuntimeSettings
import com.openlattice.chronicle.collection.sensors.ManualSensorRuntimeScheduler
import com.openlattice.chronicle.collection.sensors.SensorRuntimeController
import com.openlattice.chronicle.collection.sink.SensorSampleSink
import com.openlattice.chronicle.collection.sink.SensorSampleWriter
import com.openlattice.chronicle.collection.usage.DaoUsagePollCheckpointStore
import com.openlattice.chronicle.preferences.PARTICIPANT_ID
import com.openlattice.chronicle.preferences.PARTICIPATION_STATUS
import com.openlattice.chronicle.preferences.STUDY_ID
import com.openlattice.chronicle.sensors.USAGE_EVENTS_SENSOR_CHECKPOINT
import com.openlattice.chronicle.services.upload.LOCAL_STORAGE_RESERVE_BYTES
import com.openlattice.chronicle.services.withdrawal.ParticipantWithdrawalManager
import com.openlattice.chronicle.services.withdrawal.WithdrawalState
import com.openlattice.chronicle.services.withdrawal.WithdrawalStateStore
import com.openlattice.chronicle.storage.ChronicleDb
import com.openlattice.chronicle.storage.checkedSQLiteOpenHelperFactory
import com.openlattice.chronicle.storage.CollectionModuleStateEntity
import com.openlattice.chronicle.storage.LocalStoreRecoveryManager
import com.openlattice.chronicle.storage.SensorSampleEntry
import com.openlattice.chronicle.storage.UploadServerEntity
import com.openlattice.chronicle.storage.UsagePollCheckpointEntity
import java.io.File
import java.lang.reflect.Proxy
import java.time.OffsetDateTime
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowLog

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [28])
class AndroidSweepReview2RegressionTest {
    @get:Rule val workManagerRule = WorkManagerRule()
    @get:Rule val backgroundPersistence = BackgroundPersistenceRule()
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
        EnrollmentTestStores.prefs = prefs
        context.getSharedPreferences("research_erasure_fence", Context.MODE_PRIVATE).edit().clear().commit()
        db = Room.inMemoryDatabaseBuilder(context, ChronicleDb::class.java).allowMainThreadQueries()
            .openHelperFactory(checkedSQLiteOpenHelperFactory { sql ->
                if ("upload_servers" in sql) {
                    if (Thread.currentThread() === Looper.getMainLooper().thread) mainQueries.incrementAndGet()
                    if (failQueries.get()) { queryFailed.countDown(); throw SQLiteException("transient Room failure") }
                }
            }).build()
        EnrollmentTestStores.db = db
        db.uploadServerDao().insert(UploadServerEntity(name = "study", url = "https://localhost", apiKey = "test-device-key",
            studyId = prefs.getString(STUDY_ID, "")!!, participantId = "participant-A", sourceRandomDeviceId = "device"))
        CollectionModuleId.values().filter { it.active }.forEach { consent(it) }
        ResearchPersistenceGate.initialize(context)
        StorageAdmission.allowed(context, LOCAL_STORAGE_RESERVE_BYTES)
        mainQueries.set(0)
    }

    private fun consent(id: CollectionModuleId, enabled: Boolean = true) {
        db.collectionModuleStateDao().upsertAll(listOf(CollectionModuleStateEntity(id.id, enabled,
            ParticipantDecision.ACCEPTED.name, 0, false, 1)))
    }
    private fun awaitWorker() {
        val done = CountDownLatch(1)
        ResearchPersistenceGate.executeAsync { done.countDown() }
        assertTrue(done.await(5, TimeUnit.SECONDS))
    }
    private fun waitUntil(timeoutSeconds: Long = 5, predicate: () -> Boolean) {
        val until = System.nanoTime() + TimeUnit.SECONDS.toNanos(timeoutSeconds)
        while (!predicate() && System.nanoTime() < until) Thread.sleep(10)
        assertTrue(predicate())
    }
    @After fun tearDown() {
        failQueries.set(false)
        awaitWorker()
        AndroidSensorType.values().forEach { SensorSampleWriter.discardSensorSamples(it.name) }
        ResearchPersistenceGate.resetAfterLocalStoreRecovery()
        EnrollmentTestStores.db = null
        EnrollmentTestStores.prefs = null
        db.close()
    }

    @Test fun lifecycleWithdrawalReconcilesAndPublishesOffMain() {
        WithdrawalStateStore(context).beginWithdrawal()
        ParticipantWithdrawalManager.resumePending(context)
        awaitWorker()
        assertEquals("lifecycle reconciliation must issue no MAIN DAO calls", 0, mainQueries.get())
        assertEquals("NOT_ENROLLED", prefs.getString(PARTICIPATION_STATUS, ""))
        waitUntil { WorkManager.getInstance(context).getWorkInfosForUniqueWork(ParticipantWithdrawalManager.WORK_NAME).get().isNotEmpty() }
    }

    @Test fun failedWithdrawalAndEachPrivacyOperationRequireTheirOwnRetry() {
        val rawFence = context.getSharedPreferences("research_erasure_fence", Context.MODE_PRIVATE)
        val failingFence = Proxy.newProxyInstance(SharedPreferences::class.java.classLoader, arrayOf(SharedPreferences::class.java)) { _, method,
            args ->
            if (method.name == "edit") {
                val editor = rawFence.edit()
                Proxy.newProxyInstance(SharedPreferences.Editor::class.java.classLoader, arrayOf(SharedPreferences.Editor::class.java)) { proxy, edit,
                    values ->
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
        listOf(ResearchPersistenceGate.PrivacyOperationKind.PARTICIPANT_DECISION, ResearchPersistenceGate.PrivacyOperationKind.ERASURE_REPLAY,
            ResearchPersistenceGate.PrivacyOperationKind.SETTINGS_APPLY).forEach { kind ->
            val operation = ResearchPersistenceGate.PrivacyOperation(kind, "original")
            assertThrows(IOException::class.java) { ResearchPersistenceGate.stop(operation) { throw IOException(
                "failed privacy write") } }
            ResearchPersistenceGate.stop { }
            ResearchPersistenceGate.stop(ResearchPersistenceGate.PrivacyOperation(kind, "unrelated")) { }
            assertFalse("$kind must retain its own failure", ResearchPersistenceGate.collectsNow(context, module))
            ResearchPersistenceGate.stop(operation) { }
            assertTrue(ResearchPersistenceGate.collectsNow(context, module))
        }
    }

    @Test fun coldInitializationRetriesLocallyWithoutCallbacksOrNetwork() {
        failQueries.set(true)
        ResearchPersistenceGate.initializeAsync(context)
        assertTrue(queryFailed.await(5, TimeUnit.SECONDS))
        awaitWorker()
        assertFalse(ResearchPersistenceGate.collectsNow(context, module))
        failQueries.set(false)
        waitUntil { ResearchPersistenceGate.collectsNow(context, module) }
        awaitWorker()
        val previousRetries = ShadowLog.getLogsForTag("ResearchPersistenceGate").count {
            it.msg == "Research authorization initialization will retry locally"
        }
        failQueries.set(true)
        ResearchPersistenceGate.initializeAsync(context)
        // Reach the capped production retry through failed Room reads, without altering its counter.
        waitUntil(timeoutSeconds = 45) { ShadowLog.getLogsForTag("ResearchPersistenceGate").count {
            it.msg == "Research authorization initialization will retry locally"
        } >= previousRetries + 8 }
        awaitWorker()
        ResearchPersistenceGate.resetAfterLocalStoreRecovery()
        ResearchPersistenceGate.initializeAsync(context)
        awaitWorker()
        failQueries.set(false)
        waitUntil { ResearchPersistenceGate.collectsNow(context, module) }
    }

    @Test fun serverEnablementPublishesOfflineAdmission() {
        val id = owner.id
        ResearchPersistenceGate.stop { db.uploadServerDao().setEnabled(id, false) }
        assertFalse(ResearchPersistenceGate.collectsNow(context, CollectionModuleId.USAGE_EVENTS))
        assertEquals(1, ResearchPersistenceGate.setServerEnabled(context, id, true))
        assertTrue(ResearchPersistenceGate.collectsNow(context, CollectionModuleId.USAGE_EVENTS))
        assertEquals(1, ResearchPersistenceGate.setServerEnabled(context, id, false))
        assertFalse(ResearchPersistenceGate.captureObservation(context, CollectionModuleId.USAGE_EVENTS).isCurrent())
    }

    @Test fun recoveryReplayPreservesSplitDiagnosticsAndErasesUnsplitDatabase() {
        val root = File(context.noBackupFilesDir, "chronicle-recovery")
        val bundle = File(root, "split").apply { mkdirs() }
        File(bundle, "manifest.txt").writeText(
            "owner_scope_sha256=unknown\ndiagnostics_separated=true\ndiagnostic_artifact=diagnostic-snapshot.enc\n")
        File(bundle, "artifact-chronicle.db.enc").writeText("encrypted research and diagnostics")
        val diagnostic = "encrypted immutable diagnostic journal".toByteArray()
        File(bundle, "diagnostic-snapshot.enc").writeBytes(diagnostic)
        LocalStoreRecoveryManager.erasePendingRecoveryArtifacts(context, null)
        assertFalse(bundle.exists())
        val preserved = File(context.noBackupFilesDir, "chronicle-diagnostics-recovery/split/diagnostic-snapshot.enc")
        assertArrayEquals(diagnostic, preserved.readBytes())
        val unsplit = File(root, "unsplit").apply { mkdirs() }
        File(unsplit, "artifact-chronicle.db.enc").writeText("only remaining diagnostic journal")
        LocalStoreRecoveryManager.erasePendingRecoveryArtifacts(context, null)
        assertFalse(unsplit.exists())
    }

    @Test fun newEnrollmentCannotAdoptAnotherOwnersUnstampedUsageCheckpoint() {
        db.usagePollCheckpointDao().upsert(UsagePollCheckpointEntity(USAGE_EVENTS_SENSOR_CHECKPOINT, 9000))
        val store = DaoUsagePollCheckpointStore(db.usagePollCheckpointDao(), "replacement-owner:1", 0)
        assertNull(store.readPreviousPollTimestamp())
    }

    @Test fun unstampedUnlockRamCannotAcquireConsentFromTimestamps() {
        val old = SensorRuntimeController(FakeSensorGateway(), FakeSensorRuntimeSettings(), SensorSampleWriter { ModuleResult.Retry(
            "DE append unavailable") }, ManualSensorRuntimeScheduler(false))
        old.recordSample(AndroidSensorType.accelerometer, floatArrayOf(1f), 1, OffsetDateTime.now())
        ResearchPersistenceGate.stop { consent(module, false) }
        old.stop(true) { ResearchPersistenceGate.guardForRetainedRegistration(context, module, null) }
        val replacement = SensorRuntimeController(FakeSensorGateway(), FakeSensorRuntimeSettings(),
            SensorSampleSink(db.sensorSampleDao(), ResearchPersistenceGate.guard(context, module)),
            ManualSensorRuntimeScheduler(false), collectionGate = { ResearchPersistenceGate.collectsNow(context, module) },
            retainOnStop = true)
        assertEquals(0, replacement.bufferedCount)
        ResearchPersistenceGate.stop { consent(module) }
        replacement.flushBuffer()
        assertEquals(0, db.sensorSampleDao().count())
        val unstamped = ResearchPersistenceGate.guardForRetainedRegistration(context, module, null)
        assertFalse(unstamped.isCurrent())
        assertFalse(unstamped.persist { error("Unstamped observation persisted") })
        replacement.stop()
    }

    @Test fun largeFailedFlushRequeuesEachSampleOnce() {
        var reject = true
        val batches = mutableListOf<List<SensorSampleEntry>>()
        val sink = SensorSampleSink(db.sensorSampleDao(), ResearchPersistenceGate.guard(context, module))
        val runtime = SensorRuntimeController(FakeSensorGateway(), FakeSensorRuntimeSettings(), SensorSampleWriter { samples ->
            batches += samples.toList()
            if (reject) ModuleResult.Retry("HOLD") else sink.write(samples)
        },
            ManualSensorRuntimeScheduler(false))
        repeat(5_000) { runtime.recordSample(AndroidSensorType.accelerometer, floatArrayOf(it.toFloat()), 1, OffsetDateTime.now()) }
        assertTrue(runtime.flushBuffer() is ModuleResult.Retry)
        assertEquals(5_000, runtime.bufferedCount)
        assertTrue(runtime.flushBuffer() is ModuleResult.Retry)
        assertEquals(5_000, runtime.bufferedCount)
        val expected = batches.first().map { it.id }.toSet()
        assertEquals(5_000, expected.size)
        assertEquals(listOf(expected, expected), batches.map { it.map { sample -> sample.id }.toSet() })
        assertTrue(batches.all { it.size == expected.size })
        reject = false
        assertEquals(ModuleResult.Ok(5_000), runtime.flushBuffer())
        assertEquals(0, runtime.bufferedCount)
        assertEquals(expected, db.sensorSampleDao().getOldest(5_001).map { it.id }.toSet())
        assertEquals(5_000, db.sensorSampleDao().count())
        runtime.stop()
    }

    @Test fun queuedAudioSurvivesUnrelatedModuleErasure() {
        val capture = AudioCaptureController(context)
        capture.register()
        try {
            val receiver = shadowOf(context as Application).registeredReceivers.single {
                it.intentFilter.hasAction(AudioManager.ACTION_AUDIO_BECOMING_NOISY)
            }.broadcastReceiver
            ResearchPersistenceGate.stop {
                receiver.onReceive(context, Intent(AudioManager.ACTION_AUDIO_BECOMING_NOISY))
                fence.erase(setOf(CollectionModuleId.BATTERY_TELEMETRY), durableIntent = false)
            }
            waitUntil { db.audioActivitySampleDao().getOldest(10).count { it.eventType == AudioEventType.BECOMING_NOISY.name } == 1 }
            assertEquals(1, db.audioActivitySampleDao().getOldest(10).count { it.eventType == AudioEventType.BECOMING_NOISY.name })
        } finally { capture.unregister() }
    }
}
