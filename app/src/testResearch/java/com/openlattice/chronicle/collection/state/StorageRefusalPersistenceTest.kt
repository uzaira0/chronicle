package com.openlattice.chronicle.collection.state

import android.content.Context
import android.content.SharedPreferences
import android.os.SystemClock
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.work.Configuration
import androidx.work.WorkManager
import com.openlattice.chronicle.android.AndroidSensorType
import com.openlattice.chronicle.collection.CollectionModuleId
import com.openlattice.chronicle.collection.CollectionDataDisposition
import com.openlattice.chronicle.collection.core.ModuleResult
import com.openlattice.chronicle.collection.core.NoOpCollectionLog
import com.openlattice.chronicle.collection.sensors.FakeSensorGateway
import com.openlattice.chronicle.collection.sensors.FakeSensorRuntimeSettings
import com.openlattice.chronicle.collection.sensors.ManualSensorRuntimeScheduler
import com.openlattice.chronicle.collection.sensors.SensorRuntimeController
import com.openlattice.chronicle.collection.sink.ActivityRecognitionSampleSink
import com.openlattice.chronicle.collection.sink.AppNetworkUsageSampleSink
import com.openlattice.chronicle.collection.sink.BatterySampleSink
import com.openlattice.chronicle.collection.sink.ConnectivityStateSampleSink
import com.openlattice.chronicle.collection.sink.DeviceSettingsSampleSink
import com.openlattice.chronicle.collection.sink.HealthMetricSampleSink
import com.openlattice.chronicle.collection.sink.LifecycleEventSink
import com.openlattice.chronicle.collection.sink.SensorSampleSink
import com.openlattice.chronicle.collection.sink.SleepSampleSink
import com.openlattice.chronicle.collection.sink.UsageEventSink
import com.openlattice.chronicle.collection.usage.UsageModulePersistence
import com.openlattice.chronicle.data.ParticipationStatus
import com.openlattice.chronicle.preferences.EncryptedPrefsHelper
import com.openlattice.chronicle.preferences.PARTICIPANT_ID
import com.openlattice.chronicle.preferences.PARTICIPATION_STATUS
import com.openlattice.chronicle.preferences.STUDY_ID
import com.openlattice.chronicle.services.upload.LOCAL_STORAGE_RESERVE_BYTES
import com.openlattice.chronicle.services.upload.LocalUploadModuleFamily
import com.openlattice.chronicle.services.upload.recordAbandonedGateBatch
import com.openlattice.chronicle.services.withdrawal.WithdrawalState
import com.openlattice.chronicle.services.withdrawal.WithdrawalStateStore
import com.openlattice.chronicle.storage.ActivityRecognitionSampleEntry
import com.openlattice.chronicle.storage.AppNetworkUsageSampleEntry
import com.openlattice.chronicle.storage.BatterySampleEntry
import com.openlattice.chronicle.storage.ChronicleDb
import com.openlattice.chronicle.storage.CollectionModuleStateEntity
import com.openlattice.chronicle.storage.ConnectivityStateSampleEntry
import com.openlattice.chronicle.storage.DeviceSettingsSampleEntry
import com.openlattice.chronicle.storage.HealthMetricSampleEntry
import com.openlattice.chronicle.storage.MIGRATION_17_18
import com.openlattice.chronicle.storage.QueueEntry
import com.openlattice.chronicle.storage.SleepSampleEntry
import com.openlattice.chronicle.storage.UploadServerEntity
import com.openlattice.chronicle.storage.activityRecognitionSampleDao
import com.openlattice.chronicle.storage.healthMetricSampleDao
import com.openlattice.chronicle.storage.sleepSampleDao
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements
import com.openlattice.chronicle.collection.directboot.KeystoreDirectBootRecordCipher
import java.util.concurrent.Executor
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class)
@Config(shadows = [StorageRefusalPersistenceTest.TestDirectBootCipher::class])
class StorageRefusalPersistenceTest {
    @get:org.junit.Rule val backgroundPersistence = com.openlattice.chronicle.collection.state.BackgroundPersistenceRule()
    private val context: Context = ApplicationProvider.getApplicationContext()
    private lateinit var db: ChronicleDb
    private lateinit var prefs: SharedPreferences
    private val timestamp = "2026-09-29T00:00:00Z"
    private val losses = mutableListOf<Pair<String, Int>>()

    @Implements(value = KeystoreDirectBootRecordCipher::class, isInAndroidSdk = false)
    class TestDirectBootCipher {
        @Implementation fun encrypt(plaintext: ByteArray): ByteArray = plaintext.reversedArray()
        @Implementation fun decrypt(blob: ByteArray): ByteArray = blob.reversedArray()
    }

    @Before fun setUp() {
        ResearchPersistenceGate.resetAfterLocalStoreRecovery()
        prefs = context.getSharedPreferences("storage-refusal-test", Context.MODE_PRIVATE)
        prefs.edit().clear().putString(STUDY_ID, "11111111-1111-1111-1111-111111111111")
            .putString(PARTICIPANT_ID, "participant")
            .putString(PARTICIPATION_STATUS, ParticipationStatus.ENROLLED.name).commit()
        EncryptedPrefsHelper::class.java.getDeclaredField("instance").apply { isAccessible = true }
            .set(EncryptedPrefsHelper, prefs)
        db = Room.inMemoryDatabaseBuilder(context, ChronicleDb::class.java).allowMainThreadQueries().build()
        ChronicleDb::class.java.getDeclaredField("INSTANCE").apply { isAccessible = true }.set(null, db)
        db.uploadServerDao().insert(UploadServerEntity(
            name = "study", url = "https://localhost", studyId = prefs.getString(STUDY_ID, "")!!,
            participantId = "participant", sourceDeviceId = "device",
        ))
        MIGRATION_17_18.migrate(db.openHelper.writableDatabase)
        if (!WorkManager.isInitialized()) {
            WorkManager.initialize(context, Configuration.Builder().setExecutor(Executor { }).build())
        }
        consent(ParticipantDecision.ACCEPTED)
        consent(ParticipantDecision.ACCEPTED, CollectionModuleId.SENSOR_GYROSCOPE)
        ResearchPersistenceGate.initialize(context)
        storage(LOCAL_STORAGE_RESERVE_BYTES)
        assertTrue(ResearchPersistenceGate.isActiveEnrollment(context))
    }

    @After fun tearDown() {
        ResearchPersistenceGate.resetAfterLocalStoreRecovery()
        StorageAdmission::class.java.getDeclaredField("lastCheckedAt").apply { isAccessible = true }
            .setLong(StorageAdmission, Long.MIN_VALUE)
        ChronicleDb::class.java.getDeclaredField("INSTANCE").apply { isAccessible = true }.set(null, null)
        EncryptedPrefsHelper::class.java.getDeclaredField("instance").apply { isAccessible = true }
            .set(EncryptedPrefsHelper, null)
        db.close()
    }

    private fun consent(decision: ParticipantDecision, module: CollectionModuleId = CollectionModuleId.SENSOR_ACCELEROMETER) {
        ResearchPersistenceGate.captureObservation(context, module)
        ResearchPersistenceGate.stop {
        db.collectionModuleStateDao().upsertAll(listOf(CollectionModuleStateEntity(
            moduleId = module.id, serverEnabled = true, decision = decision.name,
            decidedAtEpochMillis = 0, requiredApplied = false, appliedVersion = 1,
            appliedPolicySnapshot = null, lastDisposition = null,
        )))
        }
    }

    private fun storage(bytes: Long) {
        val allowed = StorageAdmission.allowed(context, bytes)
        // Prime the production cache with the real admission decision for deterministic free space.
        StorageAdmission::class.java.getDeclaredField("lastAllowed").apply { isAccessible = true }
            .setBoolean(StorageAdmission, allowed)
        StorageAdmission::class.java.getDeclaredField("lastCheckedAt").apply { isAccessible = true }
            .setLong(StorageAdmission, SystemClock.elapsedRealtime())
    }

    private fun runtime(): SensorRuntimeController = SensorRuntimeController(
        gateway = FakeSensorGateway(), settings = FakeSensorRuntimeSettings(),
        sink = SensorSampleSink(db.sensorSampleDao(), NoOpCollectionLog,
            ResearchPersistenceGate.guard(context, CollectionModuleId.SENSOR_ACCELEROMETER)),
        scheduler = ManualSensorRuntimeScheduler(executeImmediately = false),
        collectionGate = { true }, log = NoOpCollectionLog,
        observationAdmission = { ResearchPersistenceGate.captureObservation(context, com.openlattice.chronicle.collection.SensorCollectionModules.moduleFor(it)) },
        reportLoss = { code, count -> losses += code to count },
    ).also { controller ->
        repeat(3) { controller.recordSample(AndroidSensorType.accelerometer, floatArrayOf(1f, 2f, 3f), 3) }
    }

    @Test fun storageRefusalRequeuesCollectedBatchAndRecoveryPersistsIt() {
        val controller = runtime()
        storage(LOCAL_STORAGE_RESERVE_BYTES - 1)

        assertTrue(controller.flushBuffer() is ModuleResult.Retry)
        assertEquals(0, db.sensorSampleDao().count())
        assertTrue(losses.isEmpty())

        storage(LOCAL_STORAGE_RESERVE_BYTES)
        assertEquals(ModuleResult.Ok(3), controller.flushBuffer())
        assertEquals(3, db.sensorSampleDao().count())
        assertEquals(ModuleResult.Ok(0), controller.flushBuffer())
        assertTrue(losses.isEmpty())
    }

    private fun discardAccelerometer() = ResearchPersistenceGate.stop {
        CollectionLoopCoordinator::class.java.getDeclaredMethod("applyDisposition",
            CollectionModuleId::class.java, CollectionDataDisposition::class.java).apply { isAccessible = true }
            .invoke(CollectionLoopCoordinator(context), CollectionModuleId.SENSOR_ACCELEROMETER,
                CollectionDataDisposition.DISCARD_AND_STOP)
    }

    @Test fun discardScrubsStorageRetryBufferWhileAnotherSensorRemains() {
        val controller = runtime()
        controller.recordSample(AndroidSensorType.gyroscope, floatArrayOf(4f, 5f, 6f), 3)
        storage(LOCAL_STORAGE_RESERVE_BYTES - 1)
        assertTrue(controller.flushBuffer() is ModuleResult.Retry)
        discardAccelerometer()
        storage(LOCAL_STORAGE_RESERVE_BYTES)

        assertEquals(ModuleResult.Ok(1), controller.flushBuffer())
        assertEquals(listOf(AndroidSensorType.gyroscope.name), db.sensorSampleDao().getOldest(10).map { it.sensorType })
    }

    @Test fun discardInvalidatesDrainedBatchBeforeItAcquiresPersistenceLease() {
        assertDiscardInvalidatesInFlightBatch(storageRefusal = false)
    }

    @Test fun discardInvalidatesDrainedStorageRetryBeforeRequeue() {
        assertDiscardInvalidatesInFlightBatch(storageRefusal = true)
    }

    private fun assertDiscardInvalidatesInFlightBatch(storageRefusal: Boolean) {
        val atWrite = CountDownLatch(1)
        val writeNow = CountDownLatch(1)
        val controller = SensorRuntimeController(
            gateway = FakeSensorGateway(), settings = FakeSensorRuntimeSettings(),
            sink = SensorSampleSink(db.sensorSampleDao(), NoOpCollectionLog,
                object : CollectionPersistenceGuard {
                    override fun persist(persist: () -> Unit): Boolean = persistResult(persist) == CollectionPersistenceResult.PERSISTED
                    override fun persistResult(persist: () -> Unit): CollectionPersistenceResult {
                        atWrite.countDown()
                        check(writeNow.await(5, TimeUnit.SECONDS))
                        return if (storageRefusal) CollectionPersistenceResult.STORAGE_UNAVAILABLE
                            else ResearchPersistenceGate.guard(context).persistResult(persist)
                    }
                }),
            scheduler = ManualSensorRuntimeScheduler(executeImmediately = false), log = NoOpCollectionLog,
        )
        controller.recordSample(AndroidSensorType.accelerometer, floatArrayOf(1f, 2f, 3f), 3)
        val executor = Executors.newSingleThreadExecutor()
        val flush = executor.submit<ModuleResult> { controller.flushBuffer() }
        try {
            assertTrue(atWrite.await(5, TimeUnit.SECONDS))
            discardAccelerometer()
            writeNow.countDown()
            flush.get(3, TimeUnit.SECONDS)
            assertEquals(0, controller.bufferedCount)
            assertEquals(0, db.sensorSampleDao().count())
        } finally {
            writeNow.countDown()
            executor.shutdownNow()
            assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS))
        }
    }

    @Test fun abandonedSnapshotStorageRetriesRecordExactLossesForOriginalOwner() {
        val owner = ResearchPersistenceGate.captureOwner(context)!!
        listOf(LocalUploadModuleFamily.BATTERY, LocalUploadModuleFamily.CONNECTIVITY,
            LocalUploadModuleFamily.DEVICE_SETTINGS).forEach { family ->
            recordAbandonedGateBatch(context, owner, family, ModuleResult.Retry("local storage temporarily unavailable"), 1)
        }
        val losses = db.uploadDiagnosticDao().forEnrollment(owner.studyId, owner.participantId,
            owner.sourceDeviceId, "${owner.id}:${owner.createdAt}")
        assertEquals(3, losses.size)
        assertTrue(losses.all { it.issueCode == "COLLECTION_GATE_DROPPED" && it.count == 1 })
    }

    @Test fun consentRefusalStillDropsEvenWhenStorageAlsoRefuses() {
        val controller = runtime()
        storage(LOCAL_STORAGE_RESERVE_BYTES - 1)
        consent(ParticipantDecision.DECLINED)
        discardAccelerometer()

        assertEquals(ModuleResult.Ok(0), controller.flushBuffer())
        assertTrue(losses.isEmpty())
        consent(ParticipantDecision.ACCEPTED)
        storage(LOCAL_STORAGE_RESERVE_BYTES)
        assertEquals(ModuleResult.Ok(0), controller.flushBuffer())
        assertEquals(0, db.sensorSampleDao().count())
    }

    @Test fun withdrawalRefusalStillDropsCollectedBatch() {
        val controller = runtime()
        ResearchPersistenceGate.stop {
            ResearchErasureFence(context).erase(CollectionModuleId.values().toSet(), durableIntent = false)
            WithdrawalStateStore(context).setState(WithdrawalState.PENDING)
        }

        assertTrue(controller.flushBuffer() is ModuleResult.Skipped)
        assertEquals(0, controller.bufferedCount)
        assertTrue(losses.isEmpty())
        assertEquals(0, db.sensorSampleDao().count())
    }

    @Test fun allOtherGuardedSinksReturnRetryThenPersistTheSameBatchOnRecovery() {
        val guard = ResearchPersistenceGate.guard(context)
        val writes = listOf<Pair<String, () -> ModuleResult>>(
            "battery" to { BatterySampleSink(db.batterySampleDao(), NoOpCollectionLog, guard).write(listOf(
                BatterySampleEntry("battery", timestamp, "UTC", 80, "DISCHARGING", "NONE", 250, 4000, "GOOD"))) },
            "connectivity" to { ConnectivityStateSampleSink(db.connectivityStateSampleDao(), NoOpCollectionLog, guard).write(listOf(
                ConnectivityStateSampleEntry("connectivity", timestamp, "UTC", "SNAPSHOT", "WIFI", true, false, true))) },
            "device settings" to { DeviceSettingsSampleSink(db.deviceSettingsSampleDao(), NoOpCollectionLog, guard).write(listOf(
                DeviceSettingsSampleEntry("settings", timestamp, "UTC", false, 1f, false, false, false, null, true, true, 1000, 2000))) },
            "app network" to { AppNetworkUsageSampleSink(db.appNetworkUsageSampleDao(), NoOpCollectionLog, guard).write(listOf(
                AppNetworkUsageSampleEntry("network", timestamp, "UTC", "test.app", "WIFI", 100, 200, 0, 1000))) },
            CollectionModuleId.SLEEP.id to { SleepSampleSink(db.sleepSampleDao(), NoOpCollectionLog, guard).write(listOf(
                SleepSampleEntry("sleep-sample", timestamp, "UTC", "CLASSIFY", null, null, null, 90, 1, 1))) },
            "activity" to { ActivityRecognitionSampleSink(db.activityRecognitionSampleDao(), NoOpCollectionLog, guard).write(listOf(
                ActivityRecognitionSampleEntry("activity", timestamp, "UTC", "WALKING", 100, "ENTER"))) },
            "health" to { HealthMetricSampleSink(db.healthMetricSampleDao(), NoOpCollectionLog, guard).write(listOf(
                HealthMetricSampleEntry("health", timestamp, "UTC", "STEPS", 100.0, "count", 0, 1000, null))) },
            "usage" to { UsageEventSink(db.queueEntryData(), NoOpCollectionLog, guard).write(listOf(
                QueueEntry(1, 1, byteArrayOf(1)))) },
            "lifecycle" to { LifecycleEventSink(db.queueEntryData(), NoOpCollectionLog, guard).write(listOf(
                QueueEntry(2, 2, byteArrayOf(2)))) },
        )
        storage(LOCAL_STORAGE_RESERVE_BYTES - 1)
        writes.forEach { (name, write) -> assertTrue(name, write() is ModuleResult.Retry) }
        assertEquals(0, db.queueEntryData().getSize())
        storage(LOCAL_STORAGE_RESERVE_BYTES)
        writes.forEach { (name, write) -> assertEquals(name, ModuleResult.Ok(1), write()) }
        assertEquals(1, db.batterySampleDao().count())
        assertEquals(1, db.connectivityStateSampleDao().count())
        assertEquals(1, db.deviceSettingsSampleDao().count())
        assertEquals(1, db.appNetworkUsageSampleDao().count())
        assertEquals(1, db.sleepSampleDao().count())
        assertEquals(1, db.activityRecognitionSampleDao().count())
        assertEquals(1, db.healthMetricSampleDao().count())
        assertEquals(2, db.queueEntryData().getSize())
    }

    @Test fun storageRetryDoesNotAdvanceUsageCheckpoint() {
        var checkpoint = 100L
        val batch = listOf(QueueEntry(1, 1, byteArrayOf(1)))
        val sink = UsageEventSink(db.queueEntryData(), NoOpCollectionLog, ResearchPersistenceGate.guard(context))
        storage(LOCAL_STORAGE_RESERVE_BYTES - 1)
        val attempt = runCatching {
            UsageModulePersistence.persist(batch, 200, sink, { checkpoint = it },
                { block -> block() }, NoOpCollectionLog)
        }
        assertTrue(attempt.isFailure)
        assertEquals(100L, checkpoint)
        assertEquals(0, db.queueEntryData().getSize())
        storage(LOCAL_STORAGE_RESERVE_BYTES)
        UsageModulePersistence.persist(batch, 200, sink, { checkpoint = it },
            { block -> block() }, NoOpCollectionLog)
        assertEquals(200L, checkpoint)
        assertEquals(1, db.queueEntryData().getSize())
    }
}
