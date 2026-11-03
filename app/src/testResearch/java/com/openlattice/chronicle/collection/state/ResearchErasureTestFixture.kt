package com.openlattice.chronicle.collection.state

import com.openlattice.chronicle.WorkManagerRule
import com.openlattice.chronicle.android.fromInteractionType
import com.openlattice.chronicle.base.OK

import android.content.Context
import android.content.SharedPreferences
import android.graphics.Rect
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.openlattice.chronicle.EnrollmentTestStores
import com.openlattice.chronicle.android.AndroidSensorType
import com.openlattice.chronicle.android.ChronicleData
import com.openlattice.chronicle.android.ChronicleUsageEvent
import com.openlattice.chronicle.android.UploadReceipt
import com.openlattice.chronicle.api.EnrollmentPreviewResponse
import com.openlattice.chronicle.api.MobileEnrollmentManifest
import com.openlattice.chronicle.audit.studyApi
import com.openlattice.chronicle.collection.CollectionModuleId
import com.openlattice.chronicle.collection.CollectionModuleSetting
import com.openlattice.chronicle.collection.ConsentTrigger
import com.openlattice.chronicle.collection.InteractionPolicy
import com.openlattice.chronicle.collection.SensorCollectionModules
import com.openlattice.chronicle.collection.directboot.KeystoreDirectBootRecordCipher
import com.openlattice.chronicle.collection.interaction.InteractionCollectionService
import com.openlattice.chronicle.collection.sensors.FakeSensorGateway
import com.openlattice.chronicle.collection.sensors.FakeSensorRuntimeSettings
import com.openlattice.chronicle.collection.sensors.ManualSensorRuntimeScheduler
import com.openlattice.chronicle.collection.sensors.SensorGateway
import com.openlattice.chronicle.collection.sensors.SensorRuntimeController
import com.openlattice.chronicle.collection.sensors.SensorRuntimeScheduler
import com.openlattice.chronicle.collection.sensors.SensorRuntimeSettings
import com.openlattice.chronicle.collection.sink.SensorSampleSink
import com.openlattice.chronicle.collection.sink.SensorSampleWriter
import com.openlattice.chronicle.data.CaptureWindow
import com.openlattice.chronicle.data.MobileParticipationStatus
import com.openlattice.chronicle.data.ParticipationStatus
import com.openlattice.chronicle.models.ExtractedUsageEvent
import com.openlattice.chronicle.preferences.InteractionPolicySettings
import com.openlattice.chronicle.preferences.PARTICIPANT_ID
import com.openlattice.chronicle.preferences.PARTICIPATION_STATUS
import com.openlattice.chronicle.preferences.STUDY_ID
import com.openlattice.chronicle.serialization.ChronicleCallException
import com.openlattice.chronicle.serialization.ChronicleJson
import com.openlattice.chronicle.serialization.JsonSerializer
import com.openlattice.chronicle.services.upload.LOCAL_STORAGE_RESERVE_BYTES
import com.openlattice.chronicle.services.upload.UploadWorkerDelegate
import com.openlattice.chronicle.services.upload.decodedUploadBody
import com.openlattice.chronicle.storage.ChronicleDb
import com.openlattice.chronicle.storage.CollectionModuleStateEntity
import com.openlattice.chronicle.storage.QueueEntry
import com.openlattice.chronicle.storage.UPLOAD_EVIDENCE_CALLBACK
import com.openlattice.chronicle.storage.UploadServerEntity
import com.openlattice.chronicle.storage.interactionSampleDao
import java.lang.reflect.Proxy
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.util.UUID
import java.util.concurrent.AbstractExecutorService
import java.util.concurrent.TimeUnit
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements

@RunWith(RobolectricTestRunner::class)
@Config(shadows = [ResearchErasureTestFixture.TestCipher::class, ResearchErasureTestFixture.TestCipherKeys::class,
    EnrollmentTestStores.LifecycleModules::class])
abstract class ResearchErasureTestFixture {
    @get:Rule val workManagerRule = WorkManagerRule()
    protected val context: Context = ApplicationProvider.getApplicationContext()
    protected lateinit var db: ChronicleDb
    protected lateinit var prefs: SharedPreferences
    protected val coordinator get() = CollectionLoopCoordinator(context)
    protected val fence get() = ResearchErasureFence(context)
    protected val owner get() = db.uploadServerDao().getConfiguredServer()!!

    @Implements(value = KeystoreDirectBootRecordCipher::class, isInAndroidSdk = false)
    class TestCipher {
        @Implementation fun encrypt(plaintext: ByteArray): ByteArray = plaintext.reversedArray()
        @Implementation fun decrypt(blob: ByteArray): ByteArray = blob.reversedArray()
    }

    @Implements(value = KeystoreDirectBootRecordCipher.Companion::class, isInAndroidSdk = false)
    class TestCipherKeys { @Implementation fun ensureKey() = Unit }

    @Before fun setUp() {
        ResearchPersistenceGate.resetAfterLocalStoreRecovery()

        prefs = context.getSharedPreferences("erasure-regression-test", Context.MODE_PRIVATE)
        prefs.edit().clear().putString(STUDY_ID, "11111111-1111-1111-1111-111111111111")
            .putString(PARTICIPANT_ID, "participant-A")
            .putString(PARTICIPATION_STATUS, ParticipationStatus.ENROLLED.name).commit()
        EnrollmentTestStores.prefs = prefs
        context.getSharedPreferences("research_erasure_fence", Context.MODE_PRIVATE).edit().clear().commit()
        context.getSharedPreferences("server_clock", Context.MODE_PRIVATE).edit().clear().commit()
        db = Room.inMemoryDatabaseBuilder(context, ChronicleDb::class.java).allowMainThreadQueries().addCallback(UPLOAD_EVIDENCE_CALLBACK).build()
        EnrollmentTestStores.db = db
        db.uploadServerDao().insert(UploadServerEntity(name = "study", url = "https://localhost", apiKey = "test-device-key",
            studyId = prefs.getString(STUDY_ID, "")!!, participantId = "participant-A", sourceRandomDeviceId = "device"))
        CollectionModuleId.values().filter { it.active }.forEach { consent(it, ParticipantDecision.ACCEPTED) }
        onPersistenceWorker { ResearchPersistenceGate.initialize(context) }
        assertTrue(StorageAdmission.allowed(context, LOCAL_STORAGE_RESERVE_BYTES))
    }

    @After fun tearDown() {
        ResearchPersistenceGate.resetAfterLocalStoreRecovery()
        AndroidSensorType.values().forEach { SensorSampleWriter.discardSensorSamples(it.name) }
        StorageAdmission.clear(context)
        EnrollmentTestStores.db = null
        EnrollmentTestStores.prefs = null
        InteractionPolicySettings.invalidateMemoryCache()
        db.close()
    }

    /** Sensor availability is not consent-gated; the enrollment's study settings switch it. */
    protected fun enrollWithSensorAvailability(enabled: Boolean,
                                               modules: Map<CollectionModuleId, CollectionModuleSetting> = emptyMap()): UploadServerEntity {
        // Production never stores a decision row for it; drop the one setUp() seeds for every module.
        db.openHelper.writableDatabase.execSQL("DELETE FROM collection_module_state WHERE moduleId = ?",
            arrayOf(CollectionModuleId.SENSOR_AVAILABILITY.id))
        val preview = ChronicleJson.moshi
            .adapter(EnrollmentPreviewResponse::class.java)
            .fromJson(javaClass.getResource("/enrollment-preview.json")!!.readText())!!
        val settings = preview.manifest.collectionSettings.let { it.copy(modules = it.modules + modules +
            (CollectionModuleId.SENSOR_AVAILABILITY to CollectionModuleSetting(enabled = enabled))) }
        val manifest = preview.manifest.copy(studyId = UUID.fromString(owner.studyId),
            participantId = owner.participantId, serverOrigin = owner.url, collectionSettings = settings,
            settingsVersion = settings.settingsVersion)
        return owner.copy(studyDisclosureJson = ChronicleJson.moshi
            .adapter(MobileEnrollmentManifest::class.java).toJson(manifest),
            disclosureVersion = manifest.participantPolicy.version, manifestDigest = preview.manifestDigest)
            .also { db.uploadServerDao().update(it); onPersistenceWorker { ResearchPersistenceGate.initialize(context) } }
    }

    protected fun consent(module: CollectionModuleId, decision: ParticipantDecision, enabled: Boolean = true) {
        ResearchPersistenceGate.captureObservation(context, module)
        stopOnPersistenceWorker {
            db.collectionModuleStateDao().upsertAll(listOf(CollectionModuleStateEntity(
                module.id, enabled, decision.name, 0, false, 1)))
        }
    }

    protected fun decide(module: CollectionModuleId, accepted: Boolean, context: Context = this.context) =
        onPersistenceWorker { decideOnWorker(module, accepted, context) }

    /** Caller already runs on the persistence worker, including nested stop actions. */
    protected fun decideOnWorker(module: CollectionModuleId, accepted: Boolean, context: Context = this.context) {
        withStudyApi { method -> check(method == "reportCollectionAck"); OK() }.use {
            CollectionLoopCoordinator(context).applyDecisions(
                if (accepted) setOf(module) else emptySet(),
                if (accepted) emptySet() else setOf(module), ConsentTrigger.values().first())
        }
    }

    protected fun eraseAndReaccept(module: CollectionModuleId) { decide(module, false); decide(module, true) }

    protected fun event(at: Long = System.currentTimeMillis()) = ExtractedUsageEvent(eventType = fromInteractionType("Activity Resumed"),
        appPackageName = "example.app", interactionType = "Activity Resumed",
        timestamp = Instant.ofEpochMilli(at).atOffset(ZoneOffset.UTC), timezone = "UTC",
        user = "participant-A", applicationLabel = "Example", activityClass = "MainActivity")

    protected fun utc(millis: Long): OffsetDateTime = Instant.ofEpochMilli(millis).atOffset(ZoneOffset.UTC)

    /** The status poll's body: [status] at the server's [serverTime] with its closed capture [windows]. */
    protected fun polled(status: ParticipationStatus, windows: List<CaptureWindow> = emptyList(),
                         serverTime: Long = System.currentTimeMillis()) =
        MobileParticipationStatus(status, utc(serverTime), windows)

    protected fun row(event: ExtractedUsageEvent = event()) = QueueEntry(1, 1, JsonSerializer.serializeQueueEntry(listOf(event)))

    protected fun forbiddenUploadRecovers(resolve: () -> Unit) {
        val expected = owner.copy(apiKey = "test-key")
        db.uploadServerDao().update(expected)
        db.queueEntryData().insertEntry(row())
        var halted = true
        var calls = 0
        var sent = emptySet<UUID>()
        withStudyApi(onCall = { args -> sent = decodedUploadBody<ChronicleData>(args[3]!!)
            .mapTo(linkedSetOf()) { checkNotNull((it as ChronicleUsageEvent).clientEventId) } }) { method ->
            check(method == "uploadAndroidUsageEventData")
            calls++
            if (halted) throw ChronicleCallException("POST", "url", "", 403)
            UploadReceipt(sent, emptySet(), 1)
        }.use {
            val uploader = UploadWorkerDelegate(context, db)
            assertEquals(1, onPersistenceWorker { uploader.execute() })
            assertEquals(1, db.queueEntryData().getSize())
            halted = false
            resolve()
            assertEquals(0, onPersistenceWorker { uploader.execute() })
            assertEquals(2, calls)
            assertEquals(0, db.queueEntryData().getSize())
        }
    }

    protected fun runtime(retain: Boolean = false, gateway: SensorGateway = FakeSensorGateway(),
                        settings: SensorRuntimeSettings = FakeSensorRuntimeSettings(),
                        scheduler: SensorRuntimeScheduler = ManualSensorRuntimeScheduler(executeImmediately = false)) = SensorRuntimeController(
                            gateway, settings,
        SensorSampleSink(db.sensorSampleDao(), ResearchPersistenceGate.guard(context)),
        scheduler,
        collectionGate = { ResearchPersistenceGate.collectsNow(context, SensorCollectionModules.moduleFor(it)) },
        observationAdmission = { ResearchPersistenceGate.captureObservation(context, SensorCollectionModules.moduleFor(it)) },
        sampleLease = { ResearchPersistenceGate.withReadLease(it) }, retainOnStop = retain)

    @Suppress("DEPRECATION")
    protected fun scroll(time: Long, direction: Int): AccessibilityEvent {
        val node = AccessibilityNodeInfo.obtain()
        node.setBoundsInScreen(Rect(0, 0, 100, 100))
        return AccessibilityEvent.obtain(AccessibilityEvent.TYPE_VIEW_SCROLLED).also {
            shadowOf(it).setSourceNode(node)
            it.eventTime = time
            it.scrollDeltaY = direction
            it.scrollDeltaX = 0
            it.className = "android.widget.ScrollView"
            it.packageName = "example.app"
        }
    }

    protected fun queuedInteraction(erase: Boolean, expectedRows: Int) {
        val policy = InteractionPolicySettings(context)
        val study = UUID.fromString(owner.studyId)
        assertTrue(policy.save(study, 1, true, InteractionPolicy.DEFAULT))
        val service = Robolectric.buildService(InteractionCollectionService::class.java).create(
            ).get()
        org.robolectric.util.ReflectionHelpers.callInstanceMethod<Unit>(service, "onServiceConnected")
        try {
            onPersistenceWorker {
                ResearchPersistenceGate.stop {
                    // The real callback captures its origin before this write lease retires it.
                    service.onAccessibilityEvent(scroll(100, 10))
                    if (erase) {
                        decideOnWorker(CollectionModuleId.INTERACTION_EVENTS, false)
                        decideOnWorker(CollectionModuleId.INTERACTION_EVENTS, true)
                    }
                    else assertTrue(policy.save(study, 1, true, InteractionPolicy.DEFAULT))
                }
            }
            if (erase) {
                // A later callback proves the actual worker drained; the old queued row cannot return.
                service.onAccessibilityEvent(scroll(200, -10))
                awaitInteractionRows(1)
                assertEquals(listOf(200L), db.interactionSampleDao().getOldest(10).map { it.eventTimeMillis })
                db.interactionSampleDao().deleteAll()
            } else awaitInteractionRows(1)
            assertEquals(expectedRows, db.interactionSampleDao().count())
        } finally { service.onDestroy() }
    }

    protected fun awaitInteractionRows(expected: Int) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
        while (db.interactionSampleDao().count() < expected && System.nanoTime() < deadline) Thread.sleep(10)
        assertEquals(expected, db.interactionSampleDao().count())
    }

    /** Serves [answer] until closed, restoring the previous API fixture. */
    protected fun withStudyApi(onCall: (Array<out Any?>) -> Unit = {}, answer: (String) -> Any): AutoCloseable =
        EnrollmentTestStores.installApi(studyApi { method, args ->
            onCall(args.orEmpty())
            answer(method)
        })

    @Suppress("UNCHECKED_CAST")
    protected fun <T> proxy(type: Class<T>, read: () -> Any?): T = Proxy.newProxyInstance(type.classLoader, arrayOf(type)) {
        _, method, _ -> if (method.name == "read") read() else null
    } as T

    protected class QueuedExecutor : AbstractExecutorService() {
        val tasks = mutableListOf<Runnable>()
        private var closed = false
        override fun execute(command: Runnable) { tasks += command }
        fun runAll() { tasks.toList().also { tasks.clear() }.forEach(Runnable::run) }
        override fun shutdown() { closed = true }
        override fun shutdownNow(): MutableList<Runnable> = tasks.toMutableList().also { tasks.clear(); closed = true }
        override fun isShutdown() = closed
        override fun isTerminated() = closed
        override fun awaitTermination(timeout: Long, unit: TimeUnit) = closed
    }
}
