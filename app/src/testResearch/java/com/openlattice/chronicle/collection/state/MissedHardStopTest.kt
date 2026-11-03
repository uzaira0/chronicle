package com.openlattice.chronicle.collection.state

import androidx.work.ListenableWorker
import android.os.SystemClock
import com.openlattice.chronicle.audit.auditWorkerParameters
import com.openlattice.chronicle.collection.CollectionModuleId
import com.openlattice.chronicle.collection.lifecycle.LifecycleEventMapper
import com.openlattice.chronicle.constants.INTERACTION_BATTERY_CHARGING
import com.openlattice.chronicle.data.CaptureWindow
import com.openlattice.chronicle.data.HardStop
import com.openlattice.chronicle.data.MobileParticipationStatus
import com.openlattice.chronicle.data.ParticipationStatus
import com.openlattice.chronicle.models.ExtractedUsageEvent
import com.openlattice.chronicle.serialization.JsonSerializer
import com.openlattice.chronicle.preferences.EnrollmentSettings
import com.openlattice.chronicle.services.enrollment.EnrollmentMonitoringWorker
import com.openlattice.chronicle.services.enrollment.StatusPoll
import com.openlattice.chronicle.services.enrollment.pollParticipationStatus
import com.openlattice.chronicle.storage.BatterySampleEntry
import com.openlattice.chronicle.storage.UploadDiagnosticEntity
import com.openlattice.chronicle.storage.UserQueueEntry
import com.openlattice.chronicle.time.ServerClock
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The researcher marks the participant not enrolled at T1 and enrolls them again at T2 while the phone
 * is offline. The phone's next poll shows ENROLLED and the closed window [T1, T2); the published hard stop is what makes
 * it clear its pre-stop backlog while keeping what it captured after T2.
 */
class MissedHardStopTest : ResearchErasureTestFixture() {
    private val now = System.currentTimeMillis()
    private val t0 = now - 3 * HOUR
    private val t1 = now - 2 * HOUR
    private val t2 = now - HOUR
    private val after = now - HOUR / 2
    private val window = CaptureWindow(utc(t1), utc(t2))
    private val stop = HardStop(1, utc(t1))

    private fun lifecycle(at: Long) = LifecycleEventMapper.buildEvent("android.intent.action.ACTION_POWER_CONNECTED",
        INTERACTION_BATTERY_CHARGING, at)

    private fun battery(at: Long) = BatterySampleEntry(UUID.randomUUID().toString(), utc(at).toString(), "UTC",
        50, "CHARGING", "AC", 300, 4000, "GOOD")

    private fun diagnostic() = UploadDiagnosticEntity(UUID.randomUUID().toString(), owner.studyId, owner.participantId,
        "device", "epoch", "2026-10-30", "USAGE", "TIMEOUT", 1, utc(now).toString(), utc(now).toString(), null, null)

    private fun queueCapturedAt(at: Long) {
        db.queueEntryData().insertEntry(row(event(at)))
        db.queueEntryData().insertEntry(row(lifecycle(at)))
        db.batterySampleDao().insertAll(listOf(battery(at)))
    }

    private fun queueUntimed(at: Long = t0) {
        db.uploadDiagnosticDao().upsert(diagnostic())
        db.userQueueEntryData().insertEntries(listOf(UserQueueEntry(at, "someone-else")))
    }

    private fun count(table: String) =
        db.openHelper.readableDatabase.query("SELECT COUNT(*) FROM `$table`").use { it.moveToFirst(); it.getInt(0) }

    private fun queuedCaptureTimes(): List<Long> = db.queueEntryData().getNextEntries(100).flatMap { row ->
        JsonSerializer.deserializeQueueEntry(row.data).map { (it as ExtractedUsageEvent).timestamp.toInstant().toEpochMilli() }
    }.sorted()

    private fun batteryTimes(): List<Long> = db.openHelper.readableDatabase.query("SELECT timestamp FROM battery_samples").use {
        buildList { while (it.moveToNext()) add(utcMillis(it.getString(0))) }
    }

    private fun utcMillis(stamp: String) = java.time.OffsetDateTime.parse(stamp).toInstant().toEpochMilli()

    private fun apply(status: ParticipationStatus = ParticipationStatus.ENROLLED, windows: List<CaptureWindow> = listOf(window),
                      hardStop: HardStop? = stop) = assertTrue(onPersistenceWorker {
        ResearchPersistenceGate.applyParticipationStatus(context, owner, fence.settingsGeneration(), status, windows, hardStop)
    })

    /** The phone polled while enrolled before it went offline: it has seen no hard stop. */
    private fun polledBeforeTheStop() = apply(windows = emptyList(), hardStop = null)

    @Test fun aStopBeforeTheFirstStatusPollClearsUntimedQueues() {
        queueUntimed(after)
        apply()
        assertEquals(0, count("upload_diagnostics"))
    }

    @Test fun labelsAfterReenrollmentSurviveAMissedStopWithClockSkew() {
        val skew = 3 * HOUR
        val elapsed = SystemClock.elapsedRealtime()
        ServerClock.of(context).measure(utc(now - skew).toInstant(), elapsed, elapsed)
        polledBeforeTheStop()
        db.userQueueEntryData().insertEntries(listOf(UserQueueEntry(t0, "before"), UserQueueEntry(after, "after")))
        apply(windows = listOf(CaptureWindow(utc(t1 - skew), utc(t2 - skew))), hardStop = HardStop(1, utc(t1 - skew)))
        assertEquals(listOf("after"), db.userQueueEntryData().getUserTimestamps().map { it.user })
    }

    @Test fun anInterruptedClearIsRetriedBeforeTheGenerationIsConsumed() {
        polledBeforeTheStop()
        queueUntimed()
        stopOnPersistenceWorker {
            assertThrows(java.io.IOException::class.java) {
                fence.recordCaptureWindows(listOf(window), stop) {
                    CollectionHardStop.clearQueues(db, CollectionHardStop.UNTIMED_QUEUES)
                    throw java.io.IOException("process stopped after clear, before generation commit")
                }
            }
        }
        db.uploadDiagnosticDao().upsert(diagnostic())
        apply()
        assertEquals(0, count("upload_diagnostics"))
    }

    /**
     * A server restored without its continuity checkpoint publishes a lower generation. The phone still obeys its
     * status, keeps the stop it consumed, and a stop on the restored server clears again.
     */
    @Test fun aRestoreThatRewindsTheGenerationStillAppliesAndItsNextStopClears() {
        polledBeforeTheStop()
        apply()
        apply(ParticipationStatus.PAUSED, emptyList(), hardStop = null)
        assertEquals(ParticipationStatus.PAUSED, EnrollmentSettings(context).getParticipationStatus())
        assertTrue("the consumed stop never moves earlier", fence.captureWindows().any { t0 in it })

        queueUntimed(after)
        val restoredStop = now - HOUR / 4
        val resumed = now - HOUR / 6
        apply(windows = listOf(CaptureWindow(utc(restoredStop), utc(resumed))), hardStop = HardStop(1, utc(restoredStop)))
        assertEquals(0, count("upload_diagnostics"))
    }

    /**
     * The phone saw generation 2; the server is restored to 0 without its checkpoint, then stopped and resumed before
     * the phone polls. Generation 1 is below what the phone saw, but its stop began after the one the phone kept.
     */
    @Test fun aStopAfterARestoreThePhoneDidNotPollStillClears() {
        polledBeforeTheStop()
        apply()
        apply(hardStop = HardStop(2, utc(t1 + HOUR / 6)))
        queueUntimed(after)
        val restoredStop = now - HOUR / 4
        val resumed = now - HOUR / 6
        apply(windows = listOf(CaptureWindow(utc(restoredStop), utc(resumed))), hardStop = HardStop(1, utc(restoredStop)))
        assertEquals(0, count("upload_diagnostics"))
        assertTrue("rows captured after the resume stay", fence.captureWindows().none { now in it })
    }

    /** Responses apply in the order their polls were sent: two polls never overlap. */
    @Test fun statusPollsRunOneAtATime() {
        polledBeforeTheStop()
        val inFlight = AtomicInteger()
        val overlapped = AtomicBoolean()
        val pool = Executors.newFixedThreadPool(2)
        try {
            withStudyApi { method ->
                check(method == "getDeviceParticipationStatus") { method }
                if (inFlight.incrementAndGet() > 1) overlapped.set(true)
                Thread.sleep(OVERLAP_PROBE_MS)
                inFlight.decrementAndGet()
                MobileParticipationStatus(ParticipationStatus.ENROLLED, utc(System.currentTimeMillis()), listOf(window), stop)
            }.use {
                List(2) { pool.submit<StatusPoll> { pollParticipationStatus(context) } }
                    .forEach { assertEquals(StatusPoll.APPLIED, it.get(60, TimeUnit.SECONDS)) }
            }
        } finally { pool.shutdownNow() }
        assertFalse("a second poll ran while the first was in flight", overlapped.get())
    }

    @Test fun aMissedMarkNotEnrolledClearsThePreStopBacklogAndKeepsWhatCameAfter() {
        polledBeforeTheStop()
        queueCapturedAt(t0)
        queueUntimed()
        queueCapturedAt(after)
        withStudyApi { method ->
            check(method == "getDeviceParticipationStatus") { method }
            MobileParticipationStatus(ParticipationStatus.ENROLLED, utc(System.currentTimeMillis()), listOf(window), stop)
        }.use {
            assertEquals(ListenableWorker.Result.success(),
                onPersistenceWorker { EnrollmentMonitoringWorker(context, auditWorkerParameters()).doWork() })
        }
        assertEquals("usage and lifecycle rows captured before the stop are gone", listOf(after, after), queuedCaptureTimes())
        assertEquals("battery rows captured before the stop are gone", listOf(after), batteryTimes())
        assertEquals("pending diagnostics are cleared", 0, count("upload_diagnostics"))
        assertEquals("identity labels are cleared", 0, count("userQueue"))
        assertTrue("the usage source never reads the pre-stop history again",
            ResearchPersistenceGate.observationScope(context, CollectionModuleId.USAGE_EVENTS)!!.suspends(t0))
        assertTrue(ResearchPersistenceGate.observationScope(context, CollectionModuleId.USAGE_EVENTS)!!.admits(after))
        assertNotNull("the enrolled phone uploads what is left", ResearchPersistenceGate.runIfEnrolled(context) { "upload" })

        // The same generation again clears nothing: what was queued since stays.
        queueUntimed(after)
        apply()
        assertEquals(1, count("upload_diagnostics"))
        assertEquals(1, count("userQueue"))
        assertEquals(listOf(after, after), queuedCaptureTimes())

    }

    /** The stop arrives while the phone still reads NOT_ENROLLED: everything goes, and the later ENROLLED clears nothing new. */
    @Test fun anObservedStopIsConsumedOnce() {
        polledBeforeTheStop()
        queueCapturedAt(t0)
        queueUntimed()
        apply(ParticipationStatus.NOT_ENROLLED, listOf(CaptureWindow(utc(t1))))
        assertEquals(0, db.queueEntryData().getSize())
        apply()
        queueCapturedAt(after)
        queueUntimed(after)
        apply()
        assertEquals(listOf(after, after), queuedCaptureTimes())
        assertEquals(1, count("upload_diagnostics"))
        assertEquals(1, count("userQueue"))
    }

    /** A pause and resume is not a hard stop: what was captured before it still uploads. */
    @Test fun aPauseAndResumeClearsNothingBeforeIt() {
        polledBeforeTheStop()
        queueCapturedAt(t0)
        queueUntimed()
        queueCapturedAt(after)
        apply(hardStop = null)
        assertEquals(listOf(t0, t0, after, after), queuedCaptureTimes())
        assertEquals(listOf(t0, after), batteryTimes().sorted())
        assertEquals(1, count("upload_diagnostics"))
        assertEquals(1, count("userQueue"))
    }

    /** A label applies forward: one selected during a pause still labels what is captured after the resume. */
    @Test fun aLabelSelectedDuringAPauseSurvivesItsWindow() {
        polledBeforeTheStop()
        db.userQueueEntryData().insertEntries(listOf(UserQueueEntry((t1 + t2) / 2, "someone-else")))
        apply(hardStop = null)
        assertEquals(listOf("someone-else"), db.userQueueEntryData().getUserTimestamps().map { it.user })
    }

    private companion object {
        const val HOUR = 3_600_000L
        const val OVERLAP_PROBE_MS = 300L
    }
}
