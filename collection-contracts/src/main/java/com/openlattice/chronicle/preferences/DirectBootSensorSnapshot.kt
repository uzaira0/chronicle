package com.openlattice.chronicle.preferences

import android.content.Context
import android.content.SharedPreferences
import com.openlattice.chronicle.android.AndroidSensorType

private const val DIRECT_BOOT_PREFS_FILE = "chronicle_direct_boot_snapshot"
private const val KEY_WRITTEN_AT = "db_written_at"
private const val KEY_SENSORS = "db_sensors"
private const val KEY_OWNER = "db_owner"
private const val KEY_RATE_HZ_PREFIX = "db_rate_hz_"
private const val KEY_DUTY_ACTIVE_PREFIX = "db_duty_active_"
private const val KEY_DUTY_PERIOD_PREFIX = "db_duty_period_"

// Mirrors SensorSettings' defaults.
private const val DEFAULT_RATE_HZ = 5
private const val DEFAULT_DUTY_ACTIVE = 30
private const val DEFAULT_DUTY_PERIOD = 300

private fun directBootSensorPrefs(context: Context): SharedPreferences =
    context.createDeviceProtectedStorageContext().getSharedPreferences(DIRECT_BOOT_PREFS_FILE, Context.MODE_PRIVATE)

/** Removes the snapshot, so a locked boot starts nothing; withdrawal and module retirement call this. */
fun clearDirectBootSensorSnapshot(context: Context): Boolean =
    directBootSensorPrefs(context).edit().clear().commit()

/**
 * Device-protected mirror of the consented sensor set and each sensor's rate and duty cycle: the only
 * collection state readable between reboot and first unlock. Rewritten from live consent while unlocked,
 * cleared on withdrawal, and bounded by [isUsableFor], so a locked boot never collects for a withdrawn
 * participant. It holds configuration and a one-way enrollment key, never identity or samples.
 */
class DirectBootSensorSnapshot(
    private val prefs: SharedPreferences,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    constructor(context: Context) : this(directBootSensorPrefs(context))

    /** One sensor's runtime configuration, mirroring the `SensorSettings` per-sensor getters. */
    data class SensorConfig(
        val samplingRateHz: Int = DEFAULT_RATE_HZ,
        val dutyCycleActiveSeconds: Int = DEFAULT_DUTY_ACTIVE,
        val dutyCyclePeriodSeconds: Int = DEFAULT_DUTY_PERIOD,
    )

    /** Replaces the snapshot; an empty map means nothing collects before unlock. Commits synchronously. */
    fun write(collectable: Map<AndroidSensorType, SensorConfig>, owner: String? = null): Boolean {
        val editor = prefs.edit().putString(KEY_OWNER, owner)
        AndroidSensorType.entries.forEach { sensor ->
            editor.remove(KEY_RATE_HZ_PREFIX + sensor.name)
                .remove(KEY_DUTY_ACTIVE_PREFIX + sensor.name)
                .remove(KEY_DUTY_PERIOD_PREFIX + sensor.name)
        }
        editor.putStringSet(KEY_SENSORS, collectable.keys.map { it.name }.toSet())
        collectable.forEach { (sensor, config) ->
            editor.putInt(KEY_RATE_HZ_PREFIX + sensor.name, config.samplingRateHz)
                .putInt(KEY_DUTY_ACTIVE_PREFIX + sensor.name, config.dutyCycleActiveSeconds)
                .putInt(KEY_DUTY_PERIOD_PREFIX + sensor.name, config.dutyCyclePeriodSeconds)
        }
        editor.putLong(KEY_WRITTEN_AT, clock())
        return editor.commit()
    }

    /** The opaque key of the enrollment whose buffer a locked boot writes; never an identifier. */
    fun owner(): String? = prefs.getString(KEY_OWNER, null)

    /** Removes the snapshot entirely; a locked boot then starts nothing. */
    fun clear(): Boolean = prefs.edit().clear().commit()

    /** The sensors that may collect before first unlock. Unknown persisted names are ignored. */
    fun collectableSensors(): Set<AndroidSensorType> {
        val names = prefs.getStringSet(KEY_SENSORS, emptySet()).orEmpty()
        return AndroidSensorType.entries.filterTo(mutableSetOf()) { it.name in names }
    }

    /** [sensor]'s snapshotted rate/duty, or the shared defaults if never written. */
    fun config(sensor: AndroidSensorType): SensorConfig = SensorConfig(
        samplingRateHz = prefs.getInt(KEY_RATE_HZ_PREFIX + sensor.name, DEFAULT_RATE_HZ),
        dutyCycleActiveSeconds = prefs.getInt(KEY_DUTY_ACTIVE_PREFIX + sensor.name, DEFAULT_DUTY_ACTIVE),
        dutyCyclePeriodSeconds = prefs.getInt(KEY_DUTY_PERIOD_PREFIX + sensor.name, DEFAULT_DUTY_PERIOD),
    )

    /** Millis since the last [write], or null if the snapshot has never been written. */
    fun ageMillis(): Long? {
        val writtenAt = prefs.getLong(KEY_WRITTEN_AT, -1L)
        if (writtenAt < 0) return null
        return (clock() - writtenAt).coerceAtLeast(0L)
    }

    /** Written, no older than [maxAgeMillis], bound to an enrollment, and naming a collectable sensor. */
    fun isUsableFor(maxAgeMillis: Long): Boolean {
        val age = ageMillis() ?: return false
        return age <= maxAgeMillis && owner() != null && collectableSensors().isNotEmpty()
    }

    companion object {
        /** Snapshots older than this never start locked-boot collection (fail closed). */
        const val MAX_SNAPSHOT_AGE_MILLIS: Long = 14L * 24 * 60 * 60 * 1000
    }
}
