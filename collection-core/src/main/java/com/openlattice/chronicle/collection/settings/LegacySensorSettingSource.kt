package com.openlattice.chronicle.collection.settings

import android.content.Context
import com.openlattice.chronicle.android.AndroidSensorSetting
import com.openlattice.chronicle.collection.core.CollectionLog
import com.openlattice.chronicle.preferences.SensorSettings

private const val TAG = "LegacySensorSettingSource"

/**
 * Read-only access to the legacy `AndroidSensor` setting (design §1B.4 — the legacy
 * fallback in the resolution order).
 *
 * The resolver must not depend on [SensorSettings] directly: [SensorSettings] holds an
 * Android [Context]-bound `EncryptedSharedPreferences`, which would make the resolver
 * untestable on the JVM. This interface is the seam — the resolver depends on it, a
 * real implementation wraps [SensorSettings], and tests supply a fake.
 *
 */
public interface LegacySensorSettingSource {
    /**
     * The legacy [AndroidSensorSetting] currently stored on the device, or `null` if
     * none has been persisted. An implementation that fails to read returns `null` so
     * the resolver falls through to safe coded defaults rather than crashing.
     */
    public fun read(): AndroidSensorSetting?
}

/**
 * Production [LegacySensorSettingSource] backed by the encrypted-prefs [SensorSettings].
 *
 * Reconstructs an [AndroidSensorSetting] from the four legacy keys
 * ([SensorSettings.getEnabledSensors], sampling rate, duty-cycle active/period). A read
 * failure is logged and surfaced as `null` so resolution stays safe — it never silently
 * enables a module.
 */
public class EncryptedPrefsSensorSettingSource(
    private val context: Context,
    private val log: CollectionLog = CollectionLog.LOGCAT,
) : LegacySensorSettingSource {

    override fun read(): AndroidSensorSetting? = try {
        val sensorSettings = SensorSettings(context)
        val enabled = sensorSettings.getEnabledSensors()
        if (enabled.isEmpty()) {
            // No sensors persisted — there is no legacy setting to fall back to.
            null
        } else {
            AndroidSensorSetting(
                sensors = enabled,
                samplingRateHz = sensorSettings.getSamplingRateHz(),
                dutyCycleActiveSeconds = sensorSettings.getDutyCycleActiveSeconds(),
                dutyCyclePeriodSeconds = sensorSettings.getDutyCyclePeriodSeconds(),
            )
        }
    } catch (e: Exception) {
        // Safe fallback: a failed read must not enable anything. Disable via null.
        log.error(TAG, "Failed to read legacy AndroidSensor setting; falling back to defaults", e)
        null
    }
}
