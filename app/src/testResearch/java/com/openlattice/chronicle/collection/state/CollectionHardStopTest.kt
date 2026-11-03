package com.openlattice.chronicle.collection.state

import androidx.work.WorkInfo
import androidx.work.WorkManager
import com.openlattice.chronicle.EnrolledWork
import com.openlattice.chronicle.android.AndroidSensorType
import com.openlattice.chronicle.collection.CollectionModuleId
import com.openlattice.chronicle.data.ParticipationStatus
import com.openlattice.chronicle.preferences.PARTICIPATION_STATUS
import com.openlattice.chronicle.serialization.ChronicleCallException
import com.openlattice.chronicle.services.enrollment.ENROLLMENT_MONITOR_WORK_NAME
import com.openlattice.chronicle.services.enrollment.ENROLLMENT_REFRESH_WORK_NAME
import com.openlattice.chronicle.services.enrollment.EnrollmentMonitoringWorker
import com.openlattice.chronicle.services.sync.CHRONICLE_SYNC_WORK_NAME
import com.openlattice.chronicle.services.upload.UploadWorkerDelegate
import com.openlattice.chronicle.services.withdrawal.ParticipantWithdrawalManager
import com.openlattice.chronicle.services.withdrawal.WithdrawalState
import com.openlattice.chronicle.services.withdrawal.WithdrawalStateStore
import com.openlattice.chronicle.storage.BatterySampleEntry
import com.openlattice.chronicle.storage.CollectionModuleStateEntity
import com.openlattice.chronicle.storage.UploadDiagnosticEntity
import com.openlattice.chronicle.storage.SensorSampleEntry
import com.openlattice.chronicle.storage.UserQueueEntry
import com.openlattice.chronicle.audit.auditWorkerParameters
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Withdrawal, a required decline and Mark not enrolled: every queue is cleared,
 * nothing uploads and nothing collects. The status monitor keeps running so a re-enrollment can reach the phone.
 */
class CollectionHardStopTest : ResearchErasureTestFixture() {
    private val work get() = WorkManager.getInstance(context)

    private fun sensor(at: Long = System.currentTimeMillis()) = SensorSampleEntry(UUID.randomUUID().toString(),
        AndroidSensorType.accelerometer.name, utc(at).toString(), "UTC", 1f, 2f, 3f, null, 0)

    private fun queueEverywhere() {
        db.queueEntryData().insertEntry(row())
        db.sensorSampleDao().insertAll(listOf(sensor()))
        db.userQueueEntryData().insertEntries(listOf(UserQueueEntry(System.currentTimeMillis(), "labelled-user")))
        assertTrue("the fixture queues rows in several streams", queued() >= 3)
    }

    private fun queuedByTable(): Map<String, Int> = CollectionHardStop.QUEUES.associateWith { table ->
        db.openHelper.readableDatabase.query("SELECT COUNT(*) FROM `$table`").use { it.moveToFirst(); it.getInt(0) }
    }.filterValues { it > 0 }

    private fun queued(): Int = queuedByTable().values.sum()

    private fun live(name: String) = work.getWorkInfosForUniqueWork(name).get().filterNot { it.state.isFinished }

    private fun markNotEnrolled() = assertTrue(onPersistenceWorker {
        ResearchPersistenceGate.applyParticipationStatus(context, owner, fence.settingsGeneration(),
            ParticipationStatus.NOT_ENROLLED, emptyList())
    })

    private fun declineRequired(module: CollectionModuleId = CollectionModuleId.USAGE_EVENTS) {
        stopOnPersistenceWorker {
            db.collectionModuleStateDao().upsertAll(listOf(CollectionModuleStateEntity(
                module.id, true, ParticipantDecision.ACCEPTED.name, 0, true, 1)))
        }
        decide(module, accepted = false)
        assertTrue(CollectionLoopStore.of(context).haltedByDeclinedRequired())
    }

    private fun withdraw() = assertTrue(onPersistenceWorker { ParticipantWithdrawalManager.begin(context) })

    private fun assertHardStop(trigger: () -> Unit) {
        onPersistenceWorker { EnrolledWork.reconcile(context) }
        assertTrue(live(CHRONICLE_SYNC_WORK_NAME).isNotEmpty())
        queueEverywhere()
        trigger()
        assertEquals("nothing queued survives the stop", 0, queued())
        assertNull("no upload is admitted", ResearchPersistenceGate.runIfEnrolled(context) { "upload" })
        assertFalse("nothing collects", ResearchPersistenceGate.captureObservation(context, CollectionModuleId.USAGE_EVENTS)
            .persist { db.queueEntryData().insertEntry(row()) })
        assertEquals(0, queued())
        assertEquals("collection and upload work is cancelled", emptyList<WorkInfo>(), live(CHRONICLE_SYNC_WORK_NAME))
    }

    @Test fun markNotEnrolledClearsEveryQueueAndStopsCollection() {
        assertHardStop(::markNotEnrolled)
        assertTrue("the monitor still polls for a re-enrollment", live(ENROLLMENT_MONITOR_WORK_NAME).isNotEmpty())
    }

    @Test fun aRequiredDeclineClearsEveryQueueAndStopsCollection() {
        assertHardStop { declineRequired() }
        assertTrue(live(ENROLLMENT_MONITOR_WORK_NAME).isNotEmpty())
    }

    @Test fun withdrawalClearsEveryQueueAndStopsCollection() = assertHardStop(::withdraw)

    /** A process death after the durable mark and before the clear: no upload in between, the next start clears. */
    @Test fun aMarkWithoutItsClearRefusesUploadsAndTheNextStartClears() {
        for (mark in listOf<() -> Unit>(
            { check(prefs.edit().putString(PARTICIPATION_STATUS, ParticipationStatus.NOT_ENROLLED.name).commit()) },
            { WithdrawalStateStore(context).setState(WithdrawalState.PENDING) },
        )) {
            queueEverywhere()
            mark()
            withStudyApi { method -> error("no upload after the mark: $method") }.use {
                onPersistenceWorker { UploadWorkerDelegate(context, db).execute() }
            }
            assertTrue("the rows wait for the next start", queued() > 0)
            onPersistenceWorker { ResearchPersistenceGate.initialize(context) }
            assertEquals(0, queued())
            check(prefs.edit().putString(PARTICIPATION_STATUS, ParticipationStatus.ENROLLED.name).commit())
            WithdrawalStateStore(context).setState(WithdrawalState.NONE)
            onPersistenceWorker { ResearchPersistenceGate.initialize(context) }
        }
    }

    /**
     * Mark not enrolled arrives while an upload is in flight. The stop waits for it; the server refuses it (its own
     * hard stop), the refusal asks for a status poll, and once the stop returns nothing is left to send.
     */
    @Test fun anUploadInFlightWhenMarkNotEnrolledArrivesIsTheLastOne() {
        db.queueEntryData().insertEntry(row())
        val inFlight = CountDownLatch(1)
        val refuse = CountDownLatch(1)
        var calls = 0
        val threads = Executors.newFixedThreadPool(2)
        try {
            withStudyApi { method ->
                check(method == "uploadAndroidUsageEventData") { method }
                calls++
                inFlight.countDown()
                check(refuse.await(30, TimeUnit.SECONDS))
                throw ChronicleCallException("POST", "url", "", 403)
            }.use {
                val upload = threads.submit<Int> { UploadWorkerDelegate(context, db).execute() }
                assertTrue(inFlight.await(30, TimeUnit.SECONDS))
                val expected = owner
                val generation = fence.settingsGeneration()
                val stop = threads.submit<Boolean> {
                    ResearchPersistenceGate.applyParticipationStatus(context, expected, generation,
                        ParticipationStatus.NOT_ENROLLED, emptyList())
                }
                refuse.countDown()
                upload.get(30, TimeUnit.SECONDS)
                assertTrue(stop.get(30, TimeUnit.SECONDS))
                assertEquals(emptyMap<String, Int>(), queuedByTable())
                onPersistenceWorker { UploadWorkerDelegate(context, db).execute() }
                assertEquals("nothing is sent after the stop", 1, calls)
            }
        } finally { threads.shutdownNow() }
        assertTrue("the refusal polls the status now", live(ENROLLMENT_REFRESH_WORK_NAME).isNotEmpty())
    }

    @Test fun aRefusedUploadPollsTheStatusAndTheMonitorAppliesTheStop() {
        db.queueEntryData().insertEntry(row())
        withStudyApi { method ->
            when (method) {
                "uploadAndroidUsageEventData" -> throw ChronicleCallException("POST", "url", "", 403)
                "getDeviceParticipationStatus" -> polled(ParticipationStatus.NOT_ENROLLED)
                else -> error(method)
            }
        }.use {
            onPersistenceWorker { UploadWorkerDelegate(context, db).execute() }
            assertTrue(live(ENROLLMENT_REFRESH_WORK_NAME).isNotEmpty())
            assertEquals(1, db.queueEntryData().getSize())
            onPersistenceWorker { EnrollmentMonitoringWorker(context, auditWorkerParameters()).doWork() }
        }
        assertEquals(0, queued())
    }

    @Test fun reEnrollingAfterMarkNotEnrolledResumesCollectionAndItsWork() {
        assertHardStop(::markNotEnrolled)
        assertTrue(onPersistenceWorker {
            ResearchPersistenceGate.applyParticipationStatus(context, owner, fence.settingsGeneration(),
                ParticipationStatus.ENROLLED, emptyList())
        })
        assertNotNull(ResearchPersistenceGate.runIfActive(context) { "collect" })
        assertTrue(ResearchPersistenceGate.captureObservation(context, CollectionModuleId.USAGE_EVENTS)
            .persist { db.queueEntryData().insertEntry(row()) })
        assertEquals(1, db.queueEntryData().getSize())
        assertTrue(live(CHRONICLE_SYNC_WORK_NAME).isNotEmpty())
    }

    @Test fun reAcceptingTheRequiredModuleResumesCollectionAndItsWork() {
        assertHardStop { declineRequired() }
        decide(CollectionModuleId.USAGE_EVENTS, accepted = true)
        assertFalse(CollectionHardStop.applies(context))
        assertNotNull(ResearchPersistenceGate.runIfEnrolled(context) { "upload" })
        assertTrue(live(CHRONICLE_SYNC_WORK_NAME).isNotEmpty())
    }

    /**
     * The stores a pilot enrollment fills, named here rather than read from [CollectionHardStop.QUEUES], so a store
     * dropped from that list fails these tests: usage, activity class and lifecycle rows share dataQueue; identity
     * labels; battery samples; upload telemetry; and each row's capture evidence.
     */
    private val pilotStores = listOf("dataQueue", "userQueue", "battery_samples", "upload_diagnostics", "upload_queue_evidence")

    private fun pilotRows() = pilotStores.associateWith { table ->
        db.openHelper.readableDatabase.query("SELECT COUNT(*) FROM `$table`").use { it.moveToFirst(); it.getInt(0) }
    }.filterValues { it > 0 }

    private fun seedPilot() {
        val now = System.currentTimeMillis()
        db.queueEntryData().insertEntry(row())
        db.userQueueEntryData().insertEntries(listOf(UserQueueEntry(now, "someone-else")))
        db.batterySampleDao().insertAll(listOf(BatterySampleEntry(UUID.randomUUID().toString(), utc(now).toString(), "UTC",
            50, "CHARGING", "AC", 300, 4000, "GOOD")))
        db.uploadDiagnosticDao().upsert(UploadDiagnosticEntity(UUID.randomUUID().toString(), owner.studyId,
            owner.participantId, "device", "epoch", "2026-10-30", "USAGE", "TIMEOUT", 1, utc(now).toString(),
            utc(now).toString(), null, null))
        assertEquals("every pilot store holds a sentinel", pilotStores, pilotRows().keys.toList())
    }

    private fun assertPilotClearedAndNothingReturns(trigger: () -> Unit, lift: () -> Unit) {
        onPersistenceWorker { EnrolledWork.reconcile(context) }
        seedPilot()
        trigger()
        assertEquals("no pilot store keeps a row", emptyMap<String, Int>(), pilotRows())
        onPersistenceWorker { ResearchPersistenceGate.initialize(context) }
        assertEquals("a second start clears again safely", emptyMap<String, Int>(), pilotRows())
        lift()
        assertFalse(CollectionHardStop.applies(context))
        assertEquals("lifting the stop brings nothing back", emptyMap<String, Int>(), pilotRows())
        withStudyApi { method -> error("nothing old is left to send: $method") }.use {
            onPersistenceWorker { UploadWorkerDelegate(context, db).execute() }
        }
    }

    @Test fun markNotEnrolledClearsEveryPilotStore() = assertPilotClearedAndNothingReturns(::markNotEnrolled) {
        assertTrue(onPersistenceWorker {
            ResearchPersistenceGate.applyParticipationStatus(context, owner, fence.settingsGeneration(),
                ParticipationStatus.ENROLLED, emptyList())
        })
    }

    @Test fun aRequiredDeclineClearsEveryPilotStore() =
        assertPilotClearedAndNothingReturns({ declineRequired() }) { decide(CollectionModuleId.USAGE_EVENTS, accepted = true) }

    @Test fun withdrawalClearsEveryPilotStore() {
        onPersistenceWorker { EnrolledWork.reconcile(context) }
        seedPilot()
        withdraw()
        assertEquals(emptyMap<String, Int>(), pilotRows())
        onPersistenceWorker { ResearchPersistenceGate.initialize(context) }
        assertEquals(emptyMap<String, Int>(), pilotRows())
    }

    /**
     * The clear fails partway (disk error on the third store): the transaction keeps every row, no upload is admitted
     * on the durable mark alone, and the next start finishes the clear.
     */
    @Test fun aClearThatFailsPartwayKeepsTheRowsRefusesUploadsAndTheNextStartFinishes() {
        seedPilot()
        val before = pilotRows()
        db.openHelper.writableDatabase.execSQL(
            "CREATE TRIGGER fail_clear BEFORE DELETE ON battery_samples BEGIN SELECT RAISE(ABORT, 'disk I/O error'); END")
        check(prefs.edit().putString(PARTICIPATION_STATUS, ParticipationStatus.NOT_ENROLLED.name).commit())
        runCatching { onPersistenceWorker { ResearchPersistenceGate.initialize(context) } }
        assertEquals("a failed clear is all or nothing", before, pilotRows())
        assertNull("the durable mark alone refuses uploads", ResearchPersistenceGate.runIfEnrolled(context) { "upload" })
        withStudyApi { method -> error("no upload while the clear is unfinished: $method") }.use {
            onPersistenceWorker { UploadWorkerDelegate(context, db).execute() }
        }
        db.openHelper.writableDatabase.execSQL("DROP TRIGGER fail_clear")
        onPersistenceWorker { ResearchPersistenceGate.initialize(context) }
        assertEquals(emptyMap<String, Int>(), pilotRows())
    }
}
