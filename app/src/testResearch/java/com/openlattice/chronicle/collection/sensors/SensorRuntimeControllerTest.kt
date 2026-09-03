package com.openlattice.chronicle.collection.sensors

import com.openlattice.chronicle.android.AndroidSensorType
import com.openlattice.chronicle.collection.core.FixedCollectionClock
import com.openlattice.chronicle.collection.core.ModuleResult
import com.openlattice.chronicle.collection.core.NoOpCollectionLog
import com.openlattice.chronicle.collection.sink.FakeSensorSampleDao
import com.openlattice.chronicle.collection.sink.SensorSampleSink
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.OffsetDateTime

/**
 * JVM unit coverage for [SensorRuntimeController] — the Phase 6A sensor runtime extracted
 * from `HardwareSensorService`.
 *
 * Drives the controller over the [FakeSensorGateway] / [ManualSensorRuntimeScheduler] /
 * [FakeSensorRuntimeSettings] / [SensorSampleSink]-over-[FakeSensorSampleDao] seams — no
 * Android `Context`, no Robolectric. Proves the controller preserves: the duty cycle
 * (active → flush → idle → re-arm), power-save degraded mode (doubled idle window /
 * sampling period / batch latency), critical-battery stop (skip phase + live-broadcast
 * stop), the buffer flush at 500 and on stop, the re-queue-on-failure semantics, and the
 * destroy-flush failure surfaced in diagnostics.
 */
class SensorRuntimeControllerTest {

    private fun controller(
        gateway: FakeSensorGateway = FakeSensorGateway(),
        settings: FakeSensorRuntimeSettings = FakeSensorRuntimeSettings(),
        scheduler: ManualSensorRuntimeScheduler = ManualSensorRuntimeScheduler(),
        dao: FakeSensorSampleDao = FakeSensorSampleDao(),
        collectionGate: (AndroidSensorType) -> Boolean = { true },
    ): SensorRuntimeController {
        val c = SensorRuntimeController(
            gateway = gateway,
            settings = settings,
            sink = SensorSampleSink(dao, NoOpCollectionLog),
            scheduler = scheduler,
            collectionGate = collectionGate,
            clock = FixedCollectionClock(1_000L),
            log = NoOpCollectionLog,
        )
        gateway.attach(
            object : SensorGateway.SampleListener {
                override fun onSample(
                    sensorType: AndroidSensorType,
                    values: FloatArray,
                    accuracy: Int,
                    timestamp: OffsetDateTime,
                ) {
                    c.recordSample(sensorType, values, accuracy, timestamp)
                }

                override fun onTrigger(
                    sensorType: AndroidSensorType,
                    values: FloatArray,
                    timestamp: OffsetDateTime,
                ) {
                    c.recordSample(sensorType, values, null, timestamp)
                }

                override fun onPersistentRegistrationLost(sensorType: AndroidSensorType) {
                    c.onPersistentRegistrationLost(sensorType)
                }
            },
        )
        return c
    }

    private fun sample() = floatArrayOf(1.0f, 2.0f, 3.0f)

    // ----- duty cycle -----

    @Test
    fun startBeginsAnActivePhaseAndRegistersEnabledSensors() {
        val gateway = FakeSensorGateway()
        val settings = FakeSensorRuntimeSettings(sensors = setOf(AndroidSensorType.accelerometer))
        val c = controller(gateway = gateway, settings = settings)

        c.start()

        assertTrue("runtime should be started", c.isStarted)
        assertTrue("active phase should be collecting", c.isCollecting)
        assertEquals(listOf(AndroidSensorType.accelerometer), gateway.registeredContinuous)
    }

    @Test
    fun invalidSamplingRatesNeverRegisterContinuousSensors() {
        listOf(0, 201).forEach { rate ->
            val gateway = FakeSensorGateway()
            val settings = FakeSensorRuntimeSettings(samplingRate = rate)

            controller(gateway = gateway, settings = settings).start()

            assertTrue("rate $rate must not register a sensor", gateway.registeredContinuous.isEmpty())
        }
    }

    @Test
    fun dutyCycleStopsCollectionAfterActivePhaseThenReArms() {
        val gateway = FakeSensorGateway()
        val scheduler = ManualSensorRuntimeScheduler()
        val settings = FakeSensorRuntimeSettings(dutyActive = 30, dutyPeriod = 300)
        val c = controller(gateway = gateway, scheduler = scheduler, settings = settings)

        c.start()
        assertTrue(c.isCollecting)
        // First scheduled task = the active-phase end (delay == activeSeconds == 30).
        val activeDelay = scheduler.runNext()
        assertEquals(30L, activeDelay)
        assertFalse("collection stops at the end of the active phase", c.isCollecting)
        // Next scheduled task = the idle-window re-arm (delay == idle == 270).
        val idleDelay = scheduler.runNext()
        assertEquals(270L, idleDelay)
        // Re-arm started a new active phase.
        assertTrue("duty cycle re-armed a new active phase", c.isCollecting)
    }

    @Test
    fun startIsIdempotent() {
        val scheduler = ManualSensorRuntimeScheduler()
        val c = controller(scheduler = scheduler)
        c.start()
        val afterFirst = scheduler.scheduled.size
        c.start() // duplicate — must not schedule a second cycle
        assertEquals(afterFirst, scheduler.scheduled.size)
    }

    // ----- reconcile (a sensor enabled while the service is already running) -----

    @Test
    fun reconcileSchedulesASensorEnabledWhileAlreadyRunning() {
        // Regression: toggling a per-sensor module on (or a study adding a sensor) while the
        // service already runs for another sensor must start collecting it without a full
        // service restart — onStartCommand calls reconcile().
        val gateway = FakeSensorGateway()
        val settings = FakeSensorRuntimeSettings(sensors = setOf(AndroidSensorType.accelerometer))
        val c = controller(gateway = gateway, settings = settings)
        c.start()
        assertEquals(listOf(AndroidSensorType.accelerometer), gateway.registeredContinuous)

        // Consent/study now also enables the gyroscope while running.
        settings.sensors = setOf(AndroidSensorType.accelerometer, AndroidSensorType.gyroscope)
        c.reconcile()

        assertTrue(
            "reconcile must register the newly-enabled sensor",
            gateway.registeredContinuous.contains(AndroidSensorType.gyroscope),
        )
    }

    @Test
    fun reconcileDoesNotDoubleScheduleAnAlreadyRunningSensor() {
        val scheduler = ManualSensorRuntimeScheduler()
        val settings = FakeSensorRuntimeSettings(sensors = setOf(AndroidSensorType.accelerometer))
        val c = controller(scheduler = scheduler, settings = settings)
        c.start()
        val afterStart = scheduler.scheduled.size
        c.reconcile() // unchanged config — must not launch a second loop for accelerometer
        assertEquals(afterStart, scheduler.scheduled.size)
    }

    @Test
    fun reconcileBeforeStartIsANoOp() {
        val scheduler = ManualSensorRuntimeScheduler()
        val c = controller(scheduler = scheduler)
        c.reconcile() // never started
        assertFalse(c.isStarted)
        assertTrue(scheduler.scheduled.isEmpty())
    }

    // ----- power-save degraded mode -----

    @Test
    fun powerSaveModeDoublesIdleWindowAndSamplingPeriodAndBatchLatency() {
        val gateway = FakeSensorGateway().apply { powerSaveMode = true }
        val scheduler = ManualSensorRuntimeScheduler()
        // 10Hz → base period 100_000us; active 30 / period 300 → base idle 270.
        val settings = FakeSensorRuntimeSettings(samplingRate = 10, dutyActive = 30, dutyPeriod = 300)
        val c = controller(gateway = gateway, scheduler = scheduler, settings = settings)

        c.start()

        assertEquals(SensorCollectionMode.DEGRADED, c.mode)
        // Sampling period and batch latency doubled.
        assertEquals(200_000, gateway.lastSamplingPeriodUs)
        assertEquals(10_000_000, gateway.lastMaxReportLatencyUs)
        // Active-phase end delay is unchanged (30); the idle window is doubled (270*2).
        assertEquals(30L, scheduler.runNext())
        assertEquals(540L, scheduler.runNext())
    }

    @Test
    fun normalModeUsesUnscaledTimings() {
        val gateway = FakeSensorGateway().apply { powerSaveMode = false }
        val settings = FakeSensorRuntimeSettings(samplingRate = 10)
        val c = controller(gateway = gateway, settings = settings)
        c.start()
        assertEquals(SensorCollectionMode.NORMAL, c.mode)
        assertEquals(100_000, gateway.lastSamplingPeriodUs)
        assertEquals(5_000_000, gateway.lastMaxReportLatencyUs)
    }

    // ----- critical-battery stop -----

    @Test
    fun criticalBatterySkipsTheActivePhase() {
        val gateway = FakeSensorGateway().apply { batteryPercent = 10 } // <= 15
        val scheduler = ManualSensorRuntimeScheduler()
        val c = controller(gateway = gateway, scheduler = scheduler)

        c.start()

        assertFalse("active phase must be skipped at critical battery", c.isCollecting)
        assertTrue(gateway.registeredContinuous.isEmpty())
        // Only the idle re-check is scheduled — no active-phase-end task.
        assertEquals(1, scheduler.scheduled.size)
    }

    @Test
    fun batteryAtThresholdSkipsCollectionButAboveThresholdCollects() {
        val atThreshold = FakeSensorGateway().apply { batteryPercent = 15 }
        assertFalse(controller(gateway = atThreshold).also { it.start() }.isCollecting)

        val aboveThreshold = FakeSensorGateway().apply { batteryPercent = 16 }
        assertTrue(controller(gateway = aboveThreshold).also { it.start() }.isCollecting)
    }

    @Test
    fun negativeBatteryReadingIsTreatedAsCollectable() {
        // BatteryManager returns a negative value when capacity is unavailable; legacy
        // shouldCollect() treated that as collectable (level in 0..15 excludes negatives).
        val gateway = FakeSensorGateway().apply { batteryPercent = -1 }
        val c = controller(gateway = gateway)
        c.start()
        assertTrue(c.isCollecting)
    }

    @Test
    fun liveBatteryBroadcastBelowThresholdStopsAnInFlightActivePhase() {
        val gateway = FakeSensorGateway()
        val c = controller(gateway = gateway)
        c.start()
        assertTrue(c.isCollecting)

        c.onBatteryLevel(12) // critical broadcast mid-phase

        assertFalse("a critical battery broadcast stops collection", c.isCollecting)
        // Critical battery tears down each in-flight continuous sensor individually (per-sensor
        // teardown); the low-power persistent sensors are not unregistered by a pause.
        assertEquals(1, gateway.unregisterContinuousSensorCount)
        assertEquals(0, gateway.unregisterAllCount)
    }

    @Test
    fun liveBatteryBroadcastAboveThresholdDoesNotStopCollection() {
        val c = controller()
        c.start()
        c.onBatteryLevel(50)
        assertTrue(c.isCollecting)
    }

    // ----- buffer flush -----

    @Test
    fun bufferFlushesAutomaticallyAtFiveHundredSamples() {
        val dao = FakeSensorSampleDao()
        val gateway = FakeSensorGateway()
        val c = controller(gateway = gateway, dao = dao)
        c.start()

        // 499 samples — below the threshold, nothing flushed yet.
        repeat(499) { gateway.emitSample(AndroidSensorType.accelerometer, sample()) }
        assertEquals("no flush below 500", 0, dao.count())
        assertEquals(499, c.bufferedCount)

        // The 500th sample triggers a flush (ManualScheduler.execute runs it synchronously).
        gateway.emitSample(AndroidSensorType.accelerometer, sample())
        assertEquals("flush at 500", 500, dao.count())
        assertEquals(0, c.bufferedCount)
    }

    @Test
    fun stopDrainsTheBufferToTheSink() {
        val dao = FakeSensorSampleDao()
        val gateway = FakeSensorGateway()
        val c = controller(gateway = gateway, dao = dao)
        c.start()
        repeat(10) { gateway.emitSample(AndroidSensorType.gyroscope, sample()) }
        assertEquals(10, c.bufferedCount)

        c.stop()

        assertEquals("buffer drained on stop", 10, dao.count())
        assertEquals(0, c.bufferedCount)
        assertFalse(c.isStarted)
    }

    @Test
    fun stopStopsCollectionBeforeDrainingSoNoSampleRefillsMidDrain() {
        val gateway = FakeSensorGateway()
        val c = controller(gateway = gateway)
        c.start()
        assertTrue(c.isCollecting)
        c.stop()
        // unregisterAll is called by stopCollecting before the drain.
        assertTrue(gateway.unregisterAllCount >= 1)
        assertFalse(c.isCollecting)
    }

    @Test
    fun failedFlushReQueuesSamplesForRetryNeverLosesThem() {
        val dao = FakeSensorSampleDao().apply { failNextInsert = true }
        val gateway = FakeSensorGateway()
        val c = controller(gateway = gateway, dao = dao)
        c.start()
        repeat(5) { gateway.emitSample(AndroidSensorType.accelerometer, sample()) }

        val result = c.flushBuffer()

        assertTrue("flush failure surfaces as Failed", result is ModuleResult.Failed)
        assertEquals("nothing persisted on a failed flush", 0, dao.count())
        // Samples re-queued — not lost.
        assertEquals("samples re-queued for retry", 5, c.bufferedCount)

        // Next flush (DAO no longer failing) persists the re-queued samples.
        val retry = c.flushBuffer()
        assertTrue(retry is ModuleResult.Ok)
        assertEquals(5, dao.count())
    }

    @Test
    fun failedThresholdFlushRetriesAfterDelayWithoutATightLoop() {
        val dao = FakeSensorSampleDao().apply { failNextInsert = true }
        val gateway = FakeSensorGateway()
        val scheduler = ManualSensorRuntimeScheduler()
        val settings = FakeSensorRuntimeSettings(sensors = setOf(AndroidSensorType.tiltDetector))
        val c = controller(
            gateway = gateway,
            settings = settings,
            scheduler = scheduler,
            dao = dao,
        )
        c.start()

        repeat(SensorRuntimeController.FLUSH_THRESHOLD) {
            gateway.emitSample(AndroidSensorType.tiltDetector, sample())
        }

        assertEquals("failed batch remains queued", SensorRuntimeController.FLUSH_THRESHOLD, c.bufferedCount)
        assertEquals("failed batch is not persisted", 0, dao.count())
        assertEquals(
            "storage retry is delayed instead of recursively executed",
            listOf(SensorRuntimeController.STORAGE_RETRY_DELAY_SECONDS),
            scheduler.scheduled.map { it.first },
        )

        gateway.emitSample(AndroidSensorType.tiltDetector, sample())
        assertEquals(
            "new samples cannot bypass the outstanding storage backoff",
            SensorRuntimeController.FLUSH_THRESHOLD + 1,
            c.bufferedCount,
        )
        assertEquals(0, dao.count())
        assertEquals("only one delayed retry remains owned", 1, scheduler.scheduled.size)

        assertEquals(SensorRuntimeController.STORAGE_RETRY_DELAY_SECONDS, scheduler.runNext())
        assertEquals(
            "delayed retry persists the retained batch and intervening sample",
            SensorRuntimeController.FLUSH_THRESHOLD + 1,
            dao.count(),
        )
        assertEquals(0, c.bufferedCount)
    }

    @Test
    fun thresholdBurstSchedulesOnlyOneFlushTask() {
        val gateway = FakeSensorGateway()
        val scheduler = ManualSensorRuntimeScheduler(executeImmediately = false)
        val c = controller(gateway = gateway, scheduler = scheduler)
        c.start()
        scheduler.runNextExecution() // Complete the deferred start task before measuring flush scheduling.
        val executionsBeforeBurst = scheduler.executeCount

        repeat(600) { gateway.emitSample(AndroidSensorType.accelerometer, sample()) }

        assertEquals("only one flush task may be queued", executionsBeforeBurst + 1, scheduler.executeCount)
        assertEquals(600, c.bufferedCount)
        scheduler.runNextExecution()
        assertEquals(0, c.bufferedCount)
    }

    @Test
    fun failedStorageCannotGrowSensorBufferPastHardLimit() {
        val gateway = FakeSensorGateway()
        val scheduler = ManualSensorRuntimeScheduler(executeImmediately = false)
        val c = controller(gateway = gateway, scheduler = scheduler)
        c.start()
        scheduler.runNextExecution() // Complete the deferred start task before measuring flush scheduling.
        val executionsBeforeBurst = scheduler.executeCount

        repeat(SensorRuntimeController.MAX_BUFFERED_SAMPLES + 250) {
            gateway.emitSample(AndroidSensorType.accelerometer, sample())
        }

        assertEquals(SensorRuntimeController.MAX_BUFFERED_SAMPLES, c.bufferedCount)
        assertEquals(250L, c.droppedCount)
        assertEquals(executionsBeforeBurst + 1, scheduler.executeCount)
    }

    @Test
    fun emptyBufferFlushIsAnIdempotentOkNoOp() {
        val c = controller()
        val result = c.flushBuffer()
        assertEquals(ModuleResult.Ok(0), result)
    }

    // ----- collection-loop gate (design §7) -----

    @Test
    fun closedGateDropsBufferedSamplesAndPersistsNothing() {
        // Even with samples buffered (e.g. fed by always-armed persistent sensors after a
        // legacy-path start), a closed gate must persist nothing and must not retain the
        // un-acknowledged samples for a later flush.
        val dao = FakeSensorSampleDao()
        val gateway = FakeSensorGateway()
        val c = controller(gateway = gateway, dao = dao, collectionGate = { false })
        c.start()
        repeat(5) { gateway.emitSample(AndroidSensorType.accelerometer, sample()) }

        val result = c.flushBuffer()

        assertTrue("a closed gate skips the flush", result is ModuleResult.Skipped)
        assertEquals("nothing persisted while the gate is closed", 0, dao.count())
        assertEquals("un-acknowledged samples are dropped, not retained", 0, c.bufferedCount)
    }

    @Test
    fun flushEvaluatesCollectionGateOncePerDistinctSensor() {
        val dao = FakeSensorSampleDao()
        val gateCalls = mutableMapOf<AndroidSensorType, Int>()
        val c = controller(
            dao = dao,
            collectionGate = { sensorType ->
                gateCalls[sensorType] = (gateCalls[sensorType] ?: 0) + 1
                true
            },
        )

        repeat(8) { c.recordSample(AndroidSensorType.accelerometer, sample(), 3) }
        repeat(5) { c.recordSample(AndroidSensorType.gyroscope, sample(), 3) }

        assertTrue(c.flushBuffer() is ModuleResult.Ok)
        assertEquals(13, dao.count())
        assertEquals(1, gateCalls[AndroidSensorType.accelerometer])
        assertEquals(1, gateCalls[AndroidSensorType.gyroscope])
        assertEquals(2, gateCalls.size)
    }

    @Test
    fun closedGateSkipsTheActivePhaseSoNoContinuousSensorRegisters() {
        val gateway = FakeSensorGateway()
        val settings = FakeSensorRuntimeSettings(sensors = setOf(AndroidSensorType.accelerometer))
        val c = controller(gateway = gateway, settings = settings, collectionGate = { false })

        c.start()

        assertFalse("active phase must be skipped while the gate is closed", c.isCollecting)
        assertTrue("no continuous sensor registers while un-acknowledged", gateway.registeredContinuous.isEmpty())
    }

    @Test
    fun reopeningTheGateResumesCollectionOnTheNextCycleAndFlush() {
        val dao = FakeSensorSampleDao()
        val gateway = FakeSensorGateway()
        val scheduler = ManualSensorRuntimeScheduler()
        var gateOpen = false
        val settings = FakeSensorRuntimeSettings(sensors = setOf(AndroidSensorType.accelerometer))
        val c = controller(
            gateway = gateway,
            settings = settings,
            scheduler = scheduler,
            dao = dao,
            collectionGate = { gateOpen },
        )

        c.start()
        assertFalse("closed at start — no active phase", c.isCollecting)
        // Run the idle re-check that the skipped cycle scheduled.
        gateOpen = true
        scheduler.runNext()
        assertTrue("re-armed once the gate opened", c.isCollecting)

        gateway.emitSample(AndroidSensorType.accelerometer, sample())
        val result = c.flushBuffer()
        assertTrue(result is ModuleResult.Ok)
        assertEquals("acknowledged samples now persist", 1, dao.count())
    }

    // ----- destroy-flush diagnostics -----

    @Test
    fun serviceDestroyFlushFailureIsSurfacedInDiagnostics() {
        val dao = FakeSensorSampleDao()
        val gateway = FakeSensorGateway()
        val c = controller(gateway = gateway, dao = dao)
        c.start()
        repeat(3) { gateway.emitSample(AndroidSensorType.accelerometer, sample()) }
        dao.failNextInsert = true

        c.stop(isServiceDestroy = true)

        assertNotNull("a destroy-flush failure must be visible in diagnostics", c.lastDestroyFlushFailed)
    }

    @Test
    fun successfulServiceDestroyFlushLeavesNoDestroyFailure() {
        val gateway = FakeSensorGateway()
        val c = controller(gateway = gateway)
        c.start()
        c.stop(isServiceDestroy = true)
        assertNull(c.lastDestroyFlushFailed)
    }

    @Test
    fun recordDestroyFlushFailureSurfacesAnOutOfContractFailure() {
        val c = controller()
        c.recordDestroyFlushFailure("destroy-flush timed out")
        assertEquals("destroy-flush timed out", c.lastDestroyFlushFailed)
    }

    // ----- trigger sensors -----

    @Test
    fun triggerSensorsAreRegisteredSeparatelyFromContinuousSensors() {
        // SIGNIFICANT_MOTION is a trigger sensor in SensorTypeMapping.
        val triggerType = AndroidSensorType.values().firstOrNull { isTrigger(it) }
        if (triggerType != null) {
            val gateway = FakeSensorGateway()
            val settings = FakeSensorRuntimeSettings(sensors = setOf(triggerType))
            val c = controller(gateway = gateway, settings = settings)
            c.start()
            assertEquals(listOf(triggerType), gateway.registeredTrigger)
            assertEquals("a trigger sensor is a persistent (always-armed) sensor",
                listOf(triggerType), gateway.registeredPersistent)
            assertTrue(gateway.registeredContinuous.isEmpty())
        }
    }

    // ----- persistent (always-armed) event sensors -----

    @Test
    fun eventSensorsRegisterOnceAtStartAndStayArmedAcrossIdleWindows() {
        // tiltDetector is an on-change sensor (not continuous) -> registered persistently.
        val gateway = FakeSensorGateway()
        val scheduler = ManualSensorRuntimeScheduler()
        val settings = FakeSensorRuntimeSettings(
            sensors = setOf(AndroidSensorType.accelerometer, AndroidSensorType.tiltDetector),
            dutyActive = 30,
            dutyPeriod = 300,
        )
        val c = controller(gateway = gateway, scheduler = scheduler, settings = settings)

        c.start()
        // Continuous accelerometer duty-cycled; tiltDetector armed persistently.
        assertEquals(listOf(AndroidSensorType.accelerometer), gateway.registeredContinuous)
        assertEquals(listOf(AndroidSensorType.tiltDetector), gateway.registeredPersistent)

        // End of the active phase: continuous torn down, persistent left armed.
        scheduler.runNext()
        assertTrue("continuous listeners torn down each idle window", gateway.registeredContinuous.isEmpty())
        assertEquals("persistent sensor stays armed across the idle window",
            listOf(AndroidSensorType.tiltDetector), gateway.registeredPersistent)
        assertEquals(1, gateway.unregisterContinuousSensorCount)
        assertEquals("idle window must not call the full teardown", 0, gateway.unregisterAllCount)

        // Re-arm the next active phase: continuous re-registered, persistent NOT re-registered.
        scheduler.runNext()
        assertEquals(listOf(AndroidSensorType.accelerometer), gateway.registeredContinuous)
        assertEquals("persistent sensor registered exactly once, not per cycle",
            listOf(AndroidSensorType.tiltDetector), gateway.registeredPersistent)
    }

    @Test
    fun eventSampleArrivingDuringTheIdleWindowIsStillCaptured() {
        // This is the regression the fix targets: under the old "duty-cycle everything"
        // behaviour a tilt during the idle window was lost (listener torn down). Now the
        // persistent listener stays armed, so the sample is buffered.
        val gateway = FakeSensorGateway()
        val scheduler = ManualSensorRuntimeScheduler()
        val settings = FakeSensorRuntimeSettings(sensors = setOf(AndroidSensorType.tiltDetector))
        val c = controller(gateway = gateway, scheduler = scheduler, settings = settings)

        c.start()
        scheduler.runNext() // advance into the idle window (active phase ended)
        assertFalse("we are in an idle window", c.isCollecting)

        gateway.emitSample(AndroidSensorType.tiltDetector, sample())

        assertEquals("an event-sensor sample during idle is still buffered", 1, c.bufferedCount)
    }

    @Test
    fun stopUnregistersPersistentSensorsToo() {
        val gateway = FakeSensorGateway()
        val settings = FakeSensorRuntimeSettings(sensors = setOf(AndroidSensorType.tiltDetector))
        val c = controller(gateway = gateway, settings = settings)
        c.start()
        assertEquals(listOf(AndroidSensorType.tiltDetector), gateway.registeredPersistent)

        c.stop()

        assertTrue("full stop tears down persistent sensors", gateway.registeredPersistent.isEmpty())
        assertEquals(1, gateway.unregisterAllCount)
    }

    @Test
    fun failedPersistentRegistrationIsRetriedAfterDelay() {
        val sensorType = AndroidSensorType.tiltDetector
        val gateway = FakeSensorGateway().apply {
            persistentRegistrationFailuresRemaining[sensorType] = 1
        }
        val scheduler = ManualSensorRuntimeScheduler()
        val settings = FakeSensorRuntimeSettings(sensors = setOf(sensorType))
        val c = controller(gateway = gateway, scheduler = scheduler, settings = settings)

        c.start()

        assertTrue(gateway.registeredPersistent.isEmpty())
        assertEquals(listOf(SensorRuntimeController.PERSISTENT_RETRY_DELAY_SECONDS), scheduler.scheduled.map { it.first })
        assertEquals(SensorRuntimeController.PERSISTENT_RETRY_DELAY_SECONDS, scheduler.runNext())
        assertEquals(listOf(sensorType), gateway.registeredPersistent)
        assertEquals(listOf(sensorType, sensorType), gateway.persistentRegistrationAttempts)
    }

    @Test
    fun lostTriggerOwnershipSchedulesOnlyOneRearmRetry() {
        val sensorType = AndroidSensorType.values().first { isTrigger(it) }
        val gateway = FakeSensorGateway()
        val scheduler = ManualSensorRuntimeScheduler()
        val settings = FakeSensorRuntimeSettings(sensors = setOf(sensorType))
        val c = controller(gateway = gateway, scheduler = scheduler, settings = settings)
        c.start()
        assertEquals(listOf(sensorType), gateway.registeredPersistent)

        gateway.losePersistentRegistration(sensorType)
        gateway.losePersistentRegistration(sensorType)

        assertTrue(gateway.registeredPersistent.isEmpty())
        assertEquals("duplicate loss callbacks share one retry", 1, scheduler.scheduled.size)
        assertEquals(SensorRuntimeController.PERSISTENT_RETRY_DELAY_SECONDS, scheduler.runNext())
        assertEquals(listOf(sensorType), gateway.registeredPersistent)
        assertEquals(2, gateway.persistentRegistrationAttempts.size)
    }

    @Test
    fun persistentRegistrationRetriesAreBounded() {
        val sensorType = AndroidSensorType.tiltDetector
        val gateway = FakeSensorGateway().apply {
            persistentRegistrationFailuresRemaining[sensorType] = 100
        }
        val scheduler = ManualSensorRuntimeScheduler()
        val settings = FakeSensorRuntimeSettings(sensors = setOf(sensorType))
        val c = controller(gateway = gateway, scheduler = scheduler, settings = settings)

        c.start()
        scheduler.runAll(limit = 20)

        assertEquals(
            "one initial attempt plus the bounded delayed retries",
            1 + SensorRuntimeController.MAX_PERSISTENT_RETRY_ATTEMPTS,
            gateway.persistentRegistrationAttempts.size,
        )
        assertTrue("no unbounded retry remains queued", scheduler.scheduled.isEmpty())
    }

    // ----- per-sensor rate / duty cycle (per-sensor consent redesign) -----

    @Test
    fun eachSensorRegistersAtItsOwnSamplingRate() {
        val gateway = FakeSensorGateway()
        val settings = FakeSensorRuntimeSettings(
            sensors = setOf(AndroidSensorType.accelerometer, AndroidSensorType.gyroscope),
            rateOverrides = mutableMapOf(
                AndroidSensorType.accelerometer to 50, // 1_000_000 / 50 = 20_000us
                AndroidSensorType.gyroscope to 5,      // 1_000_000 / 5  = 200_000us
            ),
        )
        val c = controller(gateway = gateway, settings = settings)

        c.start()

        assertEquals(20_000, gateway.continuousSamplingPeriodUs[AndroidSensorType.accelerometer])
        assertEquals(200_000, gateway.continuousSamplingPeriodUs[AndroidSensorType.gyroscope])
    }

    @Test
    fun eachSensorDutyCyclesOnItsOwnIndependentLoop() {
        // Two continuous sensors with different periods each schedule their own duty-cycle loop.
        val gateway = FakeSensorGateway()
        val scheduler = ManualSensorRuntimeScheduler()
        val settings = FakeSensorRuntimeSettings(
            sensors = setOf(AndroidSensorType.accelerometer, AndroidSensorType.gyroscope),
            activeOverrides = mutableMapOf(
                AndroidSensorType.accelerometer to 10,
                AndroidSensorType.gyroscope to 60,
            ),
            periodOverrides = mutableMapOf(
                AndroidSensorType.accelerometer to 100,
                AndroidSensorType.gyroscope to 600,
            ),
        )
        val c = controller(gateway = gateway, scheduler = scheduler, settings = settings)

        c.start()

        assertEquals(
            setOf(AndroidSensorType.accelerometer, AndroidSensorType.gyroscope),
            gateway.registeredContinuous.toSet(),
        )
        // One independent active-phase task per continuous sensor.
        assertEquals(2, scheduler.scheduled.size)
        // The two scheduled active-phase delays are each sensor's own active window (10s, 60s).
        assertEquals(setOf(10L, 60L), scheduler.scheduled.map { it.first }.toSet())
    }

    @Test
    fun perSensorGateCollectsOnlyTheAcknowledgedSensor() {
        // accelerometer acknowledged, gyroscope not — only the acknowledged one registers/persists.
        val gateway = FakeSensorGateway()
        val settings = FakeSensorRuntimeSettings(
            sensors = setOf(AndroidSensorType.accelerometer, AndroidSensorType.gyroscope),
        )
        val c = controller(
            gateway = gateway,
            settings = settings,
            collectionGate = { it == AndroidSensorType.accelerometer },
        )

        c.start()

        assertEquals(listOf(AndroidSensorType.accelerometer), gateway.registeredContinuous)
    }

    @Test
    fun unavailableSensorIsSkippedWithoutFailingTheActivePhase() {
        val gateway = FakeSensorGateway()
        gateway.availableOnDevice.remove(AndroidSensorType.gyroscope)
        val settings = FakeSensorRuntimeSettings(
            sensors = setOf(AndroidSensorType.accelerometer, AndroidSensorType.gyroscope),
        )
        val c = controller(gateway = gateway, settings = settings)
        c.start()
        // Accelerometer registered; gyroscope skipped silently.
        assertEquals(listOf(AndroidSensorType.accelerometer), gateway.registeredContinuous)
        assertTrue(c.isCollecting)
    }
}
