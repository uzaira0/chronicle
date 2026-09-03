package com.openlattice.chronicle.collection.sensors

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.hardware.TriggerEvent
import android.hardware.TriggerEventListener
import android.os.BatteryManager
import android.os.PowerManager
import android.os.SystemClock
import android.util.Log
import com.openlattice.chronicle.android.AndroidSensorType
import com.openlattice.chronicle.sensors.SensorTypeMapping
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneId
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap

/**
 * The single seam over the Android sensor/battery/power-save runtime (refactor plan §9.1
 * guardrail 2 — "direct `SensorManager` access must live in the sensor runtime/service
 * package only").
 *
 * [SensorRuntimeController] holds the duty-cycle, buffer, power-save and critical-battery
 * logic but never touches `SensorManager`, `BatteryManager` or `PowerManager` directly —
 * it drives them through this interface. Production wires [AndroidSensorGateway]; JVM
 * unit tests supply a fake gateway so the controller's behaviour (duty cycle, degraded
 * mode, critical-battery stop, buffer flush) is provable without a device.
 *
 * The gateway exposes only the operations the controller needs and reports outcomes as
 * plain values — it has no knowledge of [SensorSampleEntry], the buffer, the duty cycle,
 * or the sink. That keeps the runtime logic in the controller and the Android coupling
 * here.
 *
 */
public interface SensorGateway {

    /** Callback surface for samples emitted while a continuous sensor is registered. */
    public interface SampleListener {
        /**
         * A continuous-sensor sample. [values] is the raw `SensorEvent.values` snapshot;
         * [accuracy] is the `SensorEvent.accuracy`.
         */
        public fun onSample(
            sensorType: AndroidSensorType,
            values: FloatArray,
            accuracy: Int,
            timestamp: OffsetDateTime,
        )

        /** A one-shot trigger-sensor sample. Trigger sensors carry no accuracy. */
        public fun onTrigger(
            sensorType: AndroidSensorType,
            values: FloatArray,
            timestamp: OffsetDateTime,
        )

        /**
         * Reports that a persistent one-shot registration could not be restored after its
         * callback. The controller uses this to relinquish stale ownership and retry.
         */
        public fun onPersistentRegistrationLost(sensorType: AndroidSensorType) {}
    }

    /** Whether the device is currently in power-save mode (drives the degraded mode). */
    public fun isPowerSaveMode(): Boolean

    /**
     * Current battery level percent `0..100`, or a negative value if it cannot be read.
     * Used for the critical-battery stop check before each duty-cycle active phase.
     */
    public fun batteryLevelPercent(): Int

    /**
     * Registers [sensorType] for continuous collection at [samplingPeriodUs] with
     * [maxReportLatencyUs] as the requested upper bound for batch latency. The gateway may
     * reduce or disable batching when guaranteed FIFO capacity or flush-completion ownership
     * is insufficient. Returns `true` if the sensor exists on this device and the listener
     * was registered, `false` if the sensor is unavailable.
     */
    public fun registerContinuousSensor(
        sensorType: AndroidSensorType,
        samplingPeriodUs: Int,
        maxReportLatencyUs: Int,
    ): Boolean

    /**
     * Registers [sensorType] as a **persistent** (always-armed) sensor that stays
     * registered across duty-cycle idle windows — for on-change, one-shot and
     * special-trigger sensors, which emit only on a discrete physical event and would lose
     * almost every event if duty-cycled. The gateway routes one-shot trigger sensors
     * (`SensorTypeMapping.isTriggerSensor`) through `requestTriggerSensor` and the rest
     * through a persistent `registerListener`. Returns `true` if the sensor exists on this
     * device and was armed, `false` if it is unavailable.
     */
    public fun registerPersistentSensor(sensorType: AndroidSensorType): Boolean

    /**
     * Unregisters only the **continuous** (duty-cycled) listeners, leaving persistent
     * sensors armed. Called at the end of each duty-cycle active phase so streaming sensors
     * stop while event sensors keep listening.
     */
    public fun unregisterContinuous()

    /**
     * Unregisters a single **continuous** sensor's listener, leaving every other continuous
     * and persistent sensor untouched. Each sensor now runs its own per-sensor duty cycle
     * (per-sensor consent redesign, 2026-06-11), so the active phase of one sensor must be
     * able to end without disturbing the others. A no-op if [sensorType] is not registered.
     */
    public fun unregisterContinuousSensor(sensorType: AndroidSensorType)

    /**
     * Unregisters everything — continuous listeners, persistent listeners and trigger
     * sensors. Called on full stop (service destroy / critical battery shutdown).
     */
    public fun unregisterAll()
}

/**
 * Production [SensorGateway] backed by the Android [SensorManager] / [BatteryManager] /
 * [PowerManager]. This class is the only place in the sensor runtime package that
 * dereferences `SensorManager`.
 *
 * It is constructed per [SensorRuntimeController] (which is constructed per
 * [com.openlattice.chronicle.services.sensors.HardwareSensorService]); it holds the
 * application [Context]-derived system services for the lifetime of the service only,
 * never as a process-wide singleton.
 */
public class AndroidSensorGateway(
    context: Context,
    private val listener: SensorGateway.SampleListener,
) : SensorGateway {

    private val appContext = context.applicationContext
    private val sensorManager = appContext.getSystemService(Context.SENSOR_SERVICE) as SensorManager

    /** Builds a [SensorEventListener] that forwards each event to the sink as a sample. */
    private fun sampleListener(throttleContinuous: Boolean) = object : SensorEventListener {
        override fun onSensorChanged(event: SensorEvent) {
            val sensorType = SensorTypeMapping.fromAndroidType(event.sensor.type) ?: return
            if (throttleContinuous) {
                continuousRawCallbacks.merge(sensorType, 1L, Long::plus)
                if (shouldDropContinuous(sensorType, event.timestamp)) return
                continuousRetainedSamples.merge(sensorType, 1L, Long::plus)
            }
            listener.onSample(
                sensorType,
                event.values.copyOf(),
                event.accuracy,
                wallClockTimestamp(event.timestamp),
            )
        }

        override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}
    }

    // Two distinct listeners so the duty cycle can tear down the continuous (streaming)
    // sensors at the end of each active phase while the persistent (on-change) sensors stay
    // armed — `unregisterListener(listener)` removes a listener from *all* its sensors, so a
    // shared listener could not be torn down selectively.
    private val continuousListener = sampleListener(throttleContinuous = true)
    private val persistentListener = sampleListener(throttleContinuous = false)

    private val triggerLock = Any()
    private val triggerListeners = mutableMapOf<Sensor, TriggerEventListener>()
    private val continuousSamplingPeriodsNanos = ConcurrentHashMap<AndroidSensorType, Long>()
    private val continuousNextDeadlineNanos = ConcurrentHashMap<AndroidSensorType, Long>()
    private val continuousStartedNanos = ConcurrentHashMap<AndroidSensorType, Long>()
    private val continuousRawCallbacks = ConcurrentHashMap<AndroidSensorType, Long>()
    private val continuousRetainedSamples = ConcurrentHashMap<AndroidSensorType, Long>()

    override fun isPowerSaveMode(): Boolean {
        val pm = appContext.getSystemService(Context.POWER_SERVICE) as PowerManager
        return pm.isPowerSaveMode
    }

    override fun batteryLevelPercent(): Int {
        val bm = appContext.getSystemService(Context.BATTERY_SERVICE) as BatteryManager
        return bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
    }

    override fun registerContinuousSensor(
        sensorType: AndroidSensorType,
        samplingPeriodUs: Int,
        maxReportLatencyUs: Int,
    ): Boolean {
        val androidType = SensorTypeMapping.toAndroidType(sensorType)
        val sensor = sensorManager.getDefaultSensor(androidType) ?: return false
        val effectiveReportLatencyUs = dutyCycledReportLatencyUs(
            requestedLatencyUs = maxReportLatencyUs,
            samplingPeriodUs = samplingPeriodUs,
            fifoReservedEventCount = sensor.fifoReservedEventCount,
            canAwaitFlushCompletion = false,
        )
        continuousSamplingPeriodsNanos[sensorType] = samplingPeriodUs.toLong().coerceAtLeast(0L) * 1_000L
        continuousNextDeadlineNanos.remove(sensorType)
        continuousStartedNanos[sensorType] = SystemClock.elapsedRealtimeNanos()
        continuousRawCallbacks[sensorType] = 0L
        continuousRetainedSamples[sensorType] = 0L
        if (!sensorManager.registerListener(
                continuousListener,
                sensor,
                samplingPeriodUs,
                effectiveReportLatencyUs,
            )
        ) {
            clearContinuousMetrics(sensorType)
            return false
        }
        return true
    }

    override fun registerPersistentSensor(sensorType: AndroidSensorType): Boolean {
        return if (SensorTypeMapping.isTriggerSensor(sensorType)) {
            registerTriggerSensor(sensorType)
        } else {
            registerOnChangeSensor(sensorType)
        }
    }

    /**
     * On-change / special-trigger sensors (e.g. tiltDetector, light, proximity, screen
     * orientation, the Samsung vendor sensors): registered with the persistent listener and
     * left armed across duty-cycle idle windows. The reporting rate is hardware-driven (on
     * change), so [SensorManager.SENSOR_DELAY_NORMAL] is advisory.
     */
    private fun registerOnChangeSensor(sensorType: AndroidSensorType): Boolean {
        val androidType = SensorTypeMapping.toAndroidType(sensorType)
        val sensor = sensorManager.getDefaultSensor(androidType) ?: return false
        if (!sensorManager.registerListener(persistentListener, sensor, SensorManager.SENSOR_DELAY_NORMAL)) {
            return false
        }
        return true
    }

    /** One-shot trigger sensors (significantMotion): armed via requestTriggerSensor, self-re-arming. */
    private fun registerTriggerSensor(sensorType: AndroidSensorType): Boolean {
        val androidType = SensorTypeMapping.toAndroidType(sensorType)
        val sensor = sensorManager.getDefaultSensor(androidType) ?: return false
        lateinit var triggerListener: TriggerEventListener
        triggerListener = object : TriggerEventListener() {
            override fun onTrigger(event: TriggerEvent) {
                listener.onTrigger(sensorType, event.values.copyOf(), wallClockTimestamp(event.timestamp))
                // Serialize ownership with unregisterAll: either this listener is still owned
                // and re-arms, or teardown has removed ownership and it must stay stopped.
                val rearmFailed = synchronized(triggerLock) {
                    if (triggerListeners[sensor] !== this) {
                        false
                    } else if (sensorManager.requestTriggerSensor(this, sensor)) {
                        false
                    } else {
                        triggerListeners.remove(sensor)
                        true
                    }
                }
                if (rearmFailed) {
                    Log.w(TAG, "Failed to re-arm trigger sensor ${sensorType.name}")
                    listener.onPersistentRegistrationLost(sensorType)
                }
            }
        }
        return synchronized(triggerLock) {
            if (sensorManager.requestTriggerSensor(triggerListener, sensor)) {
                triggerListeners[sensor] = triggerListener
                true
            } else {
                false
            }
        }
    }

    override fun unregisterContinuous() {
        continuousSamplingPeriodsNanos.keys.toList().forEach(::logRealizedRate)
        sensorManager.unregisterListener(continuousListener)
        continuousSamplingPeriodsNanos.clear()
        continuousNextDeadlineNanos.clear()
        continuousStartedNanos.clear()
        continuousRawCallbacks.clear()
        continuousRetainedSamples.clear()
    }

    override fun unregisterContinuousSensor(sensorType: AndroidSensorType) {
        val androidType = SensorTypeMapping.toAndroidType(sensorType)
        val sensor = sensorManager.getDefaultSensor(androidType)
        // The two-arg overload removes continuousListener from this sensor only, leaving its
        // registration for every other continuous sensor intact.
        if (sensor != null) sensorManager.unregisterListener(continuousListener, sensor)
        logRealizedRate(sensorType)
        clearContinuousMetrics(sensorType)
    }

    override fun unregisterAll() {
        continuousSamplingPeriodsNanos.keys.toList().forEach(::logRealizedRate)
        sensorManager.unregisterListener(continuousListener)
        sensorManager.unregisterListener(persistentListener)
        val triggersToCancel = synchronized(triggerLock) {
            triggerListeners.entries.map { it.key to it.value }.also { triggerListeners.clear() }
        }
        triggersToCancel.forEach { (sensor, triggerListener) ->
            sensorManager.cancelTriggerSensor(triggerListener, sensor)
        }
        continuousSamplingPeriodsNanos.clear()
        continuousNextDeadlineNanos.clear()
        continuousStartedNanos.clear()
        continuousRawCallbacks.clear()
        continuousRetainedSamples.clear()
    }

    private fun shouldDropContinuous(sensorType: AndroidSensorType, eventTimestampNanos: Long): Boolean {
        val minPeriodNanos = continuousSamplingPeriodsNanos[sensorType] ?: return false
        val decision = continuousThrottleDecision(
            eventTimestampNanos = eventTimestampNanos,
            minPeriodNanos = minPeriodNanos,
            nextDeadlineNanos = continuousNextDeadlineNanos[sensorType],
        )
        decision.nextDeadlineNanos?.let { continuousNextDeadlineNanos[sensorType] = it }
        return !decision.retain
    }

    private fun logRealizedRate(sensorType: AndroidSensorType) {
        val started = continuousStartedNanos[sensorType] ?: return
        val durationNanos = (SystemClock.elapsedRealtimeNanos() - started).coerceAtLeast(1L)
        val rawCallbacks = continuousRawCallbacks[sensorType] ?: 0L
        val retainedSamples = continuousRetainedSamples[sensorType] ?: 0L
        val requestedPeriodNanos = continuousSamplingPeriodsNanos[sensorType] ?: return
        val requestedHz = if (requestedPeriodNanos > 0L) 1_000_000_000.0 / requestedPeriodNanos else 0.0
        val rawHz = rawCallbacks * 1_000_000_000.0 / durationNanos
        val retainedHz = retainedSamples * 1_000_000_000.0 / durationNanos
        Log.i(
            TAG,
            "Sensor ${sensorType.name} requestedHz=${"%.2f".format(Locale.US, requestedHz)} " +
                "rawCallbackHz=${"%.2f".format(Locale.US, rawHz)} " +
                "retainedHz=${"%.2f".format(Locale.US, retainedHz)} " +
                "rawCallbacks=$rawCallbacks retainedSamples=$retainedSamples",
        )
    }

    private fun clearContinuousMetrics(sensorType: AndroidSensorType) {
        continuousSamplingPeriodsNanos.remove(sensorType)
        continuousNextDeadlineNanos.remove(sensorType)
        continuousStartedNanos.remove(sensorType)
        continuousRawCallbacks.remove(sensorType)
        continuousRetainedSamples.remove(sensorType)
    }

    private fun wallClockTimestamp(eventTimestampNanos: Long): OffsetDateTime {
        val elapsedNowNanos = SystemClock.elapsedRealtimeNanos()
        val ageNanos = (elapsedNowNanos - eventTimestampNanos).coerceAtLeast(0L)
        val sampleInstant = Instant.now().minusNanos(ageNanos)
        return OffsetDateTime.ofInstant(sampleInstant, ZoneId.systemDefault())
    }

    private companion object {
        const val TAG = "AndroidSensorGateway"
    }
}

/** Result of applying one callback timestamp to the continuous-sensor phase accumulator. */
internal data class ContinuousThrottleDecision(
    val retain: Boolean,
    val nextDeadlineNanos: Long?,
)

/**
 * Retains callbacks against a fixed requested-rate phase instead of measuring a full period
 * from the last retained callback. The latter ceil-divides a slightly faster raw cadence
 * (for example 6.25 Hz against a requested 5 Hz) and undershoots to roughly 3.13 Hz. This
 * accumulator advances the deadline along its original phase, so early callbacks are dropped
 * without moving the phase and the long-run retained cadence converges on the request.
 *
 * A gap retains only the first callback after the deadline, then advances directly to the
 * first phase deadline after that callback. It never emits a catch-up burst. A `null` deadline
 * is a fresh registration and therefore retains its first callback.
 */
internal fun continuousThrottleDecision(
    eventTimestampNanos: Long,
    minPeriodNanos: Long,
    nextDeadlineNanos: Long?,
): ContinuousThrottleDecision {
    if (minPeriodNanos <= 0L) return ContinuousThrottleDecision(retain = true, nextDeadlineNanos = null)
    if (nextDeadlineNanos != null && eventTimestampNanos < nextDeadlineNanos) {
        return ContinuousThrottleDecision(retain = false, nextDeadlineNanos = nextDeadlineNanos)
    }

    val anchor = nextDeadlineNanos ?: eventTimestampNanos
    val periodsElapsed = (eventTimestampNanos - anchor) / minPeriodNanos
    val nextDeadline = saturatingPhaseAdvance(
        anchorNanos = anchor,
        periods = periodsElapsed + 1L,
        periodNanos = minPeriodNanos,
    )
    return ContinuousThrottleDecision(retain = true, nextDeadlineNanos = nextDeadline)
}

private fun saturatingPhaseAdvance(anchorNanos: Long, periods: Long, periodNanos: Long): Long {
    if (periods <= 0L || anchorNanos > Long.MAX_VALUE - periodNanos) return Long.MAX_VALUE
    val maxPeriods = (Long.MAX_VALUE - anchorNanos) / periodNanos
    return if (periods > maxPeriods) Long.MAX_VALUE else anchorNanos + periods * periodNanos
}

/**
 * Resolves a safe Android batch latency for a duty-cycled registration. Only reserved FIFO
 * capacity is guaranteed to this application; maximum capacity may be shared with other
 * clients. More importantly, a duty-cycle boundary must await `SensorEventListener2.flush`
 * completion before unregistering or residual batched events can be discarded. Chronicle
 * does not yet own that asynchronous completion lifecycle, so production passes
 * [canAwaitFlushCompletion] as `false` and uses immediate delivery. The capacity calculation
 * remains explicit for a future listener that can prove flush completion.
 */
internal fun dutyCycledReportLatencyUs(
    requestedLatencyUs: Int,
    samplingPeriodUs: Int,
    fifoReservedEventCount: Int,
    canAwaitFlushCompletion: Boolean,
): Int {
    if (!canAwaitFlushCompletion) return 0
    if (requestedLatencyUs <= 0 || samplingPeriodUs <= 0 || fifoReservedEventCount <= 0) return 0
    val fifoCapacityUs = samplingPeriodUs.toLong() * fifoReservedEventCount.toLong()
    return minOf(requestedLatencyUs.toLong(), fifoCapacityUs, Int.MAX_VALUE.toLong()).toInt()
}
