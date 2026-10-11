package com.openlattice.chronicle.collection.state

import android.app.AlarmManager
import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.os.SystemClock
import android.service.notification.StatusBarNotification
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.work.Configuration
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.WorkerFactory
import androidx.work.ListenableWorker
import androidx.work.ProgressUpdater
import androidx.work.ForegroundUpdater
import androidx.work.Data
import androidx.work.impl.utils.taskexecutor.WorkManagerTaskExecutor
import com.openlattice.chronicle.android.AndroidSensorType
import com.openlattice.chronicle.collection.AndroidDataCollectionSetting
import com.openlattice.chronicle.collection.BatteryChargingState
import com.openlattice.chronicle.collection.BatteryHealth
import com.openlattice.chronicle.collection.BatteryPlugType
import com.openlattice.chronicle.collection.CollectionAcknowledgment
import com.openlattice.chronicle.collection.CollectionModuleId
import com.openlattice.chronicle.collection.CollectionModuleSetting
import com.openlattice.chronicle.collection.ConsentTrigger
import com.openlattice.chronicle.collection.DistributionRestrictedRuntime
import com.openlattice.chronicle.collection.HealthMetricType
import com.openlattice.chronicle.collection.InteractionPolicy
import com.openlattice.chronicle.collection.NetworkTransport
import com.openlattice.chronicle.collection.SensorCollectionModules
import com.openlattice.chronicle.collection.battery.BatteryReading
import com.openlattice.chronicle.collection.battery.BatteryTelemetryCollectionModule
import com.openlattice.chronicle.collection.core.ModuleResult
import com.openlattice.chronicle.collection.core.NoOpCollectionLog
import com.openlattice.chronicle.collection.device.AndroidAppNetworkUsageSource
import com.openlattice.chronicle.collection.device.AndroidDeviceSettingsSource
import com.openlattice.chronicle.collection.device.AndroidHealthMetricSource
import com.openlattice.chronicle.collection.device.ConnectivityStateCollectionModule
import com.openlattice.chronicle.collection.device.ConnectivityStateReading
import com.openlattice.chronicle.collection.device.DeviceSettingsCollectionModule
import com.openlattice.chronicle.collection.device.HealthMetricCollectionModule
import com.openlattice.chronicle.collection.device.HealthMetricReadCoordinator
import com.openlattice.chronicle.collection.device.HealthMetricReading
import com.openlattice.chronicle.collection.directboot.DirectBootSnapshotWriter
import com.openlattice.chronicle.collection.directboot.KeystoreDirectBootRecordCipher
import com.openlattice.chronicle.collection.sensors.FakeSensorGateway
import com.openlattice.chronicle.collection.sensors.FakeSensorRuntimeSettings
import com.openlattice.chronicle.collection.sensors.ManualSensorRuntimeScheduler
import com.openlattice.chronicle.collection.sensors.SensorGateway
import com.openlattice.chronicle.collection.sensors.SensorRuntimeController
import com.openlattice.chronicle.collection.sensors.SensorRuntimeScheduler
import com.openlattice.chronicle.collection.sensors.SensorRuntimeSettings
import com.openlattice.chronicle.collection.sink.BatterySampleSink
import com.openlattice.chronicle.collection.sink.ConnectivityStateSampleSink
import com.openlattice.chronicle.collection.sink.DeviceSettingsSampleSink
import com.openlattice.chronicle.collection.sink.HealthMetricSampleSink
import com.openlattice.chronicle.collection.sink.SensorSampleSink
import com.openlattice.chronicle.collection.usage.DaoUsagePollCheckpointStore
import com.openlattice.chronicle.collection.usage.UsageEventPoller
import com.openlattice.chronicle.collection.usage.UsageEventsCollectionModule
import com.openlattice.chronicle.collection.usage.UsageModulePersistence
import com.openlattice.chronicle.data.ParticipationStatus
import com.openlattice.chronicle.models.ExtractedUsageEvent
import com.openlattice.chronicle.preferences.DirectBootSensorSnapshot
import com.openlattice.chronicle.preferences.EncryptedPrefsHelper
import com.openlattice.chronicle.preferences.EnrollmentSettings
import com.openlattice.chronicle.preferences.InteractionPolicySettings
import com.openlattice.chronicle.preferences.MOBILE_REMINDER_REQUEST_CODES
import com.openlattice.chronicle.preferences.PARTICIPANT_ID
import com.openlattice.chronicle.preferences.PARTICIPATION_STATUS
import com.openlattice.chronicle.preferences.STUDY_ID
import com.openlattice.chronicle.preferences.SensorSettings
import com.openlattice.chronicle.receivers.lifecycle.SurveyNotificationsReceiver
import com.openlattice.chronicle.serialization.JsonSerializer
import com.openlattice.chronicle.services.lifecycle.DeviceStateSampler
import com.openlattice.chronicle.services.notifications.NotificationDetails
import com.openlattice.chronicle.services.notifications.NotificationListener
import com.openlattice.chronicle.services.notifications.NotificationsWorker
import com.openlattice.chronicle.services.notifications.SURVEY_NOTIFICATION_ACTION
import com.openlattice.chronicle.services.notifications.SURVEY_NOTIFICATION_TAG
import com.openlattice.chronicle.services.notifications.armReminder
import com.openlattice.chronicle.services.notifications.eraseSurveyArtifacts
import com.openlattice.chronicle.services.notifications.userIdentificationMayRun
import com.openlattice.chronicle.services.upload.LOCAL_STORAGE_RESERVE_BYTES
import com.openlattice.chronicle.services.upload.LocalOperationalIssue
import com.openlattice.chronicle.services.upload.LocalUploadDiagnosticsStore
import com.openlattice.chronicle.services.upload.LocalUploadModuleFamily
import com.openlattice.chronicle.services.withdrawal.ParticipantWithdrawalManager
import com.openlattice.chronicle.services.withdrawal.ParticipantWithdrawalWorker
import com.openlattice.chronicle.services.withdrawal.WithdrawalState
import com.openlattice.chronicle.services.withdrawal.WithdrawalStateStore
import com.openlattice.chronicle.storage.AUTH_MODE_API_KEY
import com.openlattice.chronicle.storage.BatterySampleEntry
import com.openlattice.chronicle.storage.ChronicleDb
import com.openlattice.chronicle.storage.CollectionModuleStateEntity
import com.openlattice.chronicle.storage.MIGRATION_17_18
import com.openlattice.chronicle.storage.QueueEntry
import com.openlattice.chronicle.storage.UploadServerEntity
import com.openlattice.chronicle.storage.healthMetricSampleDao
import com.openlattice.chronicle.storage.interactionSampleDao
import com.openlattice.chronicle.storage.notificationActivitySampleDao
import com.openlattice.chronicle.services.lifecycle.persistLifecycleEntries
import java.lang.reflect.Proxy
import java.time.OffsetDateTime
import java.util.concurrent.AbstractExecutorService
import java.util.concurrent.Executor
import java.util.concurrent.TimeUnit
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements
import com.openlattice.chronicle.collection.battery.BatterySampleSource
import com.openlattice.chronicle.collection.device.ConnectivityStateSource
import com.openlattice.chronicle.collection.device.DeviceSettingsSource
import com.openlattice.chronicle.collection.device.HealthMetricSource
import com.openlattice.chronicle.collection.sink.SensorSampleWriter

@RunWith(RobolectricTestRunner::class)
@Config(shadows = [ResearchErasureRegressionTest.TestCipher::class, ResearchErasureRegressionTest.TestCipherKeys::class])
class ResearchErasureRegressionTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private lateinit var db: ChronicleDb
    private lateinit var prefs: SharedPreferences
    private val coordinator get() = CollectionLoopCoordinator(context)
    private val fence get() = ResearchErasureFence(context)
    private val owner get() = db.uploadServerDao().getConfiguredServer()!!

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
        EncryptedPrefsHelper::class.java.getDeclaredField("instance").apply { isAccessible = true }
            .set(EncryptedPrefsHelper, prefs)
        context.getSharedPreferences("research_erasure_fence", Context.MODE_PRIVATE).edit().clear().commit()
        db = Room.inMemoryDatabaseBuilder(context, ChronicleDb::class.java).allowMainThreadQueries().build()
        ChronicleDb::class.java.getDeclaredField("INSTANCE").apply { isAccessible = true }.set(null, db)
        db.uploadServerDao().insert(UploadServerEntity(name = "study", url = "https://localhost",
            studyId = prefs.getString(STUDY_ID, "")!!, participantId = "participant-A", sourceDeviceId = "device"))
        MIGRATION_17_18.migrate(db.openHelper.writableDatabase)
        CollectionModuleId.values().filter { it.active }.forEach { consent(it, ParticipantDecision.ACCEPTED) }
        if (!WorkManager.isInitialized()) WorkManager.initialize(context,
            Configuration.Builder().setExecutor(Executor { }).build())
        onPersistenceWorker { ResearchPersistenceGate.initialize(context) }
        StorageAdmission.allowed(context, LOCAL_STORAGE_RESERVE_BYTES)
        StorageAdmission::class.java.getDeclaredField("lastAllowed").apply { isAccessible = true }.setBoolean(StorageAdmission, true)
        StorageAdmission::class.java.getDeclaredField("lastCheckedAt").apply { isAccessible = true }
            .setLong(StorageAdmission, SystemClock.elapsedRealtime())
    }

    @After fun tearDown() {
        ResearchPersistenceGate.resetAfterLocalStoreRecovery()
        AndroidSensorType.values().forEach { SensorSampleWriter.discardSensorSamples(it.name) }
        ChronicleDb::class.java.getDeclaredField("INSTANCE").apply { isAccessible = true }.set(null, null)
        EncryptedPrefsHelper::class.java.getDeclaredField("instance").apply { isAccessible = true }.set(EncryptedPrefsHelper, null)
        StorageAdmission::class.java.getDeclaredField("lastCheckedAt").apply { isAccessible = true }
            .setLong(StorageAdmission, Long.MIN_VALUE)
        InteractionPolicySettings.invalidateMemoryCache()
        db.close()
    }

    private fun consent(module: CollectionModuleId, decision: ParticipantDecision, enabled: Boolean = true) {
        ResearchPersistenceGate.captureObservation(context, module)
        stopOnPersistenceWorker {
            db.collectionModuleStateDao().upsertAll(listOf(CollectionModuleStateEntity(
                module.id, enabled, decision.name, 0, false, 1, null, null)))
        }
    }

    private fun decide(module: CollectionModuleId, accepted: Boolean, context: Context = this.context) = onPersistenceWorker {
        CollectionLoopCoordinator::class.java.getDeclaredMethod("applyDecisionsIf", Set::class.java,
            Set::class.java, ConsentTrigger::class.java, Boolean::class.javaPrimitiveType,
            kotlin.Function0::class.java).apply { isAccessible = true }
            .invoke(CollectionLoopCoordinator(context), if (accepted) setOf(module) else emptySet<CollectionModuleId>(),
                if (accepted) emptySet<CollectionModuleId>() else setOf(module), ConsentTrigger.values().first(), false, { true })
    }

    private fun eraseAndReaccept(module: CollectionModuleId) { decide(module, false); decide(module, true) }

    private fun event(at: Long = System.currentTimeMillis()) = ExtractedUsageEvent(
        appPackageName = "example.app", interactionType = "Activity Resumed",
        timestamp = java.time.Instant.ofEpochMilli(at).atOffset(java.time.ZoneOffset.UTC), timezone = "UTC",
        user = "participant-A", applicationLabel = "Example", activityClass = "MainActivity")

    private fun row(event: ExtractedUsageEvent = event()) = QueueEntry(1, 1, JsonSerializer.serializeQueueEntry(listOf(event)))

    @Test fun identificationAddedAfterEnrollmentUnlocksSettingsAndPromptsOnlyAfterConsent() {
        val module = CollectionModuleId.USER_IDENTIFICATION
        val preview = com.openlattice.chronicle.serialization.ChronicleJson.moshi
            .adapter(com.openlattice.chronicle.api.EnrollmentPreviewResponse::class.java)
            .fromJson(javaClass.getResource("/enrollment-preview.json")!!.readText())!!
        val initialSettings = preview.manifest.collectionSettings.copy(settingsVersion = 1,
            modules = AndroidDataCollectionSetting.androidSupportedModuleIds.associateWith {
                CollectionModuleSetting(enabled = false)
            })
        val manifest = preview.manifest.copy(studyId = java.util.UUID.fromString(owner.studyId),
            participantId = owner.participantId, serverOrigin = owner.url, collectionSettings = initialSettings,
            settingsVersion = initialSettings.settingsVersion)
        val expected = owner.copy(studyDisclosureJson = com.openlattice.chronicle.serialization.ChronicleJson.moshi
            .adapter(com.openlattice.chronicle.api.MobileEnrollmentManifest::class.java).toJson(manifest),
            disclosureVersion = manifest.participantPolicy.version, manifestDigest = preview.manifestDigest)
        db.uploadServerDao().update(expected)
        assertTrue(onPersistenceWorker {
            coordinator.applyFetchedSettings(expected, fence.settingsGeneration(), initialSettings)
        })
        prefs.edit().putBoolean(context.getString(com.openlattice.chronicle.R.string.identify_user), true).commit()
        shadowOf(context as android.app.Application).grantPermissions(android.Manifest.permission.POST_NOTIFICATIONS)
        val revised = initialSettings.copy(settingsVersion = 2,
            modules = initialSettings.modules + (module to CollectionModuleSetting(enabled = true)))

        assertTrue(onPersistenceWorker {
            coordinator.applyFetchedSettings(expected, fence.settingsGeneration(), revised)
        })
        val settings = EnrollmentSettings(context)
        assertFalse(settings.isUserIdentificationStudyAuthorized())
        assertFalse(userIdentificationMayRun(context))
        withStudyApi(expected) { method ->
            check(method == "reportCollectionAck")
            com.openlattice.chronicle.base.OK()
        }.use {
            assertTrue(onPersistenceWorker {
                coordinator.applyDecisions(setOf(module), emptySet(), ConsentTrigger.PARTICIPANT_TOGGLE)
            })
        }

        assertTrue(settings.isUserIdentificationStudyAuthorized())
        assertTrue(com.openlattice.chronicle.ui.userIdentificationControlState(
            ResearchPersistenceGate.isActiveEnrollment(context), settings.isUserIdentificationStudyAuthorized(),
            settings.isUserIdentificationEnabled()).targetChoicesEnabled)
        assertTrue(userIdentificationMayRun(context))
        assertEquals(expected.studyDisclosureJson, owner.studyDisclosureJson)
        val result = onPersistenceWorker {
            com.openlattice.chronicle.collection.identification.TargetUserRouter
                .setTargetUser(context, "accepted-user", settings)
        }
        assertTrue(result.toString(), result is ModuleResult.Ok)
        assertEquals("accepted-user", settings.getCurrentUser())
    }

    @Test fun staleSettingsResponseCannotReopenADeclinedModule() {
        val expected = owner
        val generation = fence.settingsGeneration()
        decide(CollectionModuleId.BATTERY_TELEMETRY, false)
        assertFalse(onPersistenceWorker { coordinator.applyFetchedSettings(expected, generation, AndroidDataCollectionSetting()) })
        assertEquals(ParticipantDecision.DECLINED, CollectionLoopStore.of(context).loadAll()
            .getValue(CollectionModuleId.BATTERY_TELEMETRY).decision)
    }

    @Test fun retiredEnrollmentSettingsCannotEraseTheReplacementEnrollment() {
        val expected = owner
        val generation = fence.settingsGeneration()
        stopOnPersistenceWorker {
            fence.erase(CollectionModuleId.values().toSet(), durableIntent = false)
            db.uploadServerDao().delete(expected.id)
            db.uploadServerDao().insert(expected.copy(id = 0, participantId = "participant-B", createdAt = OffsetDateTime.now().toString()))
            prefs.edit().putString(PARTICIPANT_ID, "participant-B").commit()
            db.queueEntryData().insertEntry(row())
        }
        assertFalse(onPersistenceWorker { coordinator.applyFetchedSettings(expected, generation, AndroidDataCollectionSetting()) })
        assertEquals(1, db.queueEntryData().getSize())
    }

    @Test fun enrollmentMonitorCannotApplyRetiredStatusToAReplacementWithTheSameParticipantIds() {
        val expected = owner
        val applyStatus = { status: ParticipationStatus ->
            ResearchPersistenceGate.applyParticipationStatus(context, expected, fence.settingsGeneration(), status)
        }
        WithdrawalStateStore(context).setState(WithdrawalState.PENDING)
        assertFalse(onPersistenceWorker { applyStatus(ParticipationStatus.ENROLLED) })
        stopOnPersistenceWorker {
            db.uploadServerDao().delete(expected.id)
            db.uploadServerDao().insert(expected.copy(id = 0, createdAt = OffsetDateTime.now().toString()))
            WithdrawalStateStore(context).setState(WithdrawalState.NONE)
        }
        assertFalse(onPersistenceWorker { applyStatus(ParticipationStatus.NOT_ENROLLED) })
        assertEquals(ParticipationStatus.ENROLLED, EnrollmentSettings(context).getParticipationStatus())
    }

    @Test fun pausedEnrollmentAcceptsTheServerResume() {
        val apply = { status: ParticipationStatus ->
            ResearchPersistenceGate.applyParticipationStatus(context, owner, fence.settingsGeneration(), status)
        }
        assertTrue(onPersistenceWorker { apply(ParticipationStatus.PAUSED) })
        assertFalse(ResearchPersistenceGate.isActiveEnrollment(context))
        assertTrue(ResearchPersistenceGate.isEnrolledIgnoringStatus(context))

        assertTrue(onPersistenceWorker { apply(ParticipationStatus.ENROLLED) })
        assertTrue(ResearchPersistenceGate.isActiveEnrollment(context))
    }

    @Test fun pausedAndCompletedEnrollmentDrainsQueuedRowsButCannotCollect() {
        for (status in listOf(ParticipationStatus.PAUSED, ParticipationStatus.COLLECTION_COMPLETED)) {
            db.queueEntryData().insertEntry(row())
            assertTrue(onPersistenceWorker {
                ResearchPersistenceGate.applyParticipationStatus(context, owner, fence.settingsGeneration(), status)
            })
            assertNull(ResearchPersistenceGate.runIfActive(context) { "collection" })
            assertEquals(1, ResearchPersistenceGate.runIfEnrolled(context) {
                val queued = db.queueEntryData().getNextEntries(1)
                db.queueEntryData().deleteEntries(queued)
                queued.size
            })
            assertEquals(0, db.queueEntryData().getSize())
        }
        WithdrawalStateStore(context).setState(WithdrawalState.PENDING)
        assertNull(ResearchPersistenceGate.runIfEnrolled(context) { "upload" })
    }

    @Test fun resumedUsageExcludesPausedHistoryAndKeepsAlreadyQueuedRecords() {
        val module = CollectionModuleId.USAGE_EVENTS
        val initialScope = ResearchPersistenceGate.observationScope(context, module)!!
        val checkpoint = DaoUsagePollCheckpointStore(db.usagePollCheckpointDao(), initialScope.first, initialScope.second)
        checkpoint.commitPollTimestamp(System.currentTimeMillis() - TimeUnit.MINUTES.toMillis(5))
        val retained = row()
        db.queueEntryData().insertEntry(retained)
        val captured = ResearchPersistenceGate.usageSink(context)
        assertTrue(onPersistenceWorker {
            ResearchPersistenceGate.applyParticipationStatus(context, owner, fence.settingsGeneration(), ParticipationStatus.PAUSED)
        })
        val pausedEvent = event(System.currentTimeMillis() - TimeUnit.MINUTES.toMillis(1))
        assertTrue(pausedEvent.timestamp.toInstant().toEpochMilli() >= initialScope.second)
        assertTrue(onPersistenceWorker {
            ResearchPersistenceGate.applyParticipationStatus(context, owner, fence.settingsGeneration(), ParticipationStatus.ENROLLED)
        })
        onPersistenceWorker { ResearchPersistenceGate.initialize(context) }
        val scope = ResearchPersistenceGate.observationScope(context, module)!!
        val resumedAt = scope.second
        assertTrue(resumedAt > pausedEvent.timestamp.toInstant().toEpochMilli())
        val resumedEvent = event(resumedAt + 1)
        val resumedCheckpoint = DaoUsagePollCheckpointStore(db.usagePollCheckpointDao(), scope.first, scope.second)
        val collector = UsageEventsCollectionModule(
            poller = object : UsageEventPoller {
                override fun poll(previousPollTimestamp: Long, currentPollTimestamp: Long,
                                  users: java.util.NavigableMap<Long, String>) =
                    com.openlattice.chronicle.android.ChronicleData(listOf(pausedEvent, resumedEvent).filter {
                        it.timestamp.toInstant().toEpochMilli() in previousPollTimestamp until currentPollTimestamp
                    })
            },
            checkpointStore = resumedCheckpoint,
            previousPollTimestampFallback = { error("Resume floor must survive initialization") },
        )
        val outcome = requireNotNull(collector.pollWindow(
            java.util.TreeMap(), com.openlattice.chronicle.collection.core.CollectionWindow(0, resumedAt + 2)))
        assertEquals(listOf(resumedEvent), outcome.events.toList())
        assertTrue(outcome.previousPollTimestamp >= resumedAt)
        assertFalse(UsageModulePersistence.persist(listOf(row(resumedEvent)), resumedAt + 2, captured,
            checkpoint::commitPollTimestamp, { db.runInTransaction(it) }, NoOpCollectionLog))
        assertEquals(1, db.queueEntryData().getSize())
        assertArrayEquals(retained.data, db.queueEntryData().getNextEntries(1).single().data)
    }

    @Test fun forbiddenUploadRetriesAfterParticipationResumes() = forbiddenUploadRecovers {
        assertTrue(onPersistenceWorker {
            ResearchPersistenceGate.applyParticipationStatus(context, owner, fence.settingsGeneration(), ParticipationStatus.PAUSED)
        })
        assertTrue(onPersistenceWorker {
            ResearchPersistenceGate.applyParticipationStatus(context, owner, fence.settingsGeneration(), ParticipationStatus.ENROLLED)
        })
    }

    @Test fun forbiddenUploadRetriesAfterConsentResolution() = forbiddenUploadRecovers {
        consent(CollectionModuleId.BATTERY_TELEMETRY, ParticipantDecision.UNDECIDED)
        decide(CollectionModuleId.BATTERY_TELEMETRY, true)
    }

    private fun forbiddenUploadRecovers(resolve: () -> Unit) {
        val expected = owner.copy(authMode = AUTH_MODE_API_KEY, apiKey = "test-key")
        db.uploadServerDao().update(expected)
        com.openlattice.chronicle.services.crypto.EncryptionSettingStore.of(context).put(
            java.util.UUID.fromString(expected.studyId), com.openlattice.chronicle.study.StudyEncryptionSetting(enabled = false))
        db.queueEntryData().insertEntry(row())
        var halted = true
        var calls = 0
        withStudyApi(expected) { method ->
            check(method == "uploadAndroidUsageEventData")
            calls++
            if (halted) throw com.openlattice.chronicle.serialization.ChronicleCallException("POST", "url", "", 403)
            1
        }.use {
            val uploader = com.openlattice.chronicle.services.upload.UploadWorkerDelegate(context, db)
            assertEquals(1, onPersistenceWorker { uploader.execute() })
            assertEquals(1, db.queueEntryData().getSize())
            onPersistenceWorker { uploader.execute() }
            assertEquals("unchanged refusal remains blocked", 1, calls)
            halted = false
            resolve()
            assertEquals(0, onPersistenceWorker { uploader.execute() })
            assertEquals(2, calls)
            assertEquals(0, db.queueEntryData().getSize())
        }
    }

    @Test fun acknowledgedWithdrawalClearsConsentReportsBeforeAnInterruptedDatabaseErasureAndIdentityLast() {
        val expected = owner.copy(apiKey = "test-key")
        db.uploadServerDao().update(expected)
        val reports = CollectionAckRetryQueue.of(context)
        reports.enqueue(listOf(PendingCollectionAckRecord.from(expected,
            setOf(CollectionModuleId.USAGE_EVENTS), emptySet(), trigger = ConsentTrigger.values().first(),
            acknowledgedAt = OffsetDateTime.now())))
        db.queueEntryData().insertEntry(row())
        assertTrue(onPersistenceWorker { ParticipantWithdrawalManager.begin(context) })
        WithdrawalStateStore(context).acknowledgeServer(expected.id)
        val worker = ParticipantWithdrawalWorker(context, workerParameters())
        db.openHelper.writableDatabase.execSQL(
            "CREATE TRIGGER interrupt_withdrawal BEFORE DELETE ON upload_servers BEGIN SELECT RAISE(ABORT, 'interrupted'); END")
        assertEquals(ListenableWorker.Result.retry(), onPersistenceWorker { worker.doWork() })
        assertTrue(reports.load().isEmpty())
        assertEquals(expected.participantId, prefs.getString(PARTICIPANT_ID, null))
        assertEquals(1, db.queueEntryData().getSize())
        db.openHelper.writableDatabase.execSQL("DROP TRIGGER interrupt_withdrawal")
        assertEquals(ListenableWorker.Result.success(), onPersistenceWorker { worker.doWork() })
        assertNull(db.uploadServerDao().getConfiguredServer())
        assertEquals(0, db.queueEntryData().getSize())
        assertFalse(prefs.contains(PARTICIPANT_ID))
        assertEquals(WithdrawalState.COMPLETE, WithdrawalStateStore(context).state())
    }

    private fun workerParameters(): WorkerParameters {
        val executor = Executor { }
        val progress = Proxy.newProxyInstance(ProgressUpdater::class.java.classLoader,
            arrayOf(ProgressUpdater::class.java)) { _, _, _ -> error("no progress expected") } as ProgressUpdater
        val foreground = Proxy.newProxyInstance(ForegroundUpdater::class.java.classLoader,
            arrayOf(ForegroundUpdater::class.java)) { _, _, _ -> error("no foreground expected") } as ForegroundUpdater
        val factory = object : WorkerFactory() {
            override fun createWorker(appContext: Context, workerClassName: String,
                                      workerParameters: WorkerParameters): ListenableWorker? = null
        }
        return WorkerParameters(java.util.UUID.randomUUID(), Data.EMPTY, emptyList(), WorkerParameters.RuntimeExtras(),
            0, 0, executor, kotlin.coroutines.EmptyCoroutineContext, WorkManagerTaskExecutor(executor), factory, progress, foreground)
    }

    @Test fun researcherErasureWipesQueuesAndCredentials() {
        assertErasureStatusCapability()
        val expected = owner.copy(authMode = AUTH_MODE_API_KEY, apiKey = "erased-device-key")
        db.uploadServerDao().update(expected)
        onPersistenceWorker { ResearchPersistenceGate.initialize(context) }
        db.queueEntryData().insertEntry(row())
        withStudyApi(expected) { method ->
            if (method == "getDeviceParticipationStatus") ParticipationStatus.ERASED else error(method)
        }.use {
            val monitor = com.openlattice.chronicle.services.enrollment.EnrollmentMonitoringWorker(context, workerParameters())
            assertEquals(ListenableWorker.Result.success(), onPersistenceWorker { monitor.doWork() })
        }
        assertEquals(0, db.queueEntryData().getSize())
        assertNull(db.uploadServerDao().getConfiguredServer())
        assertFalse(prefs.contains(PARTICIPANT_ID))
        assertEquals(WithdrawalState.COMPLETE, WithdrawalStateStore(context).state())
    }

    @Test fun staleResearcherErasureCannotWipeANewerEnrollmentOrSettingsGeneration() {
        val expected = owner
        val generation = fence.settingsGeneration()
        stopOnPersistenceWorker { fence.settingsChanged() }
        db.queueEntryData().insertEntry(row())
        assertFalse(onPersistenceWorker {
            ParticipantWithdrawalManager.completeResearcherErasure(context, expected, generation)
        })
        db.uploadServerDao().update(expected.copy(sourceDeviceId = "new-device"))
        assertFalse(onPersistenceWorker {
            ParticipantWithdrawalManager.completeResearcherErasure(context, expected, fence.settingsGeneration())
        })
        assertEquals(1, db.queueEntryData().getSize())
        assertEquals("new-device", owner.sourceDeviceId)
    }

    @Test fun interruptedResearcherErasureRetriesAcknowledgedCleanupWithoutAnotherServerRequest() {
        val expected = owner.copy(authMode = AUTH_MODE_API_KEY, apiKey = "erased-device-key")
        db.uploadServerDao().update(expected)
        onPersistenceWorker { ResearchPersistenceGate.initialize(context) }
        db.queueEntryData().insertEntry(row())
        db.openHelper.writableDatabase.execSQL(
            "CREATE TRIGGER interrupt_researcher_erasure BEFORE DELETE ON upload_servers BEGIN SELECT RAISE(ABORT, 'interrupted'); END")
        withStudyApi(expected) { method ->
            if (method == "getDeviceParticipationStatus") ParticipationStatus.ERASED else error(method)
        }.use {
            val monitor = com.openlattice.chronicle.services.enrollment.EnrollmentMonitoringWorker(context, workerParameters())
            assertEquals(ListenableWorker.Result.failure(), onPersistenceWorker { monitor.doWork() })
            assertEquals(WithdrawalState.PENDING, WithdrawalStateStore(context).state())
            assertTrue(WithdrawalStateStore(context).acknowledgedServerIds().contains(expected.id))
            assertFalse(ResearchPersistenceGate.isActiveEnrollment(context))
            db.openHelper.writableDatabase.execSQL("DROP TRIGGER interrupt_researcher_erasure")
            val cleanup = ParticipantWithdrawalWorker(context, workerParameters())
            assertEquals(ListenableWorker.Result.success(), onPersistenceWorker { cleanup.doWork() })
        }
        assertEquals(0, db.queueEntryData().getSize())
        assertNull(db.uploadServerDao().getConfiguredServer())
        assertFalse(prefs.contains(PARTICIPANT_ID))
    }

    @Test fun acknowledgedErasureStillCompletesAfterTheLocalServerRowWasCleared() {
        val expected = owner
        db.queueEntryData().insertEntry(row())
        val state = WithdrawalStateStore(context)
        state.beginWithdrawal()
        state.acknowledgeServer(expected.id)
        db.uploadServerDao().delete(expected.id)
        val cleanup = ParticipantWithdrawalWorker(context, workerParameters())
        assertEquals(ListenableWorker.Result.success(), onPersistenceWorker { cleanup.doWork() })
        assertEquals(WithdrawalState.COMPLETE, state.state())
        assertEquals(0, db.queueEntryData().getSize())
        assertFalse(prefs.contains(PARTICIPANT_ID))
    }

    @Test fun interruptedDiscardPersistsIntentAndRetriesBeforeOutboundAdmission() {
        db.batterySampleDao().insertAll(listOf(BatterySampleEntry("battery", "2026-09-29T00:00:00Z",
            "UTC", 80, "DISCHARGING", "NONE", 250, 4000, "GOOD")))
        db.openHelper.writableDatabase.execSQL(
            "CREATE TRIGGER fail_erasure BEFORE DELETE ON battery_samples BEGIN SELECT RAISE(ABORT, 'interrupted'); END")
        assertThrows(java.lang.reflect.InvocationTargetException::class.java) { decide(CollectionModuleId.BATTERY_TELEMETRY, false) }
        assertTrue(fence.pending().contains(CollectionModuleId.BATTERY_TELEMETRY))
        assertEquals(ParticipantDecision.DECLINED, CollectionLoopStore.of(context).loadAll()
            .getValue(CollectionModuleId.BATTERY_TELEMETRY).decision)
        db.openHelper.writableDatabase.execSQL("DROP TRIGGER fail_erasure")
        assertNull(ResearchPersistenceGate.runIfActive(context) { db.batterySampleDao().count() })
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
        while (fence.pending().isNotEmpty() && System.nanoTime() < deadline) Thread.sleep(1)
        stopOnPersistenceWorker { }
        assertEquals(0, ResearchPersistenceGate.runIfActive(context) { db.batterySampleDao().count() })
        assertTrue(ResearchErasureFence(context).pending().isEmpty())
    }

    @Test fun everyProducerFamilyRejectsCapturedAdmissionAfterDiscardAndReacceptance() {
        val families = listOf(CollectionModuleId.BATTERY_TELEMETRY, CollectionModuleId.CONNECTIVITY_STATE,
            CollectionModuleId.DEVICE_SETTINGS, CollectionModuleId.HEALTH_CONNECT, CollectionModuleId.USAGE_EVENTS,
            CollectionModuleId.SENSOR_ACCELEROMETER, CollectionModuleId.SLEEP, CollectionModuleId.ACTIVITY_RECOGNITION,
            CollectionModuleId.NOTIFICATION_ACTIVITY, CollectionModuleId.INTERACTION_EVENTS, CollectionModuleId.DEVICE_LIFECYCLE)
        families.forEach { module ->
            val origin = ResearchPersistenceGate.captureObservation(context, module)
            eraseAndReaccept(module)
            assertFalse(module.id, origin.persist { db.queueEntryData().insertEntry(row()) })
        }
        assertEquals(0, db.queueEntryData().getSize())
    }

    @Test fun batteryAcquisitionSuspendedThroughErasureCannotRecreateItsRow() {
        val source = proxy(BatterySampleSource::class.java) {
            eraseAndReaccept(CollectionModuleId.BATTERY_TELEMETRY)
            BatteryReading(72, BatteryChargingState.DISCHARGING, BatteryPlugType.UNPLUGGED, 305, 4050, BatteryHealth.GOOD)
        }
        val module = BatteryTelemetryCollectionModule(BatterySampleSink(db.batterySampleDao(), NoOpCollectionLog,
            ResearchPersistenceGate.guard(context, CollectionModuleId.BATTERY_TELEMETRY)), source, { true }, log = NoOpCollectionLog)
        assertTrue(module.sample() is ModuleResult.Skipped)
        assertEquals(0, db.batterySampleDao().count())
    }

    @Test fun otherPullFamiliesRejectErasureDuringAcquisition() {
        val connectivity = ConnectivityStateCollectionModule(ConnectivityStateSampleSink(db.connectivityStateSampleDao(),
            NoOpCollectionLog, ResearchPersistenceGate.guard(context, CollectionModuleId.CONNECTIVITY_STATE)),
            proxy(ConnectivityStateSource::class.java) {
                eraseAndReaccept(CollectionModuleId.CONNECTIVITY_STATE)
                ConnectivityStateReading(NetworkTransport.WIFI, true, false, true)
            }, { true }, log = NoOpCollectionLog)
        val reading = AndroidDeviceSettingsSource(context).read()
        val settings = DeviceSettingsCollectionModule(DeviceSettingsSampleSink(db.deviceSettingsSampleDao(), NoOpCollectionLog,
            ResearchPersistenceGate.guard(context, CollectionModuleId.DEVICE_SETTINGS)), proxy(DeviceSettingsSource::class.java) {
                eraseAndReaccept(CollectionModuleId.DEVICE_SETTINGS); reading
            }, { true }, log = NoOpCollectionLog)
        val health = HealthMetricCollectionModule(HealthMetricSampleSink(db.healthMetricSampleDao(), NoOpCollectionLog,
            ResearchPersistenceGate.guard(context, CollectionModuleId.HEALTH_CONNECT)), proxy(HealthMetricSource::class.java) {
                eraseAndReaccept(CollectionModuleId.HEALTH_CONNECT)
                listOf(HealthMetricReading(HealthMetricType.STEPS, 42.0, "count", 1, 2, null))
            }, { true }, log = NoOpCollectionLog)
        val results = listOf(connectivity.sample(), settings.sample(), health.sample())
        assertTrue(results.toString(), results.all { it is ModuleResult.Skipped })
        assertEquals(0, db.connectivityStateSampleDao().count())
        assertEquals(0, db.deviceSettingsSampleDao().count())
        assertEquals(0, db.healthMetricSampleDao().count())
    }

    @Test fun capturedProducerAdmissionsCannotEnterAReplacementEnrollmentWithoutEpochReuse() {
        val admissions = CollectionModuleId.values().filter { it.active }.associateWith {
            ResearchPersistenceGate.captureObservation(context, it)
        }
        val previous = owner
        stopOnPersistenceWorker {
            db.uploadServerDao().delete(previous.id)
            db.uploadServerDao().insert(previous.copy(id = 0, participantId = "participant-B", createdAt = OffsetDateTime.now().toString()))
            prefs.edit().putString(PARTICIPANT_ID, "participant-B").commit()
        }
        admissions.forEach { (module, admission) -> assertFalse(module.id, admission.persist { db.queueEntryData().insertEntry(row()) }) }
        assertEquals(0, db.queueEntryData().getSize())
    }

    @Test fun usageReadStartsAtTheNewAcceptanceAndRefusalCannotResurrectItsOldCheckpoint() {
        val module = CollectionModuleId.USAGE_EVENTS
        val oldScope = ResearchPersistenceGate.observationScope(context, module)!!
        val oldCheckpoint = DaoUsagePollCheckpointStore(db.usagePollCheckpointDao(), oldScope.first, oldScope.second)
        oldCheckpoint.commitPollTimestamp(10)
        val oldSink = ResearchPersistenceGate.usageSink(context)
        eraseAndReaccept(module)
        val scope = ResearchPersistenceGate.observationScope(context, module)!!
        val checkpoint = DaoUsagePollCheckpointStore(db.usagePollCheckpointDao(), scope.first, scope.second)
        assertEquals(fence.floor(module), checkpoint.readPreviousPollTimestamp())
        assertFalse(UsageModulePersistence.persist(listOf(row()), 100, oldSink, oldCheckpoint::commitPollTimestamp,
            { db.runInTransaction(it) }, NoOpCollectionLog))
        assertEquals(fence.floor(module), checkpoint.readPreviousPollTimestamp())
        assertEquals(0, db.queueEntryData().getSize())
    }

    @Test fun temporaryUsageClosureRetainsWindowAndReacceptanceKeepsItsEpochFloor() {
        val module = CollectionModuleId.USAGE_EVENTS
        stopOnPersistenceWorker { fence.erase(setOf(module), durableIntent = false, now = 500); fence.accepted(setOf(module), 1_000) }
        val scope = ResearchPersistenceGate.observationScope(context, module)!!
        val checkpoint = DaoUsagePollCheckpointStore(db.usagePollCheckpointDao(), scope.first, scope.second)
        checkpoint.commitPollTimestamp(2_000)
        val captured = ResearchPersistenceGate.usageSink(context)
        consent(module, ParticipantDecision.ACCEPTED, enabled = false)
        assertFalse(UsageModulePersistence.persist(listOf(row()), 3_000, captured, checkpoint::commitPollTimestamp,
            { db.runInTransaction(it) }, NoOpCollectionLog))
        assertEquals(2_000L, checkpoint.readPreviousPollTimestamp())
        stopOnPersistenceWorker { fence.accepted(setOf(module), 4_000) }
        consent(module, ParticipantDecision.ACCEPTED)
        assertEquals(1_000L, fence.floor(module))
        assertTrue(UsageModulePersistence.persist(listOf(row()), 3_000, captured, checkpoint::commitPollTimestamp,
            { db.runInTransaction(it) }, NoOpCollectionLog))
        assertEquals(3_000L, checkpoint.readPreviousPollTimestamp())
    }

    @Test fun finalUsageInsertionRedactsOptionalFieldsCapturedBeforeTheirErasure() {
        val captured = ResearchPersistenceGate.usageSink(context)
        val observed = row()
        eraseAndReaccept(CollectionModuleId.IN_APP_ACTIVITY_CLASS)
        eraseAndReaccept(CollectionModuleId.USER_IDENTIFICATION)
        assertEquals(ModuleResult.Ok(1), captured.write(listOf(observed)))
        val stored = JsonSerializer.deserializeQueueEntry(db.queueEntryData().getEntriesAfter(Long.MIN_VALUE, Long.MIN_VALUE, 10).single().data)
            .single() as ExtractedUsageEvent
        assertNull(stored.activityClass)
        assertEquals("", stored.user)
    }

    private fun runtime(retain: Boolean = false, gateway: SensorGateway = FakeSensorGateway(),
                        settings: SensorRuntimeSettings = FakeSensorRuntimeSettings(),
                        scheduler: SensorRuntimeScheduler = ManualSensorRuntimeScheduler(executeImmediately = false)) = SensorRuntimeController(
                            gateway, settings,
        SensorSampleSink(db.sensorSampleDao(), NoOpCollectionLog, ResearchPersistenceGate.guard(context)),
        scheduler,
        collectionGate = { ResearchPersistenceGate.collectsNow(context, SensorCollectionModules.moduleFor(it)) },
        observationAdmission = { ResearchPersistenceGate.captureObservation(context, SensorCollectionModules.moduleFor(it)) },
        sampleLease = { ResearchPersistenceGate.withReadLease(it) }, retainOnStop = retain, log = NoOpCollectionLog)

    @Test fun acceptedSensorRamSurvivesTemporaryClosureAndServiceReplacement() {
        val first = runtime(retain = true)
        first.recordSample(AndroidSensorType.accelerometer, floatArrayOf(1f, 2f, 3f), 3)
        consent(CollectionModuleId.SENSOR_ACCELEROMETER, ParticipantDecision.ACCEPTED, enabled = false)
        assertTrue(first.flushBuffer() is ModuleResult.Retry)
        first.stop(isServiceDestroy = true)
        consent(CollectionModuleId.SENSOR_ACCELEROMETER, ParticipantDecision.ACCEPTED)
        val replacement = runtime(retain = true)
        assertEquals(ModuleResult.Ok(1), replacement.flushBuffer())
        assertEquals(1, db.sensorSampleDao().count())
    }

    @Test fun erasedSensorRegistrationCannotCaptureAfterReacceptance() {
        val module = CollectionModuleId.SENSOR_ACCELEROMETER
        val registration = ResearchPersistenceGate.captureObservation(context, module)
        eraseAndReaccept(module)
        val controller = runtime()
        controller.recordSample(AndroidSensorType.accelerometer, floatArrayOf(1f), 3, origin = registration)
        assertEquals(0, controller.bufferedCount)
        assertEquals(ModuleResult.Ok(0), controller.flushBuffer())
    }

    @Test fun erasedPersistentRegistrationIsReplacedEvenWhenReacceptedBeforeReconciliation() {
        val sensor = AndroidSensorType.proximity
        val gateway = FakeSensorGateway()
        val scheduler = ManualSensorRuntimeScheduler(executeImmediately = false)
        val controller = SensorRuntimeController(gateway, FakeSensorRuntimeSettings(sensors = setOf(sensor)),
            SensorSampleSink(db.sensorSampleDao(), NoOpCollectionLog, ResearchPersistenceGate.guard(context)), scheduler,
            collectionGate = { ResearchPersistenceGate.collectsNow(context, SensorCollectionModules.moduleFor(it)) },
            observationAdmission = { ResearchPersistenceGate.captureObservation(context, SensorCollectionModules.moduleFor(it)) },
            sampleLease = { ResearchPersistenceGate.withReadLease(it) }, log = NoOpCollectionLog)
        controller.start()
        scheduler.runNextExecution()
        assertEquals(1, gateway.persistentRegistrationAttempts.size)
        eraseAndReaccept(SensorCollectionModules.moduleFor(sensor))
        controller.reconcile()
        scheduler.runNextExecution()
        assertEquals(2, gateway.persistentRegistrationAttempts.size)
        assertEquals(listOf(sensor), gateway.registeredPersistent)
        controller.stop(isServiceDestroy = true)
    }

    @Test fun sleepRegistrationAndDelayedReceiverAdmissionAreInvalidatedByErasure() {
        val module = CollectionModuleId.SLEEP
        val stamp = ResearchPersistenceGate.observationScope(context, module)!!.first
        val received = ResearchPersistenceGate.guardForRegistration(context, module, stamp)
        eraseAndReaccept(module)
        assertFalse(received.persist { db.queueEntryData().insertEntry(row()) })
        assertFalse(ResearchPersistenceGate.guardForRegistration(context, module, stamp).isCurrent())
        assertEquals(0, db.queueEntryData().getSize())
    }

    @Test fun snapshotRefreshDuringWithdrawalCannotRestoreDirectBootPermission() {
        SensorSettings(context).applyResolvedSensors(mapOf(AndroidSensorType.accelerometer to
            com.openlattice.chronicle.android.AndroidSensorSetting(sensors = setOf(AndroidSensorType.accelerometer))))
        DirectBootSnapshotWriter.refresh(context)
        assertEquals(setOf(AndroidSensorType.accelerometer), DirectBootSensorSnapshot(context).collectableSensors())
        prefs.edit().putString(PARTICIPATION_STATUS, ParticipationStatus.NOT_ENROLLED.name).commit()
        DirectBootSensorSnapshot(context).clear()
        DirectBootSnapshotWriter.refresh(context)
        assertTrue(DirectBootSensorSnapshot(context).collectableSensors().isEmpty())
    }

    @Test fun participationRetirementWaitsForAnAdmittedSnapshotWrite() {
        val writing = java.util.concurrent.CountDownLatch(1)
        val finishWrite = java.util.concurrent.CountDownLatch(1)
        val retired = java.util.concurrent.CountDownLatch(1)
        val pool = java.util.concurrent.Executors.newFixedThreadPool(2)
        try {
            val refresh = pool.submit {
                ResearchPersistenceGate.withReadLease {
                    writing.countDown()
                    check(finishWrite.await(5, TimeUnit.SECONDS))
                    DirectBootSensorSnapshot(context).write(mapOf(AndroidSensorType.accelerometer to DirectBootSensorSnapshot.SensorConfig()))
                }
            }
            assertTrue(writing.await(5, TimeUnit.SECONDS))
            val retirement = pool.submit {
                EnrollmentSettings(context).setParticipationStatus(ParticipationStatus.NOT_ENROLLED)
                retired.countDown()
            }
            assertFalse(retired.await(150, TimeUnit.MILLISECONDS))
            finishWrite.countDown()
            refresh.get(5, TimeUnit.SECONDS)
            retirement.get(5, TimeUnit.SECONDS)
            assertTrue(DirectBootSensorSnapshot(context).collectableSensors().isEmpty())
        } finally { finishWrite.countDown(); pool.shutdownNow(); assertTrue(pool.awaitTermination(5, TimeUnit.SECONDS)) }
    }

    @Suppress("DEPRECATION")
    @Test fun notificationObservationQueuedBeforeErasureCannotPersistAfterReacceptance() {
        val service = Robolectric.buildService(NotificationListener::class.java).create().get()
        val executor = QueuedExecutor()
        NotificationListener::class.java.getDeclaredField("ioExecutor").apply { isAccessible = true }.set(service, executor)
        val notification = Notification.Builder(context, "test").setSmallIcon(android.R.drawable.ic_dialog_info).build()
        val sbn = StatusBarNotification("example.app", "example.app", 1, null, 1, 1, 0, notification,
            android.os.Process.myUserHandle(), System.currentTimeMillis())
        service.onNotificationPosted(sbn)
        assertEquals(1, executor.tasks.size)
        eraseAndReaccept(CollectionModuleId.NOTIFICATION_ACTIVITY)
        executor.runAll()
        assertEquals(0, db.notificationActivitySampleDao().count())
        assertTrue(executor.tasks.isEmpty())
        val current = NotificationListener::class.java.getDeclaredField("ioExecutor").apply { isAccessible = true }.get(service)
            as java.util.concurrent.ExecutorService
        current.shutdownNow()
        ResearchErasureFence.unregisterObserver(service)
    }

    @Test fun interactionErasurePreventsDeclinedScrollAndPreviousEpisodeFromEnteringReacceptedRows() {
        val policy = InteractionPolicySettings(context)
        assertTrue(policy.save(java.util.UUID.fromString(owner.studyId), 1, true, InteractionPolicy.DEFAULT))
        val service = Robolectric.buildService(com.openlattice.chronicle.collection.interaction.InteractionCollectionService::class.java).create(
            ).get()
        val serviceType = service.javaClass
        serviceType.getDeclaredField("policySettings").apply { isAccessible = true }.set(service, policy)
        val executor = serviceType.getDeclaredField("writeExecutor").apply { isAccessible = true }.get(service)
            as com.openlattice.chronicle.collection.interaction.BoundedInteractionTaskExecutor
        fun flush() {
            val complete = java.util.concurrent.CountDownLatch(1)
            executor.execute { complete.countDown() }
            assertTrue(complete.await(5, TimeUnit.SECONDS))
        }
        try {
            service.onAccessibilityEvent(scroll(100, 10))
            flush()
            val initial = db.interactionSampleDao().getOldest(10).single()
            decide(CollectionModuleId.INTERACTION_EVENTS, false)
            service.onAccessibilityEvent(scroll(200, -10))
            flush()
            decide(CollectionModuleId.INTERACTION_EVENTS, true)
            service.onAccessibilityEvent(scroll(300, -10))
            flush()
            val accepted = db.interactionSampleDao().getOldest(10).single()
            assertNotEquals(initial.episodeId, accepted.episodeId)
            assertNull(accepted.dwellMillisSincePrev)
            assertNull(accepted.scrollReversed)
        } finally { service.onDestroy() }
    }

    @Suppress("DEPRECATION")
    private fun scroll(time: Long, direction: Int): android.view.accessibility.AccessibilityEvent {
        val node = android.view.accessibility.AccessibilityNodeInfo.obtain()
        node.setBoundsInScreen(android.graphics.Rect(0, 0, 100, 100))
        return android.view.accessibility.AccessibilityEvent.obtain(android.view.accessibility.AccessibilityEvent.TYPE_VIEW_SCROLLED).also {
            shadowOf(it).setSourceNode(node)
            it.eventTime = time
            it.scrollDeltaY = direction
            it.scrollDeltaX = 0
            it.className = "android.widget.ScrollView"
            it.packageName = "example.app"
        }
    }

    @Test fun unchangedInteractionPolicyPreservesItsPublishedGeneration() {
        val policy = InteractionPolicySettings(context)
        val study = java.util.UUID.fromString(owner.studyId)
        assertTrue(policy.save(study, 1, true, InteractionPolicy.DEFAULT))
        val observed = policy.currentSnapshot()!!
        InteractionPolicySettings(context)
        assertTrue(policy.save(study, 1, true, InteractionPolicy.DEFAULT))
        assertTrue(policy.isCurrent(observed))
    }

    @Test fun queuedAcceptedInteractionSurvivesAnIdenticalSettingsPublication() {
        queuedInteraction(erase = false, expectedRows = 1)
    }

    @Test fun erasedQueuedInteractionCannotReappearAfterReacceptance() {
        queuedInteraction(erase = true, expectedRows = 0)
    }

    private fun queuedInteraction(erase: Boolean, expectedRows: Int) {
        val policy = InteractionPolicySettings(context)
        val study = java.util.UUID.fromString(owner.studyId)
        assertTrue(policy.save(study, 1, true, InteractionPolicy.DEFAULT))
        val service = Robolectric.buildService(com.openlattice.chronicle.collection.interaction.InteractionCollectionService::class.java).create(
            ).get()
        service.javaClass.getDeclaredField("policySettings").apply { isAccessible = true }.set(service, policy)
        val executor = service.javaClass.getDeclaredField("writeExecutor").apply { isAccessible = true }.get(service)
            as com.openlattice.chronicle.collection.interaction.BoundedInteractionTaskExecutor
        val waiting = java.util.concurrent.CountDownLatch(1)
        val resume = java.util.concurrent.CountDownLatch(1)
        val flushed = java.util.concurrent.CountDownLatch(1)
        try {
            executor.execute { waiting.countDown(); check(resume.await(5, TimeUnit.SECONDS)) }
            assertTrue(waiting.await(5, TimeUnit.SECONDS))
            service.onAccessibilityEvent(scroll(100, 10))
            if (erase) eraseAndReaccept(CollectionModuleId.INTERACTION_EVENTS)
            else assertTrue(InteractionPolicySettings(context).save(study, 1, true, InteractionPolicy.DEFAULT))
            resume.countDown()
            executor.execute { flushed.countDown() }
            assertTrue(flushed.await(5, TimeUnit.SECONDS))
            assertEquals(expectedRows, db.interactionSampleDao().count())
        } finally { resume.countDown(); service.onDestroy() }
    }

    @Test fun acceptedCallbackMayFinishDuringHoldWhileNewObservationsRemainClosed() {
        val module = CollectionModuleId.NOTIFICATION_ACTIVITY
        val origin = ResearchPersistenceGate.captureObservation(context, module)
        val expected = owner
        consent(module, ParticipantDecision.ACCEPTED, enabled = false)
        assertFalse(ResearchPersistenceGate.captureObservation(context, module).isCurrent())
        assertTrue(origin.persist {
            assertTrue(ResearchPersistenceGate.persistIfCollecting(context, module, expectedOwner = expected) {
                db.queueEntryData().insertEntry(row())
            })
        })
        assertEquals(1, db.queueEntryData().getSize())
    }

    @Test fun lifecycleSamplerCannotWriteDeclinedStateAndDiscardClearsItsPreferences() {
        val events = DeviceStateSampler(context).poll()
        persistLifecycleEntries(db, listOf(
            QueueEntry(System.currentTimeMillis(), 1, JsonSerializer.serializeQueueEntry(events))))
        assertNotNull(db.lifecycleStateDao().get("battery_state"))
        decide(CollectionModuleId.DEVICE_LIFECYCLE, false)
        DeviceStateSampler(context).poll()
        assertNull(db.lifecycleStateDao().get("battery_state"))
        assertTrue(context.getSharedPreferences("chronicle_device_state", Context.MODE_PRIVATE).all.isEmpty())
        assertTrue(context.getSharedPreferences("chronicle_lifecycle_recorder", Context.MODE_PRIVATE).all.isEmpty())
    }

    @Test fun lifecycleObservationQueuedBeforeErasureCannotPersistAfterReacceptance() {
        val executor = Class.forName("com.openlattice.chronicle.services.lifecycle.DeviceLifecycleEventsKt")
            .getDeclaredField("lifecycleExecutor").apply { isAccessible = true }.get(null) as java.util.concurrent.ThreadPoolExecutor
        val waiting = java.util.concurrent.CountDownLatch(1)
        val resume = java.util.concurrent.CountDownLatch(1)
        val flushed = java.util.concurrent.CountDownLatch(1)
        try {
            executor.execute { waiting.countDown(); check(resume.await(5, TimeUnit.SECONDS)) }
            assertTrue(waiting.await(5, TimeUnit.SECONDS))
            com.openlattice.chronicle.services.lifecycle.DeviceLifecycleEventRecorder.recordObserved(context) {
                listOf(com.openlattice.chronicle.services.lifecycle.DeviceLifecycleEventRecorder.lowMemoryEvent(10))
            }
            eraseAndReaccept(CollectionModuleId.DEVICE_LIFECYCLE)
            resume.countDown()
            executor.execute { flushed.countDown() }
            assertTrue(flushed.await(5, TimeUnit.SECONDS))
            assertEquals(0, db.queueEntryData().getSize())
        } finally { resume.countDown() }
    }

    @Test fun surveyErasureCancelsAlarmPostedNotificationAndItsBrowserCapability() {
        val code = 45
        prefs.edit().putStringSet(MOBILE_REMINDER_REQUEST_CODES, setOf(code.toString())).commit()
        val alarm = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val scheduled = PendingIntent.getBroadcast(context, code,
            Intent(context, SurveyNotificationsReceiver::class.java).setAction(SURVEY_NOTIFICATION_ACTION), PendingIntent.FLAG_IMMUTABLE)
        alarm.set(AlarmManager.RTC_WAKEUP, System.currentTimeMillis() + 10_000, scheduled)
        val browser = PendingIntent.getActivity(context, code, Intent(Intent.ACTION_VIEW, android.net.Uri.parse("https://localhost/survey")),
            PendingIntent.FLAG_IMMUTABLE)
        val notifications = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        notifications.notify(SURVEY_NOTIFICATION_TAG, code, Notification.Builder(context, "survey")
            .setSmallIcon(android.R.drawable.ic_dialog_info).setContentIntent(browser).build())
        stopOnPersistenceWorker { eraseSurveyArtifacts(context) }
        assertTrue(shadowOf(alarm).scheduledAlarms.isEmpty())
        assertTrue(notifications.activeNotifications.isEmpty())
        assertThrows(PendingIntent.CanceledException::class.java) { browser.send() }
        assertThrows(PendingIntent.CanceledException::class.java) { scheduled.send() }
    }

    @Test fun withdrawalErasureClearsExpansionPullScheduleForTheNextEnrollment() {
        val schedulePrefs = context.getSharedPreferences("expansion_pull_schedule", Context.MODE_PRIVATE)
        schedulePrefs.edit().putLong("lastrun_battery_telemetry", System.currentTimeMillis()).commit()

        stopOnPersistenceWorker { eraseResearchSourceState(context) }

        assertTrue(schedulePrefs.all.isEmpty())
    }

    @Test fun acknowledgedWithdrawalClearsParticipantLinkedEpisodeBookkeeping() {
        val expected = owner
        val episodeStores = listOf("chronicle_access_missing_episodes", "capture_registration_status") +
            setOf(CollectionModuleId.SLEEP, CollectionModuleId.ACTIVITY_RECOGNITION).map { "capture_registration_episode_${it.id}" }
        episodeStores.forEach {
            context.getSharedPreferences(it, Context.MODE_PRIVATE).edit()
                .putString("${expected.studyId}:${expected.participantId}|episode", "2026-10-01T00:00:00Z").commit()
        }
        onPersistenceWorker { StorageAdmission.allowed(context, 0) }
        assertTrue(prefs.all.keys.any { it.startsWith("collection_storage_") })
        assertTrue(onPersistenceWorker { ParticipantWithdrawalManager.begin(context) })
        WithdrawalStateStore(context).acknowledgeServer(expected.id)

        assertEquals(ListenableWorker.Result.success(), onPersistenceWorker {
            ParticipantWithdrawalWorker(context, workerParameters()).doWork()
        })

        episodeStores.forEach { assertTrue(context.getSharedPreferences(it, Context.MODE_PRIVATE).all.isEmpty()) }
        assertFalse(prefs.all.keys.any { it.startsWith("collection_storage_") })
        assertFalse(prefs.all.values.any { it.toString().contains(expected.participantId) })
    }

    @Test fun initialConsentRetainsReviewedHealthHistoryAndEnrollmentInstallationIsIdempotent() {
        stopOnPersistenceWorker { assertTrue(fence.installEnrollment(owner)); fence.accepted(setOf(CollectionModuleId.HEALTH_CONNECT)) }
        val generation = fence.generation(CollectionModuleId.HEALTH_CONNECT)
        stopOnPersistenceWorker { assertFalse(fence.installEnrollment(owner)) }
        assertEquals(generation, fence.generation(CollectionModuleId.HEALTH_CONNECT))
        val source = AndroidHealthMetricSource(context)
        val reader = AndroidHealthMetricSource::class.java.getDeclaredField("readCoordinator")
            .apply { isAccessible = true }.get(source) as HealthMetricReadCoordinator
        val now = System.currentTimeMillis()
        reader.read<String>(now) { start, _ ->
            assertEquals(now - 24 * 60 * 60 * 1_000L, start)
            emptyList()
        }
        source.rejectRead()
    }

    @Test fun healthSourceErasureClearsDurableAndPendingReadStateAndUsesAcceptanceFloor() {
        val source = AndroidHealthMetricSource(context)
        val sourcePrefs = context.getSharedPreferences("chronicle_health_connect", Context.MODE_PRIVATE)
        val scope = ResearchPersistenceGate.observationScope(context, CollectionModuleId.HEALTH_CONNECT)!!
        sourcePrefs.edit().putLong("last_end_millis", 10).putString("consent_scope", scope.first).commit()
        val reader = AndroidHealthMetricSource::class.java.getDeclaredField("readCoordinator")
            .apply { isAccessible = true }.get(source) as HealthMetricReadCoordinator
        reader.read<String>(System.currentTimeMillis()) { _, _ -> listOf("old-record") }
        val pendingIds = source.javaClass.getDeclaredField("pendingSeen").apply { isAccessible = true }
        pendingIds.set(source, setOf("synthetic-pending-record"))
        eraseAndReaccept(CollectionModuleId.HEALTH_CONNECT)
        // The retired pending read is already cleared; acknowledgement is a no-op.
        source.acknowledgeRead()
        assertEquals(emptySet<String>(), pendingIds.get(source))
        assertFalse(sourcePrefs.contains("last_end_millis"))
        reader.read<String>(System.currentTimeMillis() + 1_000) { start, _ ->
            assertEquals(fence.floor(CollectionModuleId.HEALTH_CONNECT), start)
            emptyList()
        }
        source.rejectRead()
    }

    @Test fun distributionPurgeClearsAppNetworkRamAndRetryWindowAndKeepsADurableFloor() {
        val source = AndroidAppNetworkUsageSource(context)
        val checkpoint = context.getSharedPreferences("chronicle_app_network_usage", Context.MODE_PRIVATE)
        checkpoint.edit().putLong("last_end_millis", 10).putLong("pending_end_millis", 20)
            .putString("enrollment_epoch", "old-owner").commit()
        AndroidAppNetworkUsageSource::class.java.getDeclaredField("pendingEndMillis")
            .apply { isAccessible = true }.set(source, 20L)
        stopOnPersistenceWorker { com.openlattice.chronicle.services.release.purgeRestrictedPlaySourceState(context) }
        assertFalse(checkpoint.contains("last_end_millis"))
        assertFalse(checkpoint.contains("pending_end_millis"))
        assertTrue(checkpoint.getLong("reset_at_millis", 0) > 20)
        assertNull(AndroidAppNetworkUsageSource::class.java.getDeclaredField("pendingEndMillis")
            .apply { isAccessible = true }.get(source))
    }

    @Test fun moduleDiscardErasesItsSealedAndTemporaryFilesWhileRetainingSiblingStream() {
        val dir = java.nio.file.Files.createTempDirectory("sealed-erasure").toFile()
        val store = com.openlattice.chronicle.services.crypto.FileSealedEnvelopeStore(dir)
        val previous = com.openlattice.chronicle.services.crypto.PayloadSealer.sealedEnvelopeStore
        try {
            com.openlattice.chronicle.services.crypto.PayloadSealer.sealedEnvelopeStore = store
            val type = com.openlattice.chronicle.crypto.EncryptedPayloadType.SENSOR
            val entry = com.openlattice.chronicle.services.crypto.SealedEnvelopeEntry("batch",
                com.openlattice.chronicle.crypto.EncryptedEnvelope(keyId = "test-key", payloadType = type,
                    encryptedKey = "wrapped", iv = "nonce", ciphertext = "encrypted", sampleCount = 1))
            store.save("sensor-stream", entry)
            store.save("battery-stream", entry.copy(envelope = entry.envelope.copy(payloadType =
                com.openlattice.chronicle.crypto.EncryptedPayloadType.BATTERY)))
            java.io.File(dir, "interrupted.tmp").writeText(JsonSerializer.toJson(entry))
            decide(CollectionModuleId.SENSOR_ACCELEROMETER, false)
            assertNull(store.load("sensor-stream"))
            assertNotNull(store.load("battery-stream"))
            assertFalse(java.io.File(dir, "interrupted.tmp").exists())
        } finally {
            com.openlattice.chronicle.services.crypto.PayloadSealer.sealedEnvelopeStore = previous
            dir.deleteRecursively()
        }
    }

    @Test fun availabilityReportHoldsTheOutboundLeaseUntilItsRequestCompletes() {
        val expected = owner
        val atRequest = java.util.concurrent.CountDownLatch(1)
        val finishRequest = java.util.concurrent.CountDownLatch(1)
        val stopped = java.util.concurrent.CountDownLatch(1)
        val apiType = com.openlattice.chronicle.api.ChronicleStudyApi::class.java
        val api = Proxy.newProxyInstance(apiType.classLoader, arrayOf(apiType)) { _, method, _ ->
            check(method.name == "reportAndroidSensorAvailability")
            atRequest.countDown()
            check(finishRequest.await(5, TimeUnit.SECONDS))
            1
        } as com.openlattice.chronicle.api.ChronicleStudyApi
        @Suppress("UNCHECKED_CAST")
        val cache = com.openlattice.chronicle.services.upload.UploadWorker::class.java.getDeclaredField("studyApiCache")
            .apply { isAccessible = true }.get(null) as MutableMap<String, com.openlattice.chronicle.api.ChronicleStudyApi>
        val key = expected.url + "|" + com.openlattice.chronicle.utils.Utils.mobileSigningSecretFingerprint(expected.mobileSigningSecretOverride)
        val previous = cache.put(key, api)
        val executor = java.util.concurrent.Executors.newFixedThreadPool(2)
        try {
            val report = executor.submit<Boolean> {
                com.openlattice.chronicle.services.sensors.SensorAvailabilityReporter.checkAndReport(context,
                    java.util.UUID.fromString(expected.studyId), expected.participantId, expected.sourceDeviceId,
                    expected.apiKey, emptySet(), expected.url, expected.mobileSigningSecretOverride)
            }
            assertTrue(atRequest.await(5, TimeUnit.SECONDS))
            val withdrawal = executor.submit {
                stopOnPersistenceWorker {
                    prefs.edit().putString(PARTICIPATION_STATUS, ParticipationStatus.NOT_ENROLLED.name).commit()
                    fence.erase(CollectionModuleId.values().toSet(), durableIntent = false)
                    stopped.countDown()
                }
            }
            assertFalse("withdrawal must wait for outbound participant data", stopped.await(150, TimeUnit.MILLISECONDS))
            finishRequest.countDown()
            assertTrue(report.get(5, TimeUnit.SECONDS))
            withdrawal.get(5, TimeUnit.SECONDS)
            assertFalse(com.openlattice.chronicle.services.sensors.SensorAvailabilityReporter.checkAndReport(context,
                java.util.UUID.fromString(expected.studyId), expected.participantId, expected.sourceDeviceId,
                expected.apiKey, emptySet(), expected.url, expected.mobileSigningSecretOverride))
        } finally {
            finishRequest.countDown()
            executor.shutdownNow()
            assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS))
            if (previous == null) cache.remove(key) else cache[key] = previous
        }
    }

    @Test fun availabilityReportsOnlyRequestedSensorsAndSkipsDisabledModule() {
        val expected = owner
        val requested = setOf(AndroidSensorType.accelerometer)
        val reports = mutableListOf<com.openlattice.chronicle.android.AndroidDeviceSensorAvailability>()
        withStudyApi(expected, onCall = { args ->
            reports += args[4] as com.openlattice.chronicle.android.AndroidDeviceSensorAvailability
        }) { method ->
            check(method == "reportAndroidSensorAvailability")
            1
        }.use {
            fun report() = com.openlattice.chronicle.services.sensors.SensorAvailabilityReporter.checkAndReport(context,
                java.util.UUID.fromString(expected.studyId), expected.participantId, expected.sourceDeviceId,
                expected.apiKey, requested, expected.url, expected.mobileSigningSecretOverride)
            assertTrue(report())
            val sent = reports.single()
            assertEquals(requested, sent.availableSensors + sent.unavailableSensors)
            assertNull(sent.screenWidthPixels)
            assertNull(sent.screenHeightPixels)
            assertNull(sent.screenDensityDpi)
            assertNull(sent.displayRotation)
            assertNull(sent.interactionPointerCaptureCapability)
            consent(CollectionModuleId.SENSOR_AVAILABILITY, ParticipantDecision.ACCEPTED, enabled = false)
            assertTrue(report())
            assertEquals(1, reports.size)
        }
    }

    @Test fun offlineAcceptIsDeliveredBeforeNewerDeclineAndCannotReturnAfterward() {
        val module = CollectionModuleId.BATTERY_TELEMETRY
        val expected = owner.copy(disclosureVersion = "reviewed-v1", manifestDigest = "a".repeat(64))
        db.uploadServerDao().update(expected)
        onPersistenceWorker { ResearchPersistenceGate.initialize(context) }
        consent(module, ParticipantDecision.DECLINED)
        val delivered = mutableListOf<com.openlattice.chronicle.collection.CollectionAcknowledgment>()
        var online = false
        withStudyApi(expected, onCall = { args ->
            if (online) delivered += args[4] as com.openlattice.chronicle.collection.CollectionAcknowledgment
        }) { method ->
            check(method == "reportCollectionAck")
            if (!online) throw java.io.IOException("offline")
            com.openlattice.chronicle.base.OK()
        }.use {
            assertFalse(onPersistenceWorker { coordinator.applyDecisions(setOf(module), emptySet(), ConsentTrigger.PARTICIPANT_TOGGLE) })
            assertEquals(1, CollectionAckRetryQueue.of(context).load().size)
            online = true
            assertTrue(onPersistenceWorker { coordinator.applyDecisions(emptySet(), setOf(module), ConsentTrigger.PARTICIPANT_TOGGLE) })
            assertEquals(listOf(setOf(module), emptySet<CollectionModuleId>()), delivered.map { it.acknowledgedModules })
            assertEquals(setOf(module), delivered.last().declinedModules)
            assertTrue(CollectionAckRetryQueue.of(context).load().isEmpty())
            assertFalse(CollectionLoopStore.of(context).collects(module))
        }
    }

    /**
     * An ordinary server unenrollment answers NOT_ENROLLED on the status read. The
     * enrollment monitor alone, starting from ENROLLED, applies it and stops collection while keeping
     * local data; a 401 changes nothing.
     */
    @Test fun enrollmentMonitorAloneAppliesServerNotEnrolledButA401ChangesNothing() {
        assertErasureStatusCapability()
        val expected = owner.copy(authMode = AUTH_MODE_API_KEY, apiKey = "test-key")
        db.uploadServerDao().update(expected)
        onPersistenceWorker { ResearchPersistenceGate.initialize(context) }
        db.queueEntryData().insertEntry(row())

        var unauthorized = true
        withStudyApi(expected) { method ->
            if (unauthorized) throw com.openlattice.chronicle.serialization.ChronicleCallException("GET", "url", "", 401)
            if (method == "getDeviceParticipationStatus") ParticipationStatus.NOT_ENROLLED else error(method)
        }.use {
            val monitor = com.openlattice.chronicle.services.enrollment.EnrollmentMonitoringWorker(context, workerParameters())
            assertEquals(ListenableWorker.Result.failure(), onPersistenceWorker { monitor.doWork() })
            assertEquals(ParticipationStatus.ENROLLED, EnrollmentSettings(context).getParticipationStatus())
            assertTrue("a 401 must not stop collection", ResearchPersistenceGate.isActiveEnrollment(context))

            unauthorized = false
            assertEquals(ListenableWorker.Result.success(), onPersistenceWorker { monitor.doWork() })
            assertEquals(ParticipationStatus.NOT_ENROLLED, EnrollmentSettings(context).getParticipationStatus())
            assertFalse("NOT_ENROLLED stops collection", ResearchPersistenceGate.isActiveEnrollment(context))
            assertFalse(ResearchPersistenceGate.captureObservation(context, CollectionModuleId.USAGE_EVENTS)
                .persist { db.queueEntryData().insertEntry(row()) })
            assertEquals("local data is kept, never erased by a status", 1, db.queueEntryData().getSize())
            assertEquals(expected, db.uploadServerDao().getConfiguredServer())
        }
    }

    private fun assertErasureStatusCapability() {
        val headers = com.openlattice.chronicle.api.ChronicleStudyApi::class.java.methods
            .single { it.name == "getDeviceParticipationStatus" }
            .getAnnotation(retrofit2.http.Headers::class.java)
        assertTrue(headers?.value?.contains(ParticipationStatus.ERASURE_STATUS_VERSION_HEADER + ": " +
            ParticipationStatus.ERASURE_STATUS_VERSION) == true)
    }

    @Test fun serverUnenrollmentStopsPersistentSensorsAndResumeRestartsTheService() {
        val serviceClass = com.openlattice.chronicle.services.sensors.HardwareSensorService::class.java
        val serviceController = Robolectric.buildService(serviceClass).create()
        val service = serviceController.get()
        val startup = serviceClass.getDeclaredField("startupExecutor").apply { isAccessible = true }
            .get(service) as java.util.concurrent.ExecutorService
        startup.submit {}.get(5, TimeUnit.SECONDS)
        val controllerField = serviceClass.getDeclaredField("controller").apply { isAccessible = true }
        onPersistenceWorker { (controllerField.get(service) as SensorRuntimeController).stop(isServiceDestroy = true) }
        val sensor = AndroidSensorType.proximity
        val gateway = FakeSensorGateway()
        val runtime = runtime(gateway = gateway, settings = FakeSensorRuntimeSettings(sensors = setOf(sensor)),
            scheduler = ManualSensorRuntimeScheduler())
        controllerField.set(service, runtime)
        runtime.start()
        assertEquals(listOf(sensor), gateway.registeredPersistent)
        var stopped = false
        var started = false
        val observedContext = object : android.content.ContextWrapper(context) {
            override fun getApplicationContext(): Context = this
            override fun stopService(intent: Intent): Boolean {
                if (intent.component?.className == serviceClass.name) stopped = true
                return true
            }
            override fun startForegroundService(intent: Intent): android.content.ComponentName? {
                if (intent.component?.className == serviceClass.name) started = true
                return intent.component
            }
        }
        try {
            assertTrue(onPersistenceWorker {
                ResearchPersistenceGate.applyParticipationStatus(observedContext, owner, fence.settingsGeneration(), ParticipationStatus.NOT_ENROLLED)
            })
            assertTrue("authoritative stop must request service destruction", stopped)
        } finally {
            // Complete the destruction Android performs after Context.stopService.
            serviceController.destroy()
            assertTrue(startup.awaitTermination(5, TimeUnit.SECONDS))
        }
        assertFalse(runtime.isStarted)
        assertTrue(gateway.registeredPersistent.isEmpty())
        val restarted = Robolectric.buildService(serviceClass).create()
        val restartedStartup = serviceClass.getDeclaredField("startupExecutor").apply { isAccessible = true }
            .get(restarted.get()) as java.util.concurrent.ExecutorService
        try {
            restartedStartup.submit {}.get(5, TimeUnit.SECONDS)
            shadowOf(android.os.Looper.getMainLooper()).idle()
            assertTrue("boot/activity starts must stop while participation is closed", shadowOf(restarted.get()).isStoppedBySelf)
        } finally {
            restarted.destroy()
            assertTrue(restartedStartup.awaitTermination(5, TimeUnit.SECONDS))
        }
        assertTrue(onPersistenceWorker {
            ResearchPersistenceGate.applyParticipationStatus(observedContext, owner, fence.settingsGeneration(), ParticipationStatus.ENROLLED)
        })
        assertTrue("resume must restart accepted sensor collection", started)
    }

    @Config(sdk = [31])
    @Test fun deniedSensorRestartAfterResumeReportsAndNotifiesOnce() {
        val observedContext = object : android.content.ContextWrapper(context) {
            override fun getApplicationContext(): Context = this
            override fun startForegroundService(intent: Intent): android.content.ComponentName? {
                error("background foreground-service start denied")
            }
        }
        assertTrue(onPersistenceWorker {
            ResearchPersistenceGate.applyParticipationStatus(observedContext, owner, fence.settingsGeneration(), ParticipationStatus.PAUSED)
        })
        com.openlattice.chronicle.services.upload.clearAccessLossEpisodes(context)
        shadowOf(context as android.app.Application).grantPermissions(android.Manifest.permission.POST_NOTIFICATIONS)
        val notificationManager = context.getSystemService(NotificationManager::class.java)
        val notifications = shadowOf(notificationManager)
        notificationManager.cancelAll()
        val sensorDiagnostics = {
            LocalUploadDiagnosticsStore.of(context).recent().filter {
                it.moduleFamily == LocalUploadModuleFamily.SENSOR.name && it.issue == LocalOperationalIssue.COLLECTION_ACCESS_MISSING.name
            }
        }

        assertTrue(onPersistenceWorker {
            ResearchPersistenceGate.applyParticipationStatus(observedContext, owner, fence.settingsGeneration(), ParticipationStatus.ENROLLED)
        })
        // A FIFO barrier waits for the access check queued after the status mutation.
        stopOnPersistenceWorker {}
        val notice = notifications.allNotifications.single { notification ->
            notification.extras.getString(Notification.EXTRA_TEXT) == context.getString(com.openlattice.chronicle.R.string.notif_access_missing)
        }
        val intent = shadowOf(notice.contentIntent).savedIntent
        assertEquals(com.openlattice.chronicle.MainActivity::class.java.name, intent.component?.className)
        assertEquals(1, sensorDiagnostics().size)

        notificationManager.cancelAll()
        onPersistenceWorker { CollectionLoopCoordinator(observedContext).updateSensorService() }
        stopOnPersistenceWorker {}
        assertTrue("a repeated refusal must not notify again", notifications.allNotifications.isEmpty())
        assertEquals(1, sensorDiagnostics().size)
    }

    @Test fun decliningOneSensorRetiresBootSnapshotWhileSiblingRemainsActiveAndRestartIsDeferred() {
        val declined = AndroidSensorType.accelerometer
        val retained = AndroidSensorType.gyroscope
        val snapshot = DirectBootSensorSnapshot(context)
        assertTrue(snapshot.write(setOf(declined, retained).associateWith { DirectBootSensorSnapshot.SensorConfig() }))
        var deferred = false
        val observedContext = object : android.content.ContextWrapper(context) {
            override fun getApplicationContext(): Context = this
            override fun startForegroundService(intent: Intent): android.content.ComponentName? {
                deferred = true
                error("background foreground-service start denied")
            }
        }

        decide(SensorCollectionModules.moduleFor(declined), false, observedContext)
        stopOnPersistenceWorker {}

        assertTrue("the remaining sensor still requests the service", deferred)
        assertTrue(CollectionLoopStore.of(context).collects(SensorCollectionModules.moduleFor(retained)))
        assertFalse(CollectionLoopStore.of(context).collects(SensorCollectionModules.moduleFor(declined)))
        assertFalse("a reboot must not reuse the pre-decline snapshot", snapshot.isUsableFor(DirectBootSensorSnapshot.MAX_SNAPSHOT_AGE_MILLIS))
        assertTrue(DistributionRestrictedRuntime.pendingDirectBootSensorErasures(context).isEmpty())
    }

    /** W-1: the reminder read answers NOT_ENROLLED with no forms; NotificationsWorker cancels the armed reminders. */
    @Test fun serverNotEnrolledReminderReadCancelsArmedReminders() {
        val expected = owner.copy(authMode = AUTH_MODE_API_KEY, apiKey = "test-key")
        db.uploadServerDao().update(expected)
        onPersistenceWorker { ResearchPersistenceGate.initialize(context) }
        val reminder = NotificationDetails("form", com.openlattice.chronicle.constants.NotificationType.QUESTIONNAIRE,
            "FREQ=DAILY;BYHOUR=19;BYMINUTE=0;BYSECOND=0", "Check-in", "Tap")
        EnrollmentSettings(context).setMobileReminderRequestCodes(setOf(reminder.requestCode()))
        assertNotNull(armReminder(context, reminder,
            Intent(context, SurveyNotificationsReceiver::class.java).setAction(SURVEY_NOTIFICATION_ACTION)))
        val alarms = shadowOf(context.getSystemService(AlarmManager::class.java))
        assertEquals(1, alarms.scheduledAlarms.size)

        withStudyApi(expected) { method ->
            if (method == "getMobileReminderSchedule") {
                com.openlattice.chronicle.participantaccess.MobileReminderConfiguration(ParticipationStatus.NOT_ENROLLED, emptyList())
            } else {
                error(method)
            }
        }.use {
            assertEquals(ListenableWorker.Result.success(),
                onPersistenceWorker { NotificationsWorker(context, workerParameters()).doWork() })
            assertTrue("NOT_ENROLLED cancels armed reminders", alarms.scheduledAlarms.isEmpty())
            assertTrue(EnrollmentSettings(context).getMobileReminderRequestCodes().isEmpty())
        }
    }

    /** Serves [answer] for every ChronicleStudyApi call to [server] until closed. */
    private fun withStudyApi(server: UploadServerEntity, onCall: (Array<out Any?>) -> Unit = {}, answer: (String) -> Any): AutoCloseable {
        val apiType = com.openlattice.chronicle.api.ChronicleStudyApi::class.java
        val api = Proxy.newProxyInstance(apiType.classLoader, arrayOf(apiType)) { _, method, args ->
            onCall(args.orEmpty())
            answer(method.name)
        }
            as com.openlattice.chronicle.api.ChronicleStudyApi
        @Suppress("UNCHECKED_CAST")
        val cache = com.openlattice.chronicle.services.upload.UploadWorker::class.java.getDeclaredField("studyApiCache")
            .apply { isAccessible = true }.get(null) as MutableMap<String, com.openlattice.chronicle.api.ChronicleStudyApi>
        val key = server.url + "|" + com.openlattice.chronicle.utils.Utils.mobileSigningSecretFingerprint(server.mobileSigningSecretOverride)
        val previous = cache.put(key, api)
        return AutoCloseable { if (previous == null) cache.remove(key) else cache[key] = previous }
    }

    @Suppress("UNCHECKED_CAST")
    private fun <T> proxy(type: Class<T>, read: () -> Any?): T = Proxy.newProxyInstance(type.classLoader, arrayOf(type)) {
        _, method, _ -> if (method.name == "read") read() else null
    } as T

    private class QueuedExecutor : AbstractExecutorService() {
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
