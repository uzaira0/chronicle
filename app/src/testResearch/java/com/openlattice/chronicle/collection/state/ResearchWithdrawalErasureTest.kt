package com.openlattice.chronicle.collection.state

import com.openlattice.chronicle.audit.auditWorkerParameters
import androidx.work.ListenableWorker
import com.openlattice.chronicle.collection.CollectionModuleId
import com.openlattice.chronicle.collection.ConsentTrigger
import com.openlattice.chronicle.data.ParticipationStatus
import com.openlattice.chronicle.preferences.PARTICIPANT_ID
import com.openlattice.chronicle.security.InvitedServerCa
import com.openlattice.chronicle.services.enrollment.EnrollmentMonitoringWorker
import com.openlattice.chronicle.services.withdrawal.ParticipantWithdrawalManager
import com.openlattice.chronicle.services.withdrawal.ParticipantWithdrawalWorker
import com.openlattice.chronicle.services.withdrawal.WithdrawalState
import com.openlattice.chronicle.services.withdrawal.WithdrawalStateStore
import com.openlattice.chronicle.storage.BatterySampleEntry
import android.database.sqlite.SQLiteException
import java.time.OffsetDateTime
import java.util.concurrent.TimeUnit
import okhttp3.tls.HeldCertificate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class ResearchWithdrawalErasureTest : ResearchErasureTestFixture() {
    @Test fun acknowledgedWithdrawalClearsConsentReportsBeforeAnInterruptedDatabaseErasureAndIdentityLast() {
        val expected = owner.copy(apiKey = "test-key")
        db.uploadServerDao().update(expected)
        InvitedServerCa.replace(context, expected.url, HeldCertificate.Builder().certificateAuthority(1).build().certificate)
        val reports = CollectionAckRetryQueue.of(context)
        reports.enqueue(listOf(PendingCollectionAckRecord.from(expected,
            setOf(CollectionModuleId.USAGE_EVENTS), emptySet(), trigger = ConsentTrigger.values().first(),
            acknowledgedAt = OffsetDateTime.now())))
        db.queueEntryData().insertEntry(row())
        assertTrue(onPersistenceWorker { ParticipantWithdrawalManager.begin(context) })
        assertEquals("withdrawal clears what is queued at once", 0, db.queueEntryData().getSize())
        WithdrawalStateStore(context).acknowledgeServer(expected.id)
        val worker = ParticipantWithdrawalWorker(context, auditWorkerParameters())
        db.openHelper.writableDatabase.execSQL(
            "CREATE TRIGGER interrupt_withdrawal BEFORE DELETE ON upload_servers BEGIN SELECT RAISE(ABORT, 'interrupted'); END")
        assertEquals(ListenableWorker.Result.retry(), onPersistenceWorker { worker.doWork() })
        assertTrue(reports.load().isEmpty())
        assertEquals(expected.participantId, prefs.getString(PARTICIPANT_ID, null))
        db.openHelper.writableDatabase.execSQL("DROP TRIGGER interrupt_withdrawal")
        assertEquals(ListenableWorker.Result.success(), onPersistenceWorker { worker.doWork() })
        assertNull(db.uploadServerDao().getConfiguredServer())
        assertEquals(0, db.queueEntryData().getSize())
        assertFalse(prefs.contains(PARTICIPANT_ID))
        InvitedServerCa.attach(context)
        assertNull(InvitedServerCa.rootFor(expected.url))
        assertEquals(WithdrawalState.COMPLETE, WithdrawalStateStore(context).state())
    }

    @Test fun researcherErasureWipesQueuesAndCredentials() {
        val expected = owner.copy(apiKey = "erased-device-key")
        db.uploadServerDao().update(expected)
        onPersistenceWorker { ResearchPersistenceGate.initialize(context) }
        db.queueEntryData().insertEntry(row())
        withStudyApi { method ->
            if (method == "getDeviceParticipationStatus") polled(ParticipationStatus.ERASED) else error(method)
        }.use {
            val monitor = EnrollmentMonitoringWorker(context, auditWorkerParameters())
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
            ParticipantWithdrawalManager.endEnrollmentEndedByServer(context, expected, generation)
        })
        db.uploadServerDao().update(expected.copy(sourceRandomDeviceId = "new-device"))
        assertFalse(onPersistenceWorker {
            ParticipantWithdrawalManager.endEnrollmentEndedByServer(context, expected, fence.settingsGeneration())
        })
        assertEquals(1, db.queueEntryData().getSize())
        assertEquals("new-device", owner.sourceRandomDeviceId)
    }

    @Test fun interruptedResearcherErasureRetriesAcknowledgedCleanupWithoutAnotherServerRequest() {
        val expected = owner.copy(apiKey = "erased-device-key")
        db.uploadServerDao().update(expected)
        onPersistenceWorker { ResearchPersistenceGate.initialize(context) }
        db.queueEntryData().insertEntry(row())
        db.openHelper.writableDatabase.execSQL(
            "CREATE TRIGGER interrupt_researcher_erasure BEFORE DELETE ON upload_servers BEGIN SELECT RAISE(ABORT, 'interrupted'); END")
        withStudyApi { method ->
            if (method == "getDeviceParticipationStatus") polled(ParticipationStatus.ERASED) else error(method)
        }.use {
            val monitor = EnrollmentMonitoringWorker(context, auditWorkerParameters())
            assertEquals(ListenableWorker.Result.failure(), onPersistenceWorker { monitor.doWork() })
            assertEquals(WithdrawalState.PENDING, WithdrawalStateStore(context).state())
            assertTrue(WithdrawalStateStore(context).acknowledgedServerIds().contains(expected.id))
            assertFalse(ResearchPersistenceGate.isActiveEnrollment(context))
            db.openHelper.writableDatabase.execSQL("DROP TRIGGER interrupt_researcher_erasure")
            val cleanup = ParticipantWithdrawalWorker(context, auditWorkerParameters())
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
        val cleanup = ParticipantWithdrawalWorker(context, auditWorkerParameters())
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
        assertThrows(SQLiteException::class.java) { decide(CollectionModuleId.BATTERY_TELEMETRY, false) }
        assertTrue(fence.pending().contains(CollectionModuleId.BATTERY_TELEMETRY))
        assertEquals(ParticipantDecision.DECLINED, CollectionLoopStore.of(context).loadAll()
            .getValue(CollectionModuleId.BATTERY_TELEMETRY).decision)
        assertNull(ResearchPersistenceGate.runIfActive(context) { db.batterySampleDao().count() })
        db.openHelper.writableDatabase.execSQL("DROP TRIGGER fail_erasure")
        ResearchPersistenceGate.runIfActive(context) { assertEquals(0, db.batterySampleDao().count()) }
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
        while (fence.pending().isNotEmpty() && System.nanoTime() < deadline) Thread.sleep(1)
        stopOnPersistenceWorker { }
        assertEquals(0, ResearchPersistenceGate.runIfActive(context) { db.batterySampleDao().count() })
        assertTrue(ResearchErasureFence(context).pending().isEmpty())
    }
}
