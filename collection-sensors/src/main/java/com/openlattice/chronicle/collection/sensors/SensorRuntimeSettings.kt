package com.openlattice.chronicle.collection.sensors

import android.content.Context
import com.openlattice.chronicle.android.AndroidSensorType
import com.openlattice.chronicle.preferences.SensorSettings

/**
 * Read-only seam over the duty-cycle / sensor-set configuration the runtime needs
 * (refactor plan §9.1).
 *
 * [SensorRuntimeController] must resolve which sensors to register and the duty-cycle
 * timings, but it must not depend on [SensorSettings] directly — [SensorSettings] holds an
 * Android [Context]-bound `EncryptedSharedPreferences`, which would make the controller
 * untestable on the JVM. This interface is the seam: the controller depends on it, the
 * production [SensorSettingsRuntimeSettings] wraps [SensorSettings], and JVM tests supply
 * a fake.
 *
 * The values exposed here mirror the four legacy `SensorSettings` getters byte-for-byte —
 * Phase 6 changes no collected value, only where the read happens.
 *
 */
public interface SensorRuntimeSettings {
    /**
     * The sensor types the study configures (server-enabled). The controller attempts each
     * of these; its per-sensor collection gate decides which actually collect. Each sensor
     * carries its own sampling rate + duty cycle (per-sensor consent redesign, 2026-06-11).
     */
    public fun enabledSensors(): Set<AndroidSensorType>

    /** [sensor]'s continuous-sampling rate in Hz. */
    public fun samplingRateHz(sensor: AndroidSensorType): Int

    /** [sensor]'s duty-cycle active-phase length in seconds. */
    public fun dutyCycleActiveSeconds(sensor: AndroidSensorType): Int

    /** [sensor]'s duty-cycle full-period length in seconds (active + idle). */
    public fun dutyCyclePeriodSeconds(sensor: AndroidSensorType): Int
}

/**
 * Production [SensorRuntimeSettings] backed by the encrypted-prefs [SensorSettings].
 *
 * Delegates to the per-sensor getters so each sensor's own rate / duty cycle drive its
 * independent duty-cycle loop.
 */
public class SensorSettingsRuntimeSettings(context: Context) : SensorRuntimeSettings {

    private val sensorSettings = SensorSettings(context.applicationContext)

    override fun enabledSensors(): Set<AndroidSensorType> = sensorSettings.getConfiguredSensors()

    override fun samplingRateHz(sensor: AndroidSensorType): Int = sensorSettings.getSamplingRateHz(sensor)

    override fun dutyCycleActiveSeconds(sensor: AndroidSensorType): Int =
        sensorSettings.getDutyCycleActiveSeconds(sensor)

    override fun dutyCyclePeriodSeconds(sensor: AndroidSensorType): Int =
        sensorSettings.getDutyCyclePeriodSeconds(sensor)
}
