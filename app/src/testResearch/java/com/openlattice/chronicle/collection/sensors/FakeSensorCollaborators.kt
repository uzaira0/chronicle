package com.openlattice.chronicle.collection.sensors

import com.openlattice.chronicle.android.AndroidSensorSetting
import com.openlattice.chronicle.android.AndroidSensorType
import com.openlattice.chronicle.collection.settings.LegacySensorSettingSource
import com.openlattice.chronicle.sensors.SensorTypeMapping
import java.time.OffsetDateTime

/**
 * JVM test fakes for the Phase 6 hardware-sensors module.
 *
 * The runtime seams ([SensorGateway], [SensorRuntimeScheduler], [SensorRuntimeSettings])
 * are interfaces, so these fakes implement them directly — no mocking framework, matching
 * the `FakeDaos` / `FakeLifecycleCollaborators` style already in `src/test/`.
 */

/**
 * Fake [SensorGateway]. Records registered sensors, lets the test set the battery level
 * and power-save mode, and lets the test emit samples / fire triggers straight into the
 * controller's [SensorGateway.SampleListener].
 */
class FakeSensorGateway : SensorGateway {

    /** Sensors the device "has" — only these register successfully. */
    val availableOnDevice = AndroidSensorType.values().toMutableSet()

    var powerSaveMode = false
    var batteryPercent = 100

    val registeredContinuous = mutableListOf<AndroidSensorType>()
    /** Per-sensor sampling period a continuous sensor was registered at (proves per-sensor Hz). */
    val continuousSamplingPeriodUs = mutableMapOf<AndroidSensorType, Int>()
    /** Every sensor armed via [registerPersistentSensor] (on-change + one-shot trigger). */
    val registeredPersistent = mutableListOf<AndroidSensorType>()
    /** Every attempted persistent registration, including attempts forced to fail. */
    val persistentRegistrationAttempts = mutableListOf<AndroidSensorType>()
    /** Number of upcoming persistent registrations to fail for each sensor. */
    val persistentRegistrationFailuresRemaining = mutableMapOf<AndroidSensorType, Int>()
    /** The one-shot-trigger subset of [registeredPersistent]. */
    val registeredTrigger = mutableListOf<AndroidSensorType>()
    var lastSamplingPeriodUs = -1
    var lastMaxReportLatencyUs = -1
    var unregisterContinuousCount = 0
    var unregisterContinuousSensorCount = 0
    var unregisterAllCount = 0

    private var listener: SensorGateway.SampleListener? = null

    /** Wires the controller's listener so [emitSample] / [fireTrigger] reach the buffer. */
    fun attach(sampleListener: SensorGateway.SampleListener) {
        listener = sampleListener
    }

    override fun isPowerSaveMode(): Boolean = powerSaveMode

    override fun batteryLevelPercent(): Int = batteryPercent

    override fun registerContinuousSensor(
        sensorType: AndroidSensorType,
        samplingPeriodUs: Int,
        maxReportLatencyUs: Int,
    ): Boolean {
        if (sensorType !in availableOnDevice) return false
        registeredContinuous.add(sensorType)
        continuousSamplingPeriodUs[sensorType] = samplingPeriodUs
        lastSamplingPeriodUs = samplingPeriodUs
        lastMaxReportLatencyUs = maxReportLatencyUs
        return true
    }

    override fun registerPersistentSensor(sensorType: AndroidSensorType): Boolean {
        persistentRegistrationAttempts.add(sensorType)
        if (sensorType !in availableOnDevice) return false
        val failuresRemaining = persistentRegistrationFailuresRemaining[sensorType] ?: 0
        if (failuresRemaining > 0) {
            persistentRegistrationFailuresRemaining[sensorType] = failuresRemaining - 1
            return false
        }
        registeredPersistent.add(sensorType)
        if (SensorTypeMapping.isTriggerSensor(sensorType)) registeredTrigger.add(sensorType)
        return true
    }

    override fun unregisterContinuous() {
        unregisterContinuousCount++
        registeredContinuous.clear()
    }

    override fun unregisterContinuousSensor(sensorType: AndroidSensorType) {
        unregisterContinuousSensorCount++
        registeredContinuous.remove(sensorType)
    }

    override fun unregisterAll() {
        unregisterAllCount++
        registeredContinuous.clear()
        registeredPersistent.clear()
        registeredTrigger.clear()
    }

    /** Emits a continuous-sensor sample into the attached controller. */
    fun emitSample(
        sensorType: AndroidSensorType,
        values: FloatArray,
        accuracy: Int = 3,
        timestamp: OffsetDateTime = OffsetDateTime.now(),
    ) {
        listener?.onSample(sensorType, values, accuracy, timestamp)
    }

    /** Fires a one-shot trigger sample into the attached controller. */
    fun fireTrigger(
        sensorType: AndroidSensorType,
        values: FloatArray,
        timestamp: OffsetDateTime = OffsetDateTime.now(),
    ) {
        listener?.onTrigger(sensorType, values, timestamp)
    }

    /** Simulates Android consuming a trigger and then failing to re-arm its listener. */
    fun losePersistentRegistration(sensorType: AndroidSensorType) {
        registeredPersistent.remove(sensorType)
        registeredTrigger.remove(sensorType)
        listener?.onPersistentRegistrationLost(sensorType)
    }
}

/**
 * Manual [SensorRuntimeScheduler] for deterministic duty-cycle tests.
 *
 * Scheduled tasks are queued, not run on a thread; the test drives the duty cycle by
 * calling [runNext] / [runAll]. `execute` tasks (the 500-buffer flush) run synchronously
 * so a flush triggered inside `recordSample` happens immediately, as it effectively does
 * on the real single-thread executor.
 */
class ManualSensorRuntimeScheduler(
    private val executeImmediately: Boolean = true,
) : SensorRuntimeScheduler {

    /** Pending scheduled tasks, each with the delay it was scheduled with. */
    val scheduled = ArrayDeque<Pair<Long, () -> Unit>>()
    val pendingExecutions = ArrayDeque<() -> Unit>()
    var executeCount = 0
    private var shutdown = false

    override fun schedule(delaySeconds: Long, task: () -> Unit) {
        if (shutdown) return
        scheduled.addLast(delaySeconds to task)
    }

    override fun execute(task: () -> Unit) {
        if (shutdown) return
        executeCount++
        if (executeImmediately) task() else pendingExecutions.addLast(task)
    }

    override fun isShutdown(): Boolean = shutdown

    override fun shutdown() {
        shutdown = true
        scheduled.clear()
        pendingExecutions.clear()
    }

    /** Runs the next queued scheduled task, if any. Returns its delay or `null`. */
    fun runNext(): Long? {
        val next = scheduled.removeFirstOrNull() ?: return null
        next.second()
        return next.first
    }

    /** Runs queued scheduled tasks until none remain or [limit] is reached. */
    fun runAll(limit: Int = 100) {
        var iterations = 0
        while (scheduled.isNotEmpty() && iterations < limit) {
            runNext()
            iterations++
        }
    }

    fun runNextExecution() {
        pendingExecutions.removeFirstOrNull()?.invoke()
    }
}

/**
 * Fake [SensorRuntimeSettings] with directly-settable values. The scalar [samplingRate] /
 * [dutyActive] / [dutyPeriod] are the per-sensor defaults; the override maps let a test give
 * an individual sensor its own rate/duty (per-sensor consent redesign, 2026-06-11).
 */
class FakeSensorRuntimeSettings(
    var sensors: Set<AndroidSensorType> = setOf(AndroidSensorType.accelerometer),
    var samplingRate: Int = 5,
    var dutyActive: Int = 30,
    var dutyPeriod: Int = 300,
    val rateOverrides: MutableMap<AndroidSensorType, Int> = mutableMapOf(),
    val activeOverrides: MutableMap<AndroidSensorType, Int> = mutableMapOf(),
    val periodOverrides: MutableMap<AndroidSensorType, Int> = mutableMapOf(),
) : SensorRuntimeSettings {
    override fun enabledSensors(): Set<AndroidSensorType> = sensors
    override fun samplingRateHz(sensor: AndroidSensorType): Int = rateOverrides[sensor] ?: samplingRate
    override fun dutyCycleActiveSeconds(sensor: AndroidSensorType): Int = activeOverrides[sensor] ?: dutyActive
    override fun dutyCyclePeriodSeconds(sensor: AndroidSensorType): Int = periodOverrides[sensor] ?: dutyPeriod
}

/** Fake [LegacySensorSettingSource] returning a fixed (or null) legacy setting. */
class FakeLegacySensorSettingSource(
    var setting: AndroidSensorSetting? = null,
) : LegacySensorSettingSource {
    override fun read(): AndroidSensorSetting? = setting
}

/** Whether [sensorType] is a one-shot trigger sensor — convenience for tests. */
fun isTrigger(sensorType: AndroidSensorType): Boolean = SensorTypeMapping.isTriggerSensor(sensorType)
