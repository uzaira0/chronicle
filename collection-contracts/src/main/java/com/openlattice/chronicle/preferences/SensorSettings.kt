package com.openlattice.chronicle.preferences

import android.content.Context
import com.openlattice.chronicle.android.AndroidSensorSetting
import com.openlattice.chronicle.android.AndroidSensorType

private const val SENSOR_PREFS_PREFIX = "sensor_"
private const val KEY_SENSORS = SENSOR_PREFS_PREFIX + "enabled_types"
// Legacy device-wide sampling/duty (the legacy AndroidSensor endpoint path). Per-sensor
// overrides below take precedence; these remain the fallback for the legacy bridge.
private const val KEY_SAMPLING_RATE = SENSOR_PREFS_PREFIX + "sampling_rate_hz"
private const val KEY_DUTY_ACTIVE = SENSOR_PREFS_PREFIX + "duty_active_seconds"
private const val KEY_DUTY_PERIOD = SENSOR_PREFS_PREFIX + "duty_period_seconds"
// Per-sensor sampling/duty (per-sensor consent redesign, 2026-06-11): each configured sensor
// carries its own rate + duty cycle, written from the resolved DataCollection per-sensor modules.
private const val KEY_RATE_HZ_PREFIX = SENSOR_PREFS_PREFIX + "rate_hz_"
private const val KEY_DUTY_ACTIVE_PREFIX = SENSOR_PREFS_PREFIX + "duty_active_"
private const val KEY_DUTY_PERIOD_PREFIX = SENSOR_PREFS_PREFIX + "duty_period_"
private const val KEY_LAST_SENSOR_UPLOAD = SENSOR_PREFS_PREFIX + "last_upload"

private const val DEFAULT_RATE_HZ = 5
private const val DEFAULT_DUTY_ACTIVE = 30
private const val DEFAULT_DUTY_PERIOD = 300

/**
 * The device's local cache of the study's sensor configuration: which sensors the study
 * collects and, **per sensor**, its sampling rate + duty cycle (per-sensor consent
 * redesign, 2026-06-11).
 *
 * Consent is no longer expressed here — each sensor is its own consent-gated
 * [com.openlattice.chronicle.collection.CollectionModuleId], decided through the state
 * machine and read at collection time via `CollectionGate`. This class only answers "what
 * does the study want collected, and at what rate/duty" for the runtime; the per-sensor
 * gate decides what actually collects.
 *
 * Two writers:
 *  - [applyResolvedSensors] — the authoritative per-sensor writer, fed from the resolved
 *    `DataCollection` per-sensor modules by the collection-loop sync.
 *  - [save] — the legacy `AndroidSensor` endpoint bridge, which carries one device-wide
 *    rate/duty applied to every configured sensor.
 */
class SensorSettings(context: Context) {
    private val prefs = EncryptedPrefsHelper.getEncryptedPrefs(context)

    /**
     * Persists the study's per-sensor configuration: the configured set plus each sensor's
     * own sampling rate + duty cycle. Clears any stale per-sensor overrides first so a sensor
     * the study dropped does not keep an orphaned rate/duty. Returns only after the generation is
     * durable so callers can keep collection gates closed on a failed write.
     */
    fun applyResolvedSensors(perSensor: Map<AndroidSensorType, AndroidSensorSetting>): Boolean {
        val editor = prefs.edit()
        AndroidSensorType.entries.forEach { sensor ->
            editor.remove(KEY_RATE_HZ_PREFIX + sensor.name)
                .remove(KEY_DUTY_ACTIVE_PREFIX + sensor.name)
                .remove(KEY_DUTY_PERIOD_PREFIX + sensor.name)
        }
        editor.putStringSet(KEY_SENSORS, perSensor.keys.map { it.name }.toSet())
        perSensor.forEach { (sensor, policy) ->
            editor.putInt(KEY_RATE_HZ_PREFIX + sensor.name, policy.samplingRateHz)
                .putInt(KEY_DUTY_ACTIVE_PREFIX + sensor.name, policy.dutyCycleActiveSeconds)
                .putInt(KEY_DUTY_PERIOD_PREFIX + sensor.name, policy.dutyCyclePeriodSeconds)
        }
        return editor.commit()
    }

    /**
     * Legacy bridge: persists a single device-wide [AndroidSensorSetting] (the legacy
     * `AndroidSensor` endpoint), applying its one rate/duty to every configured sensor. Clears
     * per-sensor overrides so the device-wide values govern.
     */
    fun save(setting: AndroidSensorSetting) {
        val editor = prefs.edit()
        AndroidSensorType.entries.forEach { sensor ->
            editor.remove(KEY_RATE_HZ_PREFIX + sensor.name)
                .remove(KEY_DUTY_ACTIVE_PREFIX + sensor.name)
                .remove(KEY_DUTY_PERIOD_PREFIX + sensor.name)
        }
        editor.putStringSet(KEY_SENSORS, setting.sensors.map { it.name }.toSet())
            .putInt(KEY_SAMPLING_RATE, setting.samplingRateHz)
            .putInt(KEY_DUTY_ACTIVE, setting.dutyCycleActiveSeconds)
            .putInt(KEY_DUTY_PERIOD, setting.dutyCyclePeriodSeconds)
            .apply()
    }

    /** The sensors the STUDY configures (server-enabled). The per-sensor gate filters actual collection. */
    fun getConfiguredSensors(): Set<AndroidSensorType> = readSensorTypes(KEY_SENSORS)

    /**
     * The sensors the runtime attempts to collect — the study-configured set. (Per-sensor
     * consent is enforced separately by `CollectionGate`; the dashboard treats configured ==
     * enabled, with the actual collecting state surfaced by the gate.)
     */
    fun getEnabledSensors(): Set<AndroidSensorType> = getConfiguredSensors()

    /** [sensor]'s sampling rate in Hz — its per-sensor override, else the device-wide value, else default. */
    fun getSamplingRateHz(sensor: AndroidSensorType): Int =
        prefs.getInt(KEY_RATE_HZ_PREFIX + sensor.name, getSamplingRateHz())

    /** [sensor]'s duty-cycle active seconds — per-sensor override, else device-wide, else default. */
    fun getDutyCycleActiveSeconds(sensor: AndroidSensorType): Int =
        prefs.getInt(KEY_DUTY_ACTIVE_PREFIX + sensor.name, getDutyCycleActiveSeconds())

    /** [sensor]'s duty-cycle period seconds — per-sensor override, else device-wide, else default. */
    fun getDutyCyclePeriodSeconds(sensor: AndroidSensorType): Int =
        prefs.getInt(KEY_DUTY_PERIOD_PREFIX + sensor.name, getDutyCyclePeriodSeconds())

    /** Device-wide sampling rate (legacy `AndroidSensor` path / per-sensor fallback). */
    fun getSamplingRateHz(): Int = prefs.getInt(KEY_SAMPLING_RATE, DEFAULT_RATE_HZ)

    /** Device-wide duty-cycle active seconds (legacy `AndroidSensor` path / per-sensor fallback). */
    fun getDutyCycleActiveSeconds(): Int = prefs.getInt(KEY_DUTY_ACTIVE, DEFAULT_DUTY_ACTIVE)

    /** Device-wide duty-cycle period seconds (legacy `AndroidSensor` path / per-sensor fallback). */
    fun getDutyCyclePeriodSeconds(): Int = prefs.getInt(KEY_DUTY_PERIOD, DEFAULT_DUTY_PERIOD)

    fun hasConfiguredSensors(): Boolean = getConfiguredSensors().isNotEmpty()

    fun isEnabled(): Boolean = getConfiguredSensors().isNotEmpty()

    fun setLastSensorUpload(timestamp: String) {
        prefs.edit().putString(KEY_LAST_SENSOR_UPLOAD, timestamp).apply()
    }

    fun getLastSensorUpload(): String? = prefs.getString(KEY_LAST_SENSOR_UPLOAD, null)

    /** Durably removes every legacy high-rate sensor setting. */
    fun clear(): Boolean {
        val editor = prefs.edit()
            .remove(KEY_SENSORS)
            .remove(KEY_SAMPLING_RATE)
            .remove(KEY_DUTY_ACTIVE)
            .remove(KEY_DUTY_PERIOD)
        AndroidSensorType.entries.forEach { sensor ->
            editor.remove(KEY_RATE_HZ_PREFIX + sensor.name)
                .remove(KEY_DUTY_ACTIVE_PREFIX + sensor.name)
                .remove(KEY_DUTY_PERIOD_PREFIX + sensor.name)
        }
        return editor.commit()
    }

    private fun readSensorNames(key: String): Set<String> =
        prefs.getStringSet(key, emptySet())?.toSet() ?: emptySet()

    private fun readSensorTypes(key: String): Set<AndroidSensorType> {
        return readSensorNames(key).mapNotNull { name ->
            try {
                AndroidSensorType.valueOf(name)
            } catch (e: IllegalArgumentException) {
                null
            }
        }.toSet()
    }
}
