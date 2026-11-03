package com.openlattice.chronicle.collection.state

import android.os.SystemClock
import com.openlattice.chronicle.collection.BatteryChargingState
import com.openlattice.chronicle.collection.BatteryHealth
import com.openlattice.chronicle.collection.BatteryPlugType
import com.openlattice.chronicle.collection.CollectionModuleId
import com.openlattice.chronicle.collection.battery.BatteryReading
import com.openlattice.chronicle.collection.battery.BatteryTelemetryCollectionModule
import com.openlattice.chronicle.collection.core.ModuleResult
import com.openlattice.chronicle.collection.lifecycle.LifecycleEventMapper
import com.openlattice.chronicle.collection.sink.BatterySampleSink
import com.openlattice.chronicle.constants.INTERACTION_BATTERY_CHARGING
import com.openlattice.chronicle.data.CaptureWindow
import com.openlattice.chronicle.data.HardStop
import com.openlattice.chronicle.data.ParticipationStatus
import com.openlattice.chronicle.models.ExtractedUsageEvent
import com.openlattice.chronicle.serialization.JsonSerializer
import com.openlattice.chronicle.services.lifecycle.DeviceLifecycleEventRecorder
import com.openlattice.chronicle.time.ServerClock
import java.time.Instant
import java.time.OffsetDateTime
import java.util.concurrent.CountDownLatch
import java.util.concurrent.FutureTask
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A lifecycle broadcast or a battery pull takes its observation token, then waits (the lifecycle
 * executor, the battery read). Meanwhile a status poll applies a capture window covering its capture time. The late
 * write must not persist inside the window, and a row captured outside every window still persists.
 */
class LateCaptureWindowTest : ResearchErasureTestFixture() {
    private val now = System.currentTimeMillis()
    private val t0 = now - 3 * HOUR
    private val t1 = now - 2 * HOUR
    private val t2 = now - HOUR
    private val inPause = (t1 + t2) / 2
    private val after = now - HOUR / 2
    private var window = CaptureWindow(utc(t1), utc(t2))
    private val stop = HardStop(1, utc(t1))

    private fun apply(hardStop: HardStop?) = assertTrue(onPersistenceWorker {
        ResearchPersistenceGate.applyParticipationStatus(context, owner, fence.settingsGeneration(),
            ParticipationStatus.ENROLLED, listOf(window), hardStop)
    })

    /** The phone polled while enrolled before it went offline: it has seen no hard stop. */
    private fun polledBeforeTheStop() = assertTrue(onPersistenceWorker {
        ResearchPersistenceGate.applyParticipationStatus(context, owner, fence.settingsGeneration(),
            ParticipationStatus.ENROLLED, emptyList(), null)
    })

    private fun queuedLifecycleTimes(): List<Long> = db.queueEntryData().getNextEntries(100).flatMap { row ->
        JsonSerializer.deserializeQueueEntry(row.data).map { (it as ExtractedUsageEvent).timestamp.toInstant().toEpochMilli() }
    }

    private fun batteryTimes(): List<Long> = db.openHelper.readableDatabase.query("SELECT timestamp FROM battery_samples").use {
        buildList { while (it.moveToNext()) add(OffsetDateTime.parse(it.getString(0)).toInstant().toEpochMilli()) }
    }

    /** A lifecycle broadcast captured at [capturedAt] waits on the lifecycle executor while [hardStop] applies. */
    private fun lateLifecycleWrite(capturedAt: Long, hardStop: HardStop?) {
        onPersistenceWorker {
            ResearchPersistenceGate.stop {
                DeviceLifecycleEventRecorder.recordAsync(context, LifecycleEventMapper.buildEvent(
                    "android.intent.action.ACTION_POWER_CONNECTED", INTERACTION_BATTERY_CHARGING, capturedAt))
                assertTrue(ResearchPersistenceGate.applyParticipationStatus(context, owner, fence.settingsGeneration(),
                    ParticipationStatus.ENROLLED, listOf(window), hardStop))
            }
        }
        val drained = CountDownLatch(1)
        DeviceLifecycleEventRecorder.recordObserved(context) { drained.countDown(); emptyList() }
        assertTrue(drained.await(5, TimeUnit.SECONDS))
    }

    /** A battery pull stamped [capturedAt] is held in its read while [hardStop] applies. */
    private fun lateBatteryWrite(capturedAt: Long, hardStop: HardStop?) {
        val reading = CountDownLatch(1)
        val resume = CountDownLatch(1)
        val module = BatteryTelemetryCollectionModule(
            sink = BatterySampleSink(db.batterySampleDao(),
                ResearchPersistenceGate.guard(context, CollectionModuleId.BATTERY_TELEMETRY)),
            source = {
                reading.countDown()
                check(resume.await(5, TimeUnit.SECONDS))
                BatteryReading(50, BatteryChargingState.CHARGING, BatteryPlugType.AC, 300, 4000, BatteryHealth.GOOD)
            },
            enrolled = { true },
            now = { capturedAt },
        )
        val sample = FutureTask { module.sample() }
        Thread(sample, "battery-pull").apply { isDaemon = true }.start()
        try {
            assertTrue(reading.await(5, TimeUnit.SECONDS))
            apply(hardStop)
            resume.countDown()
            sample.get(5, TimeUnit.SECONDS)
        } finally { resume.countDown() }
    }

    @Test fun aLifecycleEventCapturedBeforeAMissedHardStopNeverPersists() {
        polledBeforeTheStop()
        lateLifecycleWrite(t0, stop)
        assertEquals(emptyList<Long>(), queuedLifecycleTimes())
    }

    @Test fun aLifecycleEventCapturedInsideAClosedPauseNeverPersists() {
        polledBeforeTheStop()
        lateLifecycleWrite(inPause, hardStop = null)
        assertEquals(emptyList<Long>(), queuedLifecycleTimes())
    }

    @Test fun aLifecycleEventCapturedOutsideEveryWindowStillPersists() {
        polledBeforeTheStop()
        lateLifecycleWrite(after, stop)
        assertEquals(listOf(after), queuedLifecycleTimes())
    }

    @Test fun aBatterySampleCapturedBeforeAMissedHardStopNeverPersists() {
        polledBeforeTheStop()
        lateBatteryWrite(t0, stop)
        assertEquals(emptyList<Long>(), batteryTimes())
    }

    @Test fun aBatterySampleCapturedInsideAClosedPauseNeverPersists() {
        polledBeforeTheStop()
        lateBatteryWrite(inPause, hardStop = null)
        assertEquals(emptyList<Long>(), batteryTimes())
    }

    @Test fun aBatterySampleCapturedOutsideEveryWindowStillPersists() {
        polledBeforeTheStop()
        lateBatteryWrite(after, stop)
        assertEquals(listOf(after), batteryTimes())
    }

    @Test fun lateSamplesUseTheServerToPhoneClockConversion() {
        val skew = 4 * HOUR
        val elapsed = SystemClock.elapsedRealtime()
        ServerClock.of(context).measure(Instant.ofEpochMilli(System.currentTimeMillis() - skew), elapsed, elapsed)
        window = CaptureWindow(utc(t1 - skew), utc(t2 - skew))
        polledBeforeTheStop()

        lateBatteryWrite(inPause, hardStop = null)
        lateBatteryWrite(after, hardStop = null)

        assertEquals(listOf(after), batteryTimes())
    }

    @Test fun aMixedBatchKeepsExactlyTheRowsOutsideHalfOpenWindows() {
        polledBeforeTheStop()
        lateBatteryWrite(after, stop)
        val template = db.batterySampleDao().getOldest(1).single()
        val times = listOf(t0, t1 - 1, t1, t2 - 1, t2)
        onPersistenceWorker {
            val sink = BatterySampleSink(db.batterySampleDao(),
                ResearchPersistenceGate.guard(context, CollectionModuleId.BATTERY_TELEMETRY))
            assertEquals(ModuleResult.Ok(1), sink.write(times.map {
                template.copy(id = it.toString(), timestamp = utc(it).toString())
            }))
        }
        assertEquals(listOf(t2, after), batteryTimes().sorted())
    }

    private companion object {
        const val HOUR = 3_600_000L
    }
}
