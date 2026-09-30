package com.openlattice.chronicle.collection.sensors

import com.openlattice.chronicle.android.AndroidSensorType
import com.openlattice.chronicle.collection.CollectionModuleSetting
import com.openlattice.chronicle.collection.core.CollectionClock
import com.openlattice.chronicle.collection.core.CollectionLog
import com.openlattice.chronicle.collection.core.ModuleResult
import com.openlattice.chronicle.collection.state.CollectionPersistenceGuard
import com.openlattice.chronicle.collection.sink.SensorSampleWriter
import com.openlattice.chronicle.sensors.SensorTypeMapping
import com.openlattice.chronicle.storage.SensorSampleEntry
import java.time.OffsetDateTime
import java.time.ZoneId
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

private const val TAG = "SensorRuntimeController"

/**
 * Power-save collection mode (refactor plan §9.1 step 9 — "preserve power-save degraded
 * mode"). [DEGRADED] doubles the duty-cycle idle window, the sampling period and the
 * batch latency, exactly as the legacy `HardwareSensorService.CollectionMode` did.
 */
public enum class SensorCollectionMode {
    NORMAL,
    DEGRADED;

    /** The Methodic-style multiplier: degraded mode runs at half the cadence. */
    public val multiplier: Int get() = if (this == DEGRADED) 2 else 1
}

/**
 * The hardware-sensor runtime, extracted out of `HardwareSensorService` (refactor plan
 * §9.1 step 2). This is pure, `Context`-free logic — it owns:
 *
 *  - **per-sensor duty cycles** — since the per-sensor consent redesign (2026-06-11) each
 *    sensor is its own module with its own sampling rate + duty cycle, so each continuous
 *    sensor runs an **independent** active-phase / idle-phase loop through
 *    [SensorRuntimeScheduler] at its own [SensorRuntimeSettings.dutyCycleActiveSeconds] /
 *    [SensorRuntimeSettings.dutyCyclePeriodSeconds], with [SensorCollectionMode.DEGRADED]
 *    doubling the idle window in power-save mode;
 *  - **the in-memory buffer** — a bounded queue of [SensorSampleEntry],
 *    flushed at [FLUSH_THRESHOLD] entries and on [stop];
 *  - **power-save degraded mode** — the [SensorGateway.isPowerSaveMode] probe maps to a
 *    sampling-period / batch-latency multiplier;
 *  - **the critical-battery stop** — before each active phase the battery level is
 *    checked; at or below [BATTERY_CRITICAL_THRESHOLD] the active phase is skipped, and a
 *    live battery callback ([onBatteryLevel]) stops in-flight active phases;
 *  - **sensor registration** — continuous (streaming) sensors are duty-cycled per sensor
 *    through [SensorGateway.registerContinuousSensor] (registered each active phase, torn
 *    down each idle window via [SensorGateway.unregisterContinuousSensor]); on-change /
 *    one-shot / special-trigger sensors are registered **once** at [start] through
 *    [SensorGateway.registerPersistentSensor] and stay armed across idle windows;
 *  - **max-report-latency** — `5_000_000us * multiplier`, preserved from the legacy code.
 *
 * Every collected [SensorSampleEntry] is written through the [SensorSampleWriter] seam
 * (production: the sanctioned `SensorSampleSink`; direct-boot: the DE-storage buffer)
 * (design §1C.2). The controller never touches `SensorSampleDao` directly.
 *
 * **Per-sensor collection gate (design §7, per-sensor consent redesign).** A
 * [collectionGate] predicate seam gates collection per sensor on its module's server-enable
 * + participant-acknowledgment state. It is consulted at the two points where collection
 * actually begins or persists: a sensor's duty cycle skips its active phase while that
 * sensor's gate is closed, and [flushBuffer] drops any buffered sample whose sensor's gate
 * is closed instead of writing it. Because the persistence chokepoint itself is gated, a
 * sensor cannot reach `sensor_samples` without acknowledgment no matter which path started
 * the service (the default `{ true }` keeps the controller usable unguarded in tests).
 *
 * Behaviour is preserved from the legacy `HardwareSensorService` except for two deliberate
 * fixes: (1) on-change / one-shot / special-trigger sensors are registered persistently at
 * [start] rather than duty-cycled (so their discrete events are not missed); and (2) each
 * sensor now duty-cycles at its own rate/period rather than one device-wide cadence.
 *
 * This is a plain class holding only its seams — no Android `Context`.
 *
 */
public class SensorRuntimeController(
    private val gateway: SensorGateway,
    private val settings: SensorRuntimeSettings,
    private val sink: SensorSampleWriter,
    private val scheduler: SensorRuntimeScheduler,
    private val collectionGate: (AndroidSensorType) -> Boolean = { true },
    private val collectionAdmission: () -> Boolean = { true },
    private val prepareCollection: () -> Boolean = collectionAdmission,
    private val sampleLease: (() -> Unit) -> Unit = { it() },
    private val retainOnStop: Boolean = false,
    private val observationAdmission: (AndroidSensorType) -> CollectionPersistenceGuard = { CollectionPersistenceGuard.ALLOW },
    private val clock: CollectionClock = CollectionClock.SYSTEM,
    private val log: CollectionLog = CollectionLog.LOGCAT,
    /** Redacted count of already-collected samples affected by a local loss path. */
    private val reportLoss: (String, Int) -> Unit = { _, _ -> },
    /** Direct-boot capacity refusal has already been counted in its encrypted journal. */
    private val zeroWrittenIsCountedLoss: Boolean = false,
) : SensorSampleWriter.Discardable {

    public companion object {
        /** Battery percent at or below which an active phase is skipped / stopped. */
        public const val BATTERY_CRITICAL_THRESHOLD: Int = 15

        /**
         * Battery percent at which collection may resume after a critical-battery stop.
         * Declared but not wired into resume logic — preserved from the legacy code.
         */
        public const val BATTERY_RESUME_THRESHOLD: Int = 20

        /** Buffer size at which an asynchronous flush is triggered. */
        public const val FLUSH_THRESHOLD: Int = 500

        /** Hard memory bound during storage failures (ten normal flush batches). */
        public const val MAX_BUFFERED_SAMPLES: Int = 5_000

        /** Delay before retrying a threshold-triggered flush after storage rejects it. */
        public const val STORAGE_RETRY_DELAY_SECONDS: Long = 5L

        /** Admission caches expire after thirty seconds, including while no sensors are armed. */
        public const val STORAGE_ADMISSION_RECHECK_SECONDS: Long = 30L

        /** Delay between bounded attempts to restore an always-armed sensor registration. */
        public const val PERSISTENT_RETRY_DELAY_SECONDS: Long = 5L

        /** Maximum automatic attempts after a persistent sensor registration is lost. */
        public const val MAX_PERSISTENT_RETRY_ATTEMPTS: Int = 3

        /** Base max-report-latency in microseconds (multiplied in degraded mode). */
        private const val BASE_MAX_REPORT_LATENCY_US = 5_000_000
    }

    private val buffer = ArrayBlockingQueue<SensorSampleEntry>(MAX_BUFFERED_SAMPLES)
    private val bufferLock = Any()
    // Acquire the persistence lease before this monitor, so a queued erasure cannot
    // deadlock against a flush or controller replacement waiting for the lease.
    private val flushLock = Any()
    private val inFlightIds = mutableSetOf<String>()
    private val queuedIds = mutableSetOf<String>()
    @Volatile private var stopping = false
    private var stopped = false
    private val discardGenerations = mutableMapOf<String, Long>()
    private val origins = ConcurrentHashMap<String, CollectionPersistenceGuard>()

    init {
        SensorSampleWriter.registerRuntime(this)
        if (retainOnStop) sampleLease {
            SensorSampleWriter.takeRetainedSamples().filter { it.origin.isCurrent() }.forEach {
                if (buffer.offer(it.entry)) { queuedIds.add(it.entry.id); origins[it.entry.id] = it.origin }
            }
        }
    }

    public override fun discardSensorSamples(sensorType: String): Int = synchronized(bufferLock) {
        discardGenerations[sensorType] = (discardGenerations[sensorType] ?: 0L) + 1L
        val before = buffer.size
        buffer.removeIf { entry ->
            (entry.sensorType == sensorType).also { if (it) { queuedIds.remove(entry.id); origins.remove(entry.id) } }
        }
        before - buffer.size
    }
    private val flushScheduled = AtomicBoolean(false)
    private val started = AtomicBoolean(false)
    private val storageAdmissionRecheckScheduled = AtomicBoolean(false)

    // The continuous sensors whose active phase is currently registered with the gateway.
    // Each sensor's duty-cycle loop adds/removes itself; thread-safe because the scheduler
    // thread and the battery-callback thread both mutate it.
    private val activeContinuous: MutableSet<AndroidSensorType> =
        ConcurrentHashMap.newKeySet()

    // Continuous sensors that currently have a running duty-cycle loop, and persistent
    // sensors currently armed. Tracked so [reconcile] schedules/arms only the newly
    // study-configured sensors without double-scheduling an existing loop or listener.
    private val scheduledContinuous: MutableSet<AndroidSensorType> =
        ConcurrentHashMap.newKeySet()
    private val armedPersistent: MutableSet<AndroidSensorType> =
        ConcurrentHashMap.newKeySet()
    private val persistentRetryScheduled: MutableSet<AndroidSensorType> =
        ConcurrentHashMap.newKeySet()
    private val persistentRetryAttempts = ConcurrentHashMap<AndroidSensorType, Int>()

    // ----- diagnostics state (redaction-safe operational telemetry, design §1B.3) -----
    @Volatile private var currentMode: SensorCollectionMode = SensorCollectionMode.NORMAL
    @Volatile private var lastFlushResult: ModuleResult = ModuleResult.Skipped("not yet run")
    @Volatile private var lastDestroyFlushFailedMessage: String? = null
    private val samplesFlushed = AtomicLong(0L)
    private val samplesDropped = AtomicLong(0L)
    private val shutdownLossReported = AtomicBoolean(false)
    /** Per-sample losses are counted here and reported once per flush, not once per sample. */
    private val pendingLoss = ConcurrentHashMap<String, AtomicLong>()

    /** Whether the runtime has been started and not yet stopped. */
    public val isStarted: Boolean get() = started.get()

    /** Whether any sensor's active duty-cycle phase is currently registered with the gateway. */
    public val isCollecting: Boolean get() = activeContinuous.isNotEmpty()

    /** Current collection mode (NORMAL / DEGRADED) of the last active phase. */
    public val mode: SensorCollectionMode get() = currentMode

    /** Number of samples currently held in the in-memory buffer. */
    public val bufferedCount: Int get() = buffer.size

    /** Total samples handed to the sink since [start]. */
    public val flushedCount: Long get() = samplesFlushed.get()

    /** Samples discarded because the bounded in-memory buffer was full. */
    public val droppedCount: Long get() = samplesDropped.get()

    /** Outcome label of the last buffer flush, for module diagnostics. */
    public val lastFlushLabel: String get() = lastFlushResult.label

    /** A redacted message if the most recent service-destroy flush failed, else `null`. */
    public val lastDestroyFlushFailed: String? get() = lastDestroyFlushFailedMessage

    /**
     * Starts collection. Idempotent: a second call while already started is a no-op.
     *
     * Arms each enabled persistent (on-change / trigger) sensor once — gated per sensor —
     * then launches an independent duty-cycle loop for each enabled continuous sensor at its
     * own rate/period. All of this runs on the [scheduler] thread (the gate reads Room, which
     * Room forbids on the main thread).
     */
    public fun start() {
        if (!started.compareAndSet(false, true)) {
            log.info(TAG, "Sensor runtime already started; ignoring duplicate start")
            return
        }
        shutdownLossReported.set(false)
        scheduler.execute { reconcileSafely() }
    }

    /**
     * Reconciles the running sensor set against the current configuration: arms a persistent
     * listener (or launches a duty-cycle loop) for every study-configured sensor not already
     * running. Called when consent or study settings change while the service is running
     * (a per-sensor Data Sharing toggle / settings sync) — without it, a sensor enabled after
     * [start] would never collect until a full service restart, because the service's
     * `onStartCommand` cannot rebuild the controller. Idempotent and gate-respecting; a sensor
     * the study no longer enables stops via the per-cycle gate and the per-sample flush filter.
     */
    public fun reconcile() {
        if (!started.get() || scheduler.isShutdown()) return
        try {
            scheduler.execute { reconcileSafely() }
        } catch (error: java.util.concurrent.RejectedExecutionException) {
            log.warn(TAG, "Sensor reconciliation deferred after scheduler shutdown", error)
        }
    }

    /**
     * Arms/schedules each currently study-configured sensor that is not already running.
     * Runs on the [scheduler] thread (the gate reads Room, forbidden on the main thread).
     * Idempotent: [scheduledContinuous] / [armedPersistent] guard against double-scheduling,
     * so re-running this (on [reconcile]) only picks up newly-enabled sensors.
     */
    private val persistentOrigins = java.util.concurrent.ConcurrentHashMap<AndroidSensorType, CollectionPersistenceGuard>()

    private fun reconcileSafely() {
        try { scheduleConfiguredSensors() }
        catch (error: Exception) {
            log.warn(TAG, "Sensor authorization refresh failed; retry scheduled", error)
            scheduleStorageAdmissionRecheck()
        }
    }

    private fun scheduleConfiguredSensors() {
        armedPersistent.toList().filter { !collectionGate(it) || persistentOrigins[it]?.isCurrent() != true }.forEach {
            gateway.unregisterPersistentSensor(it)
            armedPersistent.remove(it)
            persistentOrigins.remove(it)
        }
        for (sensorType in settings.enabledSensors()) {
            if (SensorTypeMapping.isContinuousSensor(sensorType)) {
                if (scheduledContinuous.add(sensorType)) scheduleCycle(sensorType)
            } else {
                armPersistentSensor(sensorType)
            }
        }
    }

    /**
     * Arms one enabled persistent (on-change / one-shot / special-trigger) sensor, gated.
     * Persistent sensors stay armed across duty-cycle idle windows so their discrete events
     * are captured; only [stop] unregisters them. Idempotent: a sensor already armed, or whose
     * gate is currently closed, is skipped — so [reconcile] arms only newly-consented sensors.
     */
    private fun armPersistentSensor(sensorType: AndroidSensorType) = sampleLease {
        if (sensorType in armedPersistent || sensorType in persistentRetryScheduled) return@sampleLease
        if (!started.get() || scheduler.isShutdown() || sensorType !in settings.enabledSensors()) {
            clearPersistentRetry(sensorType)
            return@sampleLease
        }
        if (!collectionGate(sensorType)) {
            clearPersistentRetry(sensorType)
            return@sampleLease
        }
        if (!prepareCollection()) {
            clearPersistentRetry(sensorType)
            scheduleStorageAdmissionRecheck()
            return@sampleLease
        }
        val origin = observationAdmission(sensorType)
        if (!origin.isCurrent()) return@sampleLease
        if (gateway.registerPersistentSensor(sensorType)) {
            persistentOrigins[sensorType] = origin
            armedPersistent.add(sensorType)
            clearPersistentRetry(sensorType)
            log.info(TAG, "Registered persistent (always-armed) listener for ${sensorType.name}")
        } else {
            log.warn(TAG, "Persistent sensor ${sensorType.name} registration failed")
            schedulePersistentRetry(sensorType)
        }
    }

    /**
     * Reconciles controller ownership after Android fails to re-arm a one-shot trigger.
     * The gateway has already discarded its failed registration at this point, so retaining
     * [armedPersistent] would otherwise suppress every future recovery attempt.
     */
    public fun onPersistentRegistrationLost(sensorType: AndroidSensorType) {
        if (!started.get() || scheduler.isShutdown()) return
        armedPersistent.remove(sensorType)
        persistentOrigins.remove(sensorType)
        try { scheduler.execute { schedulePersistentRetry(sensorType) } }
        catch (error: RuntimeException) { if (!scheduler.isShutdown()) log.warn(TAG, "Sensor recovery scheduling failed", error) }
    }

    private fun schedulePersistentRetry(sensorType: AndroidSensorType) {
        if (!started.get() || scheduler.isShutdown() || sensorType !in settings.enabledSensors()) return
        if (!persistentRetryScheduled.add(sensorType)) return

        val attempt = persistentRetryAttempts.compute(sensorType) { _, previous ->
            (previous ?: 0) + 1
        } ?: 1
        if (attempt > MAX_PERSISTENT_RETRY_ATTEMPTS) {
            persistentRetryScheduled.remove(sensorType)
            log.error(
                TAG,
                "Persistent sensor ${sensorType.name} remains unavailable after " +
                    "$MAX_PERSISTENT_RETRY_ATTEMPTS retry attempts",
            )
            return
        }

        try {
            scheduler.schedule(PERSISTENT_RETRY_DELAY_SECONDS) {
                persistentRetryScheduled.remove(sensorType)
                if (!started.get() || scheduler.isShutdown()) return@schedule
                armPersistentSensor(sensorType)
            }
        } catch (error: RuntimeException) {
            persistentRetryScheduled.remove(sensorType)
            if (!scheduler.isShutdown()) {
                log.warn(TAG, "Unable to schedule persistent sensor retry", error)
            }
        }
    }

    private fun clearPersistentRetry(sensorType: AndroidSensorType) {
        persistentRetryScheduled.remove(sensorType)
        persistentRetryAttempts.remove(sensorType)
    }

    private fun scheduleStorageAdmissionRecheck() {
        if (!storageAdmissionRecheckScheduled.compareAndSet(false, true)) return
        try {
            scheduler.schedule(STORAGE_ADMISSION_RECHECK_SECONDS) {
                storageAdmissionRecheckScheduled.set(false)
                if (started.get() && !scheduler.isShutdown()) reconcileSafely()
            }
        } catch (error: RuntimeException) {
            storageAdmissionRecheckScheduled.set(false)
            if (!scheduler.isShutdown()) log.warn(TAG, "Unable to schedule storage admission recheck", error)
        }
    }

    /**
     * Stops every duty cycle and drains the buffer to the sink. Collection is stopped first
     * (so an in-flight `onSensorChanged` cannot re-fill the buffer mid-drain), then the
     * buffer is flushed.
     */
    public fun stop(isServiceDestroy: Boolean = false,
                    retainedOrigin: ((SensorSampleEntry) -> CollectionPersistenceGuard)? = null) {
        stopping = true
        sampleLease {
            synchronized(flushLock) {
                if (!stopped) stopUnderLease(isServiceDestroy, retainedOrigin)
            }
        }
    }

    private fun stopUnderLease(isServiceDestroy: Boolean,
                               retainedOrigin: ((SensorSampleEntry) -> CollectionPersistenceGuard)?) {
        scheduler.shutdown()
        activeContinuous.clear()
        scheduledContinuous.clear()
        armedPersistent.clear()
        persistentRetryScheduled.clear()
        persistentRetryAttempts.clear()
        storageAdmissionRecheckScheduled.set(false)
        gateway.unregisterAll()
        val result = flushBuffer()
        reportPendingLoss()
        if (isServiceDestroy && result is ModuleResult.Failed) {
            lastDestroyFlushFailedMessage = result.redactedMessage
            log.error(TAG, "Service-destroy flush failed; samples may be lost", result.error)
        }
        if (isServiceDestroy && (retainOnStop || retainedOrigin != null)) sampleLease {
            val accepted = buffer.toList().filter { origins[it.id]?.isCurrent() == true }
            val retained = accepted.map { SensorSampleWriter.RetainedSample(it,
                retainedOrigin?.invoke(it) ?: checkNotNull(origins[it.id])) }.filter { it.origin.isCurrent() }
            val stored = SensorSampleWriter.retainSamples(retained)
            synchronized(bufferLock) {
                retained.take(stored).forEach { buffer.remove(it.entry); queuedIds.remove(it.entry.id); origins.remove(it.entry.id) }
                accepted.filter { entry -> retained.none { it.entry.id == entry.id } }.forEach {
                    buffer.remove(it); queuedIds.remove(it.id); origins.remove(it.id)
                }
            }
        }
        if (isServiceDestroy && buffer.isNotEmpty() && shutdownLossReported.compareAndSet(false, true)) {
            reportLoss("LOCAL_SHUTDOWN_DROPPED", buffer.size)
        }
        started.set(false)
        stopped = true
    }

    /** Records that a service-destroy flush failed outside the [ModuleResult] contract. */
    public fun recordDestroyFlushFailure(message: String) {
        lastDestroyFlushFailedMessage = message
        log.error(TAG, "Service-destroy flush failure recorded: $message")
        if (buffer.isNotEmpty() && shutdownLossReported.compareAndSet(false, true)) {
            reportLoss("LOCAL_SHUTDOWN_DROPPED", buffer.size)
        }
    }

    // ----- per-sensor duty cycle -----

    private fun scheduleCycle(sensorType: AndroidSensorType) {
        if (scheduler.isShutdown()) return

        val mode = if (gateway.isPowerSaveMode()) SensorCollectionMode.DEGRADED else SensorCollectionMode.NORMAL
        currentMode = mode

        val activeSeconds = settings.dutyCycleActiveSeconds(sensorType).toLong()
        val periodSeconds = settings.dutyCyclePeriodSeconds(sensorType).toLong()
        val idleSeconds = (periodSeconds - activeSeconds) * mode.multiplier

        log.info(TAG, "Duty cycle ${sensorType.name} ($mode): ${activeSeconds}s active / ${idleSeconds}s idle")

        val admitted = try { collectionGate(sensorType) && prepareCollection() && shouldCollect() }
        catch (error: Exception) {
            log.warn(TAG, "Sensor admission failed; active phase will retry", error)
            scheduler.schedule(STORAGE_RETRY_DELAY_SECONDS) { if (!scheduler.isShutdown()) scheduleCycle(sensorType) }
            return
        }
        if (admitted) {
            startCollecting(sensorType, mode)
            scheduler.schedule(activeSeconds) {
                stopCollecting(sensorType)
                flushBuffer()
                scheduler.schedule(idleSeconds) {
                    if (!scheduler.isShutdown()) scheduleCycle(sensorType)
                }
            }
        } else {
            // Skip this sensor's active phase; re-check after its idle window.
            scheduler.schedule(idleSeconds) {
                if (!scheduler.isShutdown()) scheduleCycle(sensorType)
            }
        }
    }

    /**
     * Whether collection may run: `false` at or below the critical battery threshold.
     * A negative reading (battery level unavailable) is treated as collectable.
     */
    private fun shouldCollect(): Boolean {
        val level = gateway.batteryLevelPercent()
        if (level in 0..BATTERY_CRITICAL_THRESHOLD) {
            log.warn(TAG, "Battery at $level% (<= $BATTERY_CRITICAL_THRESHOLD%), skipping collection")
            return false
        }
        return true
    }

    private fun startCollecting(sensorType: AndroidSensorType, mode: SensorCollectionMode) {
        if (!activeContinuous.add(sensorType)) return

        val multiplier = mode.multiplier
        val rateHz = settings.samplingRateHz(sensorType)
        if (rateHz !in 1..CollectionModuleSetting.MAX_SENSOR_SAMPLING_RATE_HZ) {
            activeContinuous.remove(sensorType)
            log.error(
                TAG,
                "Refusing invalid sampling rate for ${sensorType.name}; sensor remains stopped",
            )
            return
        }
        // Preserve legacy arithmetic exactly: integer division, then multiply by mode.
        val samplingPeriodUs = (1_000_000 / rateHz) * multiplier
        val maxReportLatencyUs = BASE_MAX_REPORT_LATENCY_US * multiplier

        if (gateway.registerContinuousSensor(sensorType, samplingPeriodUs, maxReportLatencyUs)) {
            log.info(
                TAG,
                "Registered continuous listener for ${sensorType.name} " +
                    "($mode, samplingPeriod=${samplingPeriodUs}us, " +
                    "requestedBatchLatency=${maxReportLatencyUs}us)",
            )
        } else {
            activeContinuous.remove(sensorType)
            log.warn(TAG, "Sensor ${sensorType.name} not available on this device")
        }
    }

    private fun stopCollecting(sensorType: AndroidSensorType) {
        if (!activeContinuous.remove(sensorType)) return
        gateway.unregisterContinuousSensor(sensorType)
        log.info(TAG, "Unregistered continuous (duty-cycled) listener for ${sensorType.name}")
    }

    /**
     * Stops every in-flight active phase when a live battery broadcast reports a level at or
     * below [BATTERY_CRITICAL_THRESHOLD].
     */
    public fun onBatteryLevel(percent: Int) {
        if (percent <= BATTERY_CRITICAL_THRESHOLD && activeContinuous.isNotEmpty()) {
            log.warn(TAG, "Battery at $percent% (<= $BATTERY_CRITICAL_THRESHOLD%), stopping collection")
            activeContinuous.toList().forEach { stopCollecting(it) }
        }
    }

    // ----- sample recording -----

    /**
     * Records a sensor sample into the buffer. When the buffer reaches [FLUSH_THRESHOLD] an
     * asynchronous flush is scheduled, exactly as the legacy `recordSample`.
     */
    public fun recordSample(
        sensorType: AndroidSensorType,
        values: FloatArray,
        accuracy: Int?,
        timestamp: OffsetDateTime = OffsetDateTime.now(),
        origin: CollectionPersistenceGuard = observationAdmission(sensorType),
    ) {
        if (stopping || !collectionGate(sensorType) || !origin.isCurrent()) return
        val generation = synchronized(bufferLock) { discardGenerations[sensorType.name] ?: 0L }
        if (!collectionAdmission()) {
            // Storage pause: the pause episode itself is the recorded diagnostic.
            stopCollecting(sensorType)
            return
        }
        val offered = synchronized(bufferLock) {
            if (stopping || (discardGenerations[sensorType.name] ?: 0L) != generation) return
            val entry = toEntry(sensorType, values, accuracy, timestamp)
            (buffer.size + inFlightIds.size < MAX_BUFFERED_SAMPLES && buffer.offer(entry))
                .also { if (it) { queuedIds.add(entry.id); origins[entry.id] = origin } }

        }
        if (!offered) {
            val dropped = samplesDropped.incrementAndGet()
            countLoss("LOCAL_BUFFER_OVERFLOW", 1)
            if (dropped == 1L || dropped % FLUSH_THRESHOLD == 0L) {
                log.error(TAG, "Sensor sample buffer full; dropped $dropped sample(s) to protect app memory")
            }
            return
        }
        val depth = buffer.size
        if (depth >= FLUSH_THRESHOLD) scheduleFlush()
    }

    /**
     * Schedules one drain at a time. If producers refill the queue past the threshold while a
     * drain is in flight, the completion path schedules the next drain instead of stranding a
     * full batch until some unrelated future sample happens to arrive.
     */
    private fun scheduleFlush() {
        if (scheduler.isShutdown()) return
        if (!flushScheduled.compareAndSet(false, true)) return
        try {
            scheduler.execute(::runScheduledFlush)
        } catch (error: RuntimeException) {
            flushScheduled.set(false)
            if (!scheduler.isShutdown()) {
                log.warn(TAG, "Unable to schedule sensor buffer flush", error)
            }
        }
    }

    private fun runScheduledFlush() {
        var retryAfterDelay = false
        try {
            val result = flushBuffer()
            retryAfterDelay =
                buffer.isNotEmpty() &&
                    (result is ModuleResult.Failed || result is ModuleResult.Retry) &&
                    !scheduler.isShutdown()
        } finally {
            if (retryAfterDelay) {
                // Keep flushScheduled claimed for the entire backoff. New samples can fill the
                // queue, but cannot bypass the single delayed storage retry.
                try {
                    scheduler.schedule(STORAGE_RETRY_DELAY_SECONDS) {
                        if (scheduler.isShutdown()) {
                            flushScheduled.set(false)
                        } else {
                            runScheduledFlush()
                        }
                    }
                } catch (error: RuntimeException) {
                    flushScheduled.set(false)
                    if (!scheduler.isShutdown()) {
                        log.warn(TAG, "Unable to schedule sensor storage retry", error)
                    }
                }
            } else {
                flushScheduled.set(false)
                if (buffer.size >= FLUSH_THRESHOLD && !scheduler.isShutdown()) {
                    // Producers refilled the queue while a successful drain was in flight.
                    scheduleFlush()
                }
            }
        }
    }

    private fun toEntry(
        sensorType: AndroidSensorType,
        values: FloatArray,
        accuracy: Int?,
        timestamp: OffsetDateTime,
    ): SensorSampleEntry {
        val valueCount = SensorTypeMapping.valueCount(sensorType)
        val rawValues = values.toList()
        val legacyValues = rawValues.take(valueCount)
        return SensorSampleEntry(
            id = UUID.randomUUID().toString(),
            sensorType = sensorType.name,
            timestamp = timestamp.toString(),
            timezone = ZoneId.systemDefault().id,
            x = legacyValues.getOrNull(0),
            y = legacyValues.getOrNull(1),
            z = legacyValues.getOrNull(2),
            w = legacyValues.getOrNull(3),
            accuracy = accuracy,
            valuesJson = rawValues.joinToString(prefix = "[", postfix = "]"),
        )
    }

    /**
     * Drains the buffer and writes it through the [SensorSampleWriter], **gating each sample by its
     * own sensor's collection gate**. Accepted samples remain buffered during temporary
     * closure; retired enrollment/erasure tokens discard them. Failed writes requeue the
     * still-authorized samples for the next flush.
     */
    public fun flushBuffer(): ModuleResult {
        var result: ModuleResult = ModuleResult.Retry("persistence lease unavailable")
        try {
            sampleLease {
                synchronized(flushLock) {
                    result = if (stopped) ModuleResult.Skipped("sensor runtime stopped") else flushUnderLease()
                }
            }
        }
        catch (error: Exception) { result = ModuleResult.Failed(error, redactedMessage = "sensor validation unavailable") }
        lastFlushResult = result
        return result
    }

    private fun flushUnderLease(): ModuleResult {
        reportPendingLoss()
        val drained = mutableListOf<SensorSampleEntry>()
        val generations = synchronized(bufferLock) {
            buffer.drainTo(drained)
            queuedIds.clear()
            inFlightIds.addAll(drained.map { it.id })
            discardGenerations.toMap()
        }
        val validated = java.util.IdentityHashMap<CollectionPersistenceGuard, Boolean>()
        val isCurrent: (SensorSampleEntry) -> Boolean = { entry ->
            val sameGeneration = synchronized(bufferLock) {
                (discardGenerations[entry.sensorType] ?: 0L) == (generations[entry.sensorType] ?: 0L)
            }
            sameGeneration && origins[entry.id]?.let { origin ->
                validated.getOrPut(origin) { origin.validate() }
            } == true
        }
        if (drained.isEmpty()) {
            lastFlushResult = ModuleResult.Ok(0)
            return lastFlushResult
        }

        try {
        val gateDecisionBySensor = mutableMapOf<String, Boolean>()
        val (current, erased) = drained.partition(isCurrent)
        erased.forEach { origins.remove(it.id) }
        val (keep, paused) = current.partition { entry ->
            gateDecisionBySensor.getOrPut(entry.sensorType) {
                val type = runCatching { AndroidSensorType.valueOf(entry.sensorType) }.getOrNull()
                type != null && collectionGate(type)
            }
        }
        if (paused.isNotEmpty()) requeueAfterFailedFlush(paused, isCurrent)
        if (keep.isEmpty()) {
            lastFlushResult = if (paused.isNotEmpty()) ModuleResult.Retry("accepted samples retained while collection is paused")
                else ModuleResult.Skipped("observation authorization retired")
            return lastFlushResult
        }

        log.info(TAG, "Flushing ${keep.size} sensor samples to sensor_samples")
        val result = sink.writeCurrent(keep, isCurrent)
        when (result) {
            is ModuleResult.Ok -> {
                samplesFlushed.addAndGet(result.items.toLong())
                keep.forEach { origins.remove(it.id) }
                // The sink refused the rest on purpose (persistence gate, or a full direct-boot
                // buffer that already journaled its own loss); requeueing them would loop forever.
                val refused = keep.size - result.items
                if (refused > 0) {
                    samplesDropped.addAndGet(refused.toLong())
                    if (!zeroWrittenIsCountedLoss) reportLoss("COLLECTION_GATE_DROPPED", refused)
                }
            }
            is ModuleResult.Failed -> {
                log.error(TAG, "Failed to flush ${keep.size} sensor samples, re-queuing for retry", result.error)
                requeueAfterFailedFlush(keep, isCurrent)
            }
            is ModuleResult.Retry -> requeueAfterFailedFlush(keep, isCurrent)
            is ModuleResult.Skipped -> {
                val retained = keep.filter(isCurrent)
                if (retained.isNotEmpty()) requeueAfterFailedFlush(retained, isCurrent)
                keep.filterNot(isCurrent).forEach { origins.remove(it.id) }
            }
        }
        lastFlushResult = result
        return result
        } catch (error: Exception) {
            // Ownership remains with this batch until validation and persistence finish.
            // Uncertain entries retry; only a known erasure generation may discard them.
            synchronized(bufferLock) {
                drained.filter { origins.containsKey(it.id) }.forEach { entry ->
                    if ((discardGenerations[entry.sensorType] ?: 0L) == (generations[entry.sensorType] ?: 0L)) {
                        if (queuedIds.add(entry.id) && !buffer.offer(entry)) {
                queuedIds.remove(entry.id)
                            samplesDropped.incrementAndGet()
                            countLoss("LOCAL_REQUEUE_OVERFLOW", 1)
                            origins.remove(entry.id)
                        }
                    } else origins.remove(entry.id)
                    inFlightIds.remove(entry.id)
                }
            }
            return ModuleResult.Failed(error, redactedMessage = "sensor validation failed: ${error.javaClass.simpleName}")
        } finally {
            synchronized(bufferLock) { inFlightIds.removeAll(drained.map { it.id }.toSet()) }
        }
    }

    private fun countLoss(code: String, count: Long) {
        pendingLoss.getOrPut(code) { AtomicLong() }.addAndGet(count)
    }

    private fun reportPendingLoss() {
        pendingLoss.forEach { (code, counter) ->
            val count = counter.getAndSet(0L)
            if (count > 0) reportLoss(code, count.coerceAtMost(Int.MAX_VALUE.toLong()).toInt())
        }
    }

    private fun requeueAfterFailedFlush(entries: List<SensorSampleEntry>, isCurrent: (SensorSampleEntry) -> Boolean) = sampleLease {
        val admitted = entries.filter(isCurrent)
        synchronized(bufferLock) {
        admitted.forEach { entry ->
            if (queuedIds.add(entry.id) && !buffer.offer(entry)) {
                queuedIds.remove(entry.id)
                samplesDropped.incrementAndGet()
                countLoss("LOCAL_REQUEUE_OVERFLOW", 1)
                origins.remove(entry.id)
            }
            inFlightIds.remove(entry.id)
        }
        }
    }
}
