package com.openlattice.chronicle.services.sync

import android.app.AppOpsManager
import android.app.Application
import androidx.test.core.app.ApplicationProvider
import androidx.work.ListenableWorker
import com.openlattice.chronicle.EnrollmentTestStores
import com.openlattice.chronicle.WorkManagerRule
import com.openlattice.chronicle.api.MobileEnrollmentManifest
import com.openlattice.chronicle.audit.AuditStores
import com.openlattice.chronicle.audit.auditWorkerParameters
import com.openlattice.chronicle.audit.studyApi
import com.openlattice.chronicle.collection.AndroidDataCollectionSetting
import com.openlattice.chronicle.collection.CollectionCadence
import com.openlattice.chronicle.collection.CollectionModuleId
import com.openlattice.chronicle.collection.CollectionModuleSetting
import com.openlattice.chronicle.collection.ConsentTrigger
import com.openlattice.chronicle.collection.capability.DistributionModulePolicy
import com.openlattice.chronicle.collection.state.CollectionAckRetryQueue
import com.openlattice.chronicle.collection.state.CollectionLoopStore
import com.openlattice.chronicle.collection.state.CollectionSettingsSyncWorker
import com.openlattice.chronicle.collection.state.PendingCollectionAckRecord
import com.openlattice.chronicle.collection.state.ResearchPersistenceGate
import com.openlattice.chronicle.collection.state.onPersistenceWorker
import com.openlattice.chronicle.data.ParticipationStatus
import com.openlattice.chronicle.generated.CollectionConsentContracts
import com.openlattice.chronicle.preferences.PARTICIPATION_STATUS
import com.openlattice.chronicle.serialization.ChronicleJson
import com.openlattice.chronicle.services.upload.LocalUploadDiagnosticsStore
import com.openlattice.chronicle.services.upload.LocalUploadModuleFamily
import com.openlattice.chronicle.storage.ChronicleDb
import com.openlattice.chronicle.storage.PendingCollectionAckEntity
import java.io.IOException
import java.time.OffsetDateTime
import java.util.Collections
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import retrofit2.HttpException
import retrofit2.Response

/** The study-settings run on an enrolled phone when the server's answer, the phone's state or local storage is unusual. */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, shadows = [SyncCollectionFailureTest.FailingUsageProvider::class])
class CollectionSettingsRevisionEdgeTest {
    @get:Rule val workManagerRule = WorkManagerRule()
    private val context = ApplicationProvider.getApplicationContext<Application>()
    private val calls = Collections.synchronizedList(mutableListOf<String>())

    @Before fun enroll() {
        AuditStores.install(context, enrolled = true)
        shadowOf(context.getSystemService(AppOpsManager::class.java)).setMode(AppOpsManager.OPSTR_GET_USAGE_STATS,
            context.applicationInfo.uid, context.packageName, AppOpsManager.MODE_ALLOWED)
        onPersistenceWorker { ResearchPersistenceGate.initialize(context) }
    }

    @After fun close() {
        SyncCollectionFailureTest.FailingUsageProvider.failure = null
        EnrollmentTestStores.db?.close()
        EnrollmentTestStores.db = null
        EnrollmentTestStores.prefs = null
        ResearchPersistenceGate.resetAfterLocalStoreRecovery()
    }

    private fun server(answer: (String) -> Any?) = EnrollmentTestStores.installApi(studyApi { method, _ ->
        calls += method
        answer(method)
    })

    private fun settingsRun(): ListenableWorker.Result =
        onPersistenceWorker { CollectionSettingsSyncWorker(context, auditWorkerParameters()).doWork() }

    private fun owner() = onPersistenceWorker { ChronicleDb.getInstance(context).uploadServerDao().getConfiguredServer()!! }

    private fun currentSettings(): AndroidDataCollectionSetting = requireNotNull(ChronicleJson.moshi
        .adapter(MobileEnrollmentManifest::class.java).fromJson(requireNotNull(owner().studyDisclosureJson))).collectionSettings

    private fun supportedCurrentSettings(): AndroidDataCollectionSetting = currentSettings().let { current ->
        current.copy(modules = current.effectiveModules().mapValues { (id, setting) ->
            if (DistributionModulePolicy.supports(id)) setting else setting.copy(enabled = false)
        })
    }

    /** The next revision; [change] edits each module this build collects, the others stay off as a server sends them. */
    private fun nextRevision(change: (CollectionModuleId, CollectionModuleSetting) -> CollectionModuleSetting): AndroidDataCollectionSetting {
        val current = supportedCurrentSettings()
        return current.copy(settingsVersion = current.settingsVersion + 1, modules = current.effectiveModules().mapValues { (id, setting) ->
            if (DistributionModulePolicy.supports(id)) change(id, setting) else setting.copy(enabled = false)
        })
    }

    private fun appliedVersion(module: CollectionModuleId) =
        onPersistenceWorker { CollectionLoopStore.of(context).loadAll().getValue(module).appliedVersion }

    /** The study team paused the participant: the settings run asks the server nothing and succeeds. */
    @Test fun aPausedParticipantsSettingsRunAsksTheServerNothing() {
        checkNotNull(EnrollmentTestStores.prefs).edit().putString(PARTICIPATION_STATUS, ParticipationStatus.PAUSED.name).commit()
        server { error("no request expected") }.use { assertEquals(ListenableWorker.Result.success(), settingsRun()) }
        assertEquals(emptyList<String>(), calls.toList())
    }

    /** Permanent policy failures are reported without retrying or applying any part of the revision. */
    @Test fun aRevisionThisBuildCannotHonourIsNotApplied() {
        val module = CollectionModuleId.BATTERY_TELEMETRY
        val before = appliedVersion(module)
        val jittered = nextRevision { id, setting ->
            if (id == module) setting.copy(collectionCadence = CollectionCadence(intervalSeconds = 900, jitterSeconds = 30)) else setting
        }
        val unsupported = CollectionModuleId.entries.firstOrNull { !DistributionModulePolicy.supports(it) }
        val revisions = listOf(jittered to "IllegalArgumentException") + listOfNotNull(unsupported?.let { id ->
            val settings = nextRevision { _, setting -> setting }
            settings.copy(modules = settings.modules + (id to CollectionModuleSetting(enabled = true))) to
                "UnsupportedStudyModulesException"
        })
        for ((revision, errorType) in revisions) {
            server { method -> if (method == "getDataCollectionSettings") revision else throw IOException("offline") }.use {
                assertEquals(ListenableWorker.Result.success(), settingsRun())
            }
            val recorded = onPersistenceWorker { LocalUploadDiagnosticsStore.of(context).pending() }
            assertTrue(recorded.toString(), recorded.any {
                it.moduleFamily == LocalUploadModuleFamily.APP_RUNTIME.name && it.errorType == errorType
            })
        }
        assertEquals(before, appliedVersion(module))
        assertTrue(CollectionLoopStore.of(context).collects(module))
    }

    @Test fun anUnappliableRevisionCannotPartiallyDisableAnotherModule() {
        val module = CollectionModuleId.BATTERY_TELEMETRY
        val before = appliedVersion(module)
        val revision = nextRevision { id, setting ->
            when (id) {
                module -> setting.copy(enabled = false)
                CollectionModuleId.UPLOAD_TELEMETRY -> setting.copy(
                    collectionCadence = CollectionCadence(intervalSeconds = 900, jitterSeconds = 30),
                )
                else -> setting
            }
        }
        server { method -> if (method == "getDataCollectionSettings") revision else throw IOException("offline") }.use {
            assertEquals(ListenableWorker.Result.success(), settingsRun())
        }
        assertEquals(before, appliedVersion(module))
        assertTrue(CollectionLoopStore.of(context).collects(module))
        assertTrue(onPersistenceWorker { LocalUploadDiagnosticsStore.of(context).pending() }.isNotEmpty())
    }

    @Test fun anUnappliableRevisionRetriesWhenItsDiagnosticCannotBeStored() {
        val module = CollectionModuleId.BATTERY_TELEMETRY
        val before = appliedVersion(module)
        val revision = nextRevision { id, setting ->
            if (id == module) setting.copy(collectionCadence = CollectionCadence(intervalSeconds = 900, jitterSeconds = 30)) else setting
        }
        onPersistenceWorker {
            ChronicleDb.getInstance(context).openHelper.writableDatabase.execSQL("DROP TABLE upload_diagnostics")
        }
        server { method -> if (method == "getDataCollectionSettings") revision else throw IOException("offline") }.use {
            assertEquals(ListenableWorker.Result.retry(), settingsRun())
        }
        assertEquals(before, appliedVersion(module))
    }

    /** Reading unread usage fails while a revision turns battery off: the revision still applies. */
    @Test fun aRevisionAppliesWhenTheUsageReadBeforeItFails() {
        val module = CollectionModuleId.BATTERY_TELEMETRY
        val disabled = nextRevision { id, setting -> if (id == module) setting.copy(enabled = false) else setting }
        SyncCollectionFailureTest.FailingUsageProvider.failure = IllegalArgumentException("usage provider broke")
        SyncCollectionFailureTest.FailingUsageProvider.queried = false
        server { method -> if (method == "getDataCollectionSettings") disabled else throw IOException("offline") }.use {
            assertEquals(ListenableWorker.Result.success(), settingsRun())
        }
        assertTrue("the usage read was attempted", SyncCollectionFailureTest.FailingUsageProvider.queried)
        assertEquals(disabled.settingsVersion, appliedVersion(module))
        assertFalse(CollectionLoopStore.of(context).collects(module))
    }

    /** The server refuses a queued decision for good: it is dropped so later decisions are not held, and the study sees it. */
    @Test fun aDecisionTheServerRefusesForGoodIsDroppedAndRecorded() {
        val record = PendingCollectionAckRecord.from(owner(), setOf(CollectionModuleId.BATTERY_TELEMETRY), emptySet(),
            trigger = ConsentTrigger.PARTICIPANT_TOGGLE, acknowledgedAt = OffsetDateTime.now())
        onPersistenceWorker { CollectionAckRetryQueue.of(context).enqueue(listOf(record)) }
        val refusal = """{"code":"${CollectionConsentContracts.COLLECTION_ACK_REJECTED_CODE}","message":"refused"}"""
        val current = supportedCurrentSettings()
        server { method ->
            when (method) {
                "reportCollectionAck" -> throw HttpException(Response.error<Any>(400, refusal.toResponseBody()))
                "getDataCollectionSettings" -> current
                else -> throw IOException("offline")
            }
        }.use { assertEquals(ListenableWorker.Result.success(), settingsRun()) }
        assertTrue(calls.toString(), "reportCollectionAck" in calls)
        assertEquals(emptyList<PendingCollectionAckRecord>(), onPersistenceWorker { CollectionAckRetryQueue.of(context).load() })
        val recorded = onPersistenceWorker { LocalUploadDiagnosticsStore.of(context).pending() }
        assertTrue(recorded.toString(), recorded.any {
            it.moduleFamily == LocalUploadModuleFamily.APP_RUNTIME.name && it.httpStatus == 400
        })
        assertFalse(recorded.toString(), recorded.any { it.errorType == "UnsupportedStudyModulesException" })
    }

    /** The decision store cannot be read: the run retries and the unreadable record stays for recovery. */
    @Test fun anUnreadableDecisionStoreRetriesAndKeepsTheRecord() {
        onPersistenceWorker {
            ChronicleDb.getInstance(context).pendingCollectionAckDao()
                .insertAll(listOf(PendingCollectionAckEntity(stableKey = "corrupt", record = "{")))
        }
        val current = supportedCurrentSettings()
        server { method -> if (method == "getDataCollectionSettings") current else throw IOException("offline") }.use {
            assertEquals(ListenableWorker.Result.retry(), settingsRun())
        }
        assertEquals(listOf("{"), onPersistenceWorker { ChronicleDb.getInstance(context).pendingCollectionAckDao().getAll().map { it.record } })
        assertFalse(onPersistenceWorker { LocalUploadDiagnosticsStore.of(context).pending() }
            .any { it.errorType == "UnsupportedStudyModulesException" })
    }
}
