package com.openlattice.chronicle.services.sync

import android.app.AppOpsManager
import android.app.Application
import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import androidx.test.core.app.ApplicationProvider
import androidx.work.ListenableWorker
import com.openlattice.chronicle.EnrollmentTestStores
import androidx.work.WorkManager
import com.openlattice.chronicle.api.MobileEnrollmentManifest
import com.openlattice.chronicle.audit.studyApi
import com.openlattice.chronicle.collection.CollectionModuleId
import com.openlattice.chronicle.collection.capability.DistributionModulePolicy
import com.openlattice.chronicle.collection.state.CollectionLoopStore
import com.openlattice.chronicle.collection.state.CollectionSettingsSyncWorker
import com.openlattice.chronicle.collection.state.ParticipantDecision
import com.openlattice.chronicle.data.MobileParticipationStatus
import com.openlattice.chronicle.data.ParticipationStatus
import com.openlattice.chronicle.preferences.PARTICIPATION_STATUS
import com.openlattice.chronicle.serialization.ChronicleJson
import com.openlattice.chronicle.storage.ChronicleDb
import java.io.IOException
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.util.concurrent.TimeUnit
import com.openlattice.chronicle.WorkManagerRule
import com.openlattice.chronicle.audit.AuditStores
import com.openlattice.chronicle.audit.auditWorkerParameters
import com.openlattice.chronicle.collection.state.ResearchPersistenceGate
import com.openlattice.chronicle.collection.state.onPersistenceWorker
import com.openlattice.chronicle.services.upload.LocalOperationalIssue
import com.openlattice.chronicle.services.upload.LocalUploadDiagnosticsStore
import com.openlattice.chronicle.services.upload.LocalUploadModuleFamily
import com.openlattice.chronicle.storage.LocalStoreRecoveryReason
import com.openlattice.chronicle.storage.LocalStoreRecoveryRequiredException
import java.util.Collections
import java.util.concurrent.CancellationException
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements
import org.robolectric.shadows.ShadowUsageStatsManager

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, shadows = [SyncCollectionFailureTest.FailingUsageProvider::class])
class SyncCollectionFailureTest {
    @get:Rule val workManagerRule = WorkManagerRule()
    private val context = ApplicationProvider.getApplicationContext<Application>()

    @Implements(UsageStatsManager::class)
    class FailingUsageProvider : ShadowUsageStatsManager() {
        companion object { var failure: RuntimeException? = null; var queried = false }
        @Implementation override fun queryEvents(start: Long, end: Long): UsageEvents {
            queried = true
            failure?.let { throw it }
            return super.queryEvents(start, end)
        }
    }

    @Before fun enroll() {
        AuditStores.install(context, enrolled = true)
        shadowOf(context.getSystemService(AppOpsManager::class.java)).setMode(AppOpsManager.OPSTR_GET_USAGE_STATS,
            context.applicationInfo.uid, context.packageName, AppOpsManager.MODE_ALLOWED)
        onPersistenceWorker { ResearchPersistenceGate.initialize(context) }
        FailingUsageProvider.queried = false
    }

    @After fun close() {
        FailingUsageProvider.failure = null
        EnrollmentTestStores.db?.close()
        EnrollmentTestStores.db = null
        EnrollmentTestStores.prefs = null
        ResearchPersistenceGate.resetAfterLocalStoreRecovery()
    }

    @Test fun storageFailureInTheActualUsagePollRetries() {
        FailingUsageProvider.failure = LocalStoreRecoveryRequiredException(LocalStoreRecoveryReason.DATABASE_OPEN_FAILED)
        assertEquals(ListenableWorker.Result.retry(), runSync())
        assertTrue("The actual Android usage collection path was not reached", FailingUsageProvider.queried)
    }

    @Test fun programmingFailureInTheActualUsagePollPropagates() {
        FailingUsageProvider.failure = IllegalArgumentException("Invalid poll")
        assertThrows(IllegalArgumentException::class.java) { runSync() }
        assertTrue(FailingUsageProvider.queried)
        // WorkManager may run the same failing poll more than once; each failure is counted.
        val recorded = onPersistenceWorker { LocalUploadDiagnosticsStore.of(context).recent() }
            .filter { it.moduleFamily == LocalUploadModuleFamily.USAGE_LIFECYCLE.name }
        assertEquals(listOf(LocalOperationalIssue.LOCAL_WRITE_FAILED.name), recorded.map { it.issue })
    }

    @Test fun cancellationInTheActualUsagePollPropagates() {
        FailingUsageProvider.failure = CancellationException("Collection cancelled")
        assertThrows(CancellationException::class.java) { runSync() }
        assertTrue(FailingUsageProvider.queried)
    }

    private val calls = Collections.synchronizedList(mutableListOf<String>())

    /** The server: an enrolled status, [answer] for the other calls (by default the network is down). */
    private fun server(answer: (String) -> Any? = { throw IOException("offline") }) = EnrollmentTestStores.installApi(
        studyApi { method, _ ->
            calls += method
            if (method == "getDeviceParticipationStatus") MobileParticipationStatus(ParticipationStatus.ENROLLED,
                OffsetDateTime.now(ZoneOffset.UTC), emptyList()) else answer(method)
        })

    private fun decide(usage: ParticipantDecision) = onPersistenceWorker {
        val dao = ChronicleDb.getInstance(context).collectionModuleStateDao()
        dao.upsert(dao.get(CollectionModuleId.USAGE_EVENTS.id)!!.copy(decision = usage.name))
        ResearchPersistenceGate.resetAfterLocalStoreRecovery()
        ResearchPersistenceGate.initialize(context)
    }

    /**
     * H-COORDINATED-SYNC: the active periodic sync never reads, queues or uploads usage the participant declined or has
     * not yet accepted. A declined required module halts everything; while usage awaits its decision, accepted device
     * lifecycle stays admitted.
     */
    @Test fun theActiveSyncNeverReadsUsageThatIsDeclinedOrAwaitingConsent() {
        for (usage in listOf(ParticipantDecision.DECLINED, ParticipantDecision.UNDECIDED)) {
            decide(usage)
            FailingUsageProvider.queried = false
            calls.clear()
            server().use { runSync() }
            assertFalse("$usage usage was read from Android", FailingUsageProvider.queried)
            assertEquals(0, onPersistenceWorker { ChronicleDb.getInstance(context).queueEntryData().getSize() })
            if (usage == ParticipantDecision.DECLINED) {
                assertTrue(calls.toString(), calls.none { it.startsWith("upload") })
            } else {
                assertTrue("accepted lifecycle still collects",
                    onPersistenceWorker { ResearchPersistenceGate.collectsNow(context, CollectionModuleId.DEVICE_LIFECYCLE) })
            }
        }
    }

    /** The study team paused this participant: the periodic sync reads and queues no usage while the pause holds. */
    @Test fun aPausedParticipantsSyncReadsNoUsage() {
        checkNotNull(EnrollmentTestStores.prefs).edit().putString(PARTICIPATION_STATUS, ParticipationStatus.PAUSED.name).commit()
        EnrollmentTestStores.installApi(studyApi { method, _ ->
            if (method == "getDeviceParticipationStatus") MobileParticipationStatus(ParticipationStatus.PAUSED,
                OffsetDateTime.now(ZoneOffset.UTC), emptyList()) else throw IOException("offline")
        }).use { runSync() }

        assertFalse("paused usage was read from Android", FailingUsageProvider.queried)
        assertEquals(0, onPersistenceWorker { ChronicleDb.getInstance(context).queueEntryData().getSize() })
    }

    /**
     * H-ACTIVE-SETTINGS-SYNC:the active periodic sync asks for the study settings; the settings run it queues fetches a
     * newer revision and applies it durably, so collection changes without the participant opening the app.
     */
    @Test fun theActiveSyncFetchesAndAppliesANewerSettingsRevision() {
        server().use { runSync() }
        val queued = WorkManager.getInstance(context).getWorkInfosForUniqueWork(CollectionSettingsSyncWorker.ONESHOT_NAME)
            .get(10, TimeUnit.SECONDS)
        assertTrue("the sync must queue a settings fetch", queued.any { !it.state.isFinished })

        val module = CollectionModuleId.BATTERY_TELEMETRY
        val owner = onPersistenceWorker { ChronicleDb.getInstance(context).uploadServerDao().getConfiguredServer()!! }
        val current = requireNotNull(ChronicleJson.moshi.adapter(MobileEnrollmentManifest::class.java)
            .fromJson(requireNotNull(owner.studyDisclosureJson))).collectionSettings
        // The revision disables battery; modules this build cannot collect stay off, as a server for it sends them.
        val newer = current.copy(settingsVersion = current.settingsVersion + 1, modules = current.effectiveModules()
            .mapValues { (id, setting) -> if (id == module || !DistributionModulePolicy.supports(id)) setting.copy(enabled = false) else setting })
        assertTrue(CollectionLoopStore.of(context).collects(module))
        calls.clear()
        server { method -> if (method == "getDataCollectionSettings") newer else throw IOException("offline") }.use {
            assertEquals(ListenableWorker.Result.success(),
                onPersistenceWorker { CollectionSettingsSyncWorker(context, auditWorkerParameters()).doWork() })
        }
        assertTrue(calls.toString(), "getDataCollectionSettings" in calls)
        val applied = onPersistenceWorker { CollectionLoopStore.of(context).loadAll().getValue(module) }
        assertEquals(newer.settingsVersion, applied.appliedVersion)
        assertFalse("the disabled module stops collecting", CollectionLoopStore.of(context).collects(module))
    }

    /**
     * The participant turned Usage Access off: the sync reads nothing and keeps the poll cursor where it was, so the
     * events Android still holds are read once access is back instead of being skipped.
     */
    @Test fun aSyncWithUsageAccessOffKeepsThePollCursor() {
        shadowOf(context.getSystemService(AppOpsManager::class.java)).setMode(AppOpsManager.OPSTR_GET_USAGE_STATS,
            context.applicationInfo.uid, context.packageName, AppOpsManager.MODE_IGNORED)
        fun cursor() = onPersistenceWorker {
            ChronicleDb.getInstance(context).openHelper.readableDatabase
                .query("SELECT sensorName, lastPollTimestamp FROM usage_poll_checkpoints").use { rows ->
                    generateSequence { if (rows.moveToNext()) rows.getString(0) to rows.getLong(1) else null }.toList()
                }
        }
        val before = cursor()
        server().use { runSync() }
        assertFalse("usage was read without Usage Access", FailingUsageProvider.queried)
        assertEquals(before, cursor())
    }

    /** The phone is offline when the study-settings fetch runs: WorkManager retries it and nothing changes locally. */
    @Test fun aSettingsFetchWhileOfflineIsRetriedWithoutChangingCollection() {
        val modules = onPersistenceWorker { CollectionLoopStore.of(context).loadAll() }
        server().use {
            assertEquals(ListenableWorker.Result.retry(),
                onPersistenceWorker { CollectionSettingsSyncWorker(context, auditWorkerParameters()).doWork() })
        }
        assertTrue(calls.toString(), "getDataCollectionSettings" in calls)
        assertEquals(modules, onPersistenceWorker { CollectionLoopStore.of(context).loadAll() })
    }

    private fun runSync(): ListenableWorker.Result = onPersistenceWorker {
        ChronicleSyncWorker(context, auditWorkerParameters()).doWork()
    }
}
