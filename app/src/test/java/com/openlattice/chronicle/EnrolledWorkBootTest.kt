package com.openlattice.chronicle

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.work.WorkManager
import androidx.work.WorkInfo
import androidx.work.impl.WorkManagerImpl
import com.openlattice.chronicle.audit.AuditStores
import com.openlattice.chronicle.collection.AndroidDataCollectionSetting
import com.openlattice.chronicle.collection.CollectionModuleId
import com.openlattice.chronicle.collection.CollectionModuleSetting
import com.openlattice.chronicle.collection.state.BackgroundPersistenceRule
import com.openlattice.chronicle.collection.state.CollectionHardStop
import com.openlattice.chronicle.collection.state.CollectionLoopCoordinator
import com.openlattice.chronicle.collection.state.SettingsApplicationOutcome
import com.openlattice.chronicle.collection.state.CollectionLoopStore
import com.openlattice.chronicle.collection.state.ParticipantDecision
import com.openlattice.chronicle.collection.state.ResearchErasureFence
import com.openlattice.chronicle.collection.state.ResearchPersistenceGate
import com.openlattice.chronicle.receivers.lifecycle.StartOnBoot
import com.openlattice.chronicle.services.sync.CHRONICLE_SYNC_WORK_NAME
import com.openlattice.chronicle.services.withdrawal.WithdrawalState
import com.openlattice.chronicle.services.withdrawal.WithdrawalStateStore
import com.openlattice.chronicle.storage.ChronicleDb
import java.util.concurrent.TimeUnit
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeoutException
import org.junit.After
import org.junit.Before
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.fail
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class EnrolledWorkBootTest {
    @get:Rule val workManagerRule = WorkManagerRule()
    @get:Rule val background = BackgroundPersistenceRule()
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val work get() = WorkManagerImpl.getInstance(context)
    private val store get() = CollectionLoopStore.of(context)

    @Before fun installEnrollment() {
        AuditStores.install(context, enrolled = true)
        ResearchPersistenceGate.initialize(context)
    }

    @After fun closeEnrollment() {
        work.cancelAllWork().result.get(10, TimeUnit.SECONDS)
        ResearchPersistenceGate.resetAfterLocalStoreRecovery()
        EnrollmentTestStores.db?.close()
        EnrollmentTestStores.db = null
        EnrollmentTestStores.prefs = null
    }

    /**
     * The periodic work a pilot enrollment needs after boot, written out here rather than read from [EnrolledWork], so
     * a schedule dropped from that registry fails: usage sync and upload, battery sampling and its upload, the status
     * monitor, the settings sync and (where the build has them) the survey reminders.
     */
    companion object {
        val PILOT_PERIODIC_WORK = mapOf(
            "chronicle_sync" to 15L, "battery_telemetry" to 15L, "battery_telemetry_upload" to 15L,
            "enrollment_monitor" to 15L, "collection_settings_sync_periodic" to 60L,
        ) + if (BuildConfig.ALLOW_PARTICIPANT_FORM_REMINDERS) mapOf("notifications" to 15L) else emptyMap()
        val RETIRED_SPLIT_WORK = listOf("usage", "combined_upload")
    }

    @Test fun bootSchedulesEveryWorkNameOwnedByEnrollment() {
        val wm = WorkManager.getInstance(context)
        var finished = false
        StartOnBoot().runBootTask(context) { finished = true }
        assertTrue(finished)
        (PILOT_PERIODIC_WORK.keys + EnrolledWork.uniqueWorkNames()).forEach { name ->
            assertTrue("Boot omitted enrolled work $name",
                wm.getWorkInfosForUniqueWork(name).get(10, TimeUnit.SECONDS).any { !it.state.isFinished })
        }
    }

    @Test fun settingsLiftTheHaltEvenWhenSyncWasAlreadyRecreated() {
        declineRequired()
        EnrolledWork.rescheduleSync(context)
        assertTrue(live(CHRONICLE_SYNC_WORK_NAME).isNotEmpty())
        applySettings(CollectionModuleSetting(enabled = true, required = false))
        assertResumed()
    }

    @Test fun disablingTheDeclinedModuleRestoresEveryPeriodicSchedule() {
        declineRequired()
        applySettings(CollectionModuleSetting(enabled = false))
        assertResumed()
    }

    @Test fun retryingAnAppliedRevisionRepairsMissingWorkEvenWhenSyncIsLive() {
        declineRequired()
        applySettings(CollectionModuleSetting(enabled = true, required = false))
        val missing = PILOT_PERIODIC_WORK.keys - CHRONICLE_SYNC_WORK_NAME
        missing.forEach { work.cancelUniqueWork(it).result.get(10, TimeUnit.SECONDS) }
        assertTrue(live(CHRONICLE_SYNC_WORK_NAME).isNotEmpty())
        applySettings(CollectionModuleSetting(enabled = true, required = false))
        assertResumed()
    }

    @Test fun settingsNeverRestoreCollectionWhileARequiredDeclineRemains() {
        declineRequired()
        applySettings(CollectionModuleSetting(enabled = true, required = true))
        assertTrue(CollectionHardStop.applies(context))
        EnrolledWork.collectionWorkNames().forEach { assertTrue(it, live(it).isEmpty()) }
    }

    @Test fun settingsDoNotRefireCompletedOneShotRefreshes() {
        EnrolledWork.reconcile(context)
        val refreshes = EnrolledWork.uniqueWorkNames().associateWith { name ->
            work.getWorkInfosForUniqueWork(name).get(10, TimeUnit.SECONDS).filter { info ->
                work.workDatabase.workSpecDao().getWorkSpec(info.id.toString())?.isPeriodic == false
            }
        }.filterValues { it.isNotEmpty() }
        assertTrue(refreshes.isNotEmpty())
        refreshes.values.flatten().forEach { refresh ->
            work.workDatabase.workSpecDao().setState(WorkInfo.State.SUCCEEDED, refresh.id.toString())
        }
        applySettings(CollectionModuleSetting(enabled = true, required = false))
        refreshes.forEach { (name, completed) ->
            assertEquals(name, completed.map { it.id }, work.getWorkInfosForUniqueWork(name).get(10, TimeUnit.SECONDS).map { it.id })
            assertTrue(name, live(name).isEmpty())
        }
    }

    @Test fun reconciliationCannotPassAnInFlightHardStop() {
        val stopping = CountDownLatch(1)
        val release = CountDownLatch(1)
        val stopped = CompletableFuture.runAsync {
            ResearchPersistenceGate.stop {
                stopping.countDown()
                check(release.await(10, TimeUnit.SECONDS))
                WithdrawalStateStore(context).setState(WithdrawalState.PENDING)
            }
        }
        assertTrue(stopping.await(10, TimeUnit.SECONDS))
        val reconcilingStarted = CountDownLatch(1)
        val reconciling = CompletableFuture.runAsync {
            reconcilingStarted.countDown()
            EnrolledWork.reconcile(context)
        }
        try {
            assertTrue(reconcilingStarted.await(10, TimeUnit.SECONDS))
            try {
                reconciling.get(200, TimeUnit.MILLISECONDS)
                fail("Reconciliation must wait for the hard-stop owner")
            } catch (expected: TimeoutException) {
                assertFalse(reconciling.isDone)
            }
        } finally {
            release.countDown()
            stopped.get(10, TimeUnit.SECONDS)
            reconciling.get(10, TimeUnit.SECONDS)
        }
        EnrolledWork.collectionWorkNames().forEach { assertTrue(it, live(it).isEmpty()) }
    }

    private fun live(name: String) = work.getWorkInfosForUniqueWork(name).get(10, TimeUnit.SECONDS).filterNot { it.state.isFinished }

    private fun declineRequired() {
        EnrolledWork.reconcile(context)
        ResearchPersistenceGate.stop {
            store.save(store.loadAll().values.map { state ->
                if (state.moduleId == CollectionModuleId.USAGE_EVENTS) state.copy(decision = ParticipantDecision.DECLINED) else state
            })
        }
        assertTrue(CollectionHardStop.applies(context))
        assertTrue(live(CHRONICLE_SYNC_WORK_NAME).isEmpty())
    }

    private fun applySettings(usage: CollectionModuleSetting) {
        val server = requireNotNull(ChronicleDb.getInstance(context).uploadServerDao().getConfiguredServer())
        val fetched = AndroidDataCollectionSetting(settingsVersion = 2,
            modules = AndroidDataCollectionSetting.androidSupportedModuleIds.associateWith { CollectionModuleSetting(enabled = false) } +
                mapOf(CollectionModuleId.USAGE_EVENTS to usage,
                    CollectionModuleId.DEVICE_LIFECYCLE to CollectionModuleSetting(enabled = true),
                    CollectionModuleId.UPLOAD_TELEMETRY to CollectionModuleSetting(enabled = true)))
        assertEquals(SettingsApplicationOutcome.APPLIED,
            CollectionLoopCoordinator(context).applyFetchedSettings(server, ResearchErasureFence(context).settingsGeneration(), fetched))
    }

    private fun assertResumed() {
        assertFalse(CollectionHardStop.applies(context))
        PILOT_PERIODIC_WORK.keys.forEach { assertTrue("Missing $it", live(it).isNotEmpty()) }
        assertTrue(ResearchPersistenceGate.collectsNow(context, CollectionModuleId.DEVICE_LIFECYCLE))
        assertNotNull(ResearchPersistenceGate.runIfEnrolled(context) { "upload" })
    }
}
