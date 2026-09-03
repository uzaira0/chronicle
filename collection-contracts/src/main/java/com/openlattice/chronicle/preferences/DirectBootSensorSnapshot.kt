package com.openlattice.chronicle.preferences

import android.content.Context
import android.content.SharedPreferences
import android.os.Build
import com.openlattice.chronicle.android.AndroidSensorType

private const val DIRECT_BOOT_PREFS_FILE = "chronicle_direct_boot_snapshot"
private const val KEY_WRITTEN_AT = "db_written_at"
private const val KEY_SENSORS = "db_sensors"
private const val KEY_RATE_HZ_PREFIX = "db_rate_hz_"
private const val KEY_DUTY_ACTIVE_PREFIX = "db_duty_active_"
private const val KEY_DUTY_PERIOD_PREFIX = "db_duty_period_"

// Mirrors SensorSettings' defaults (per-sensor consent redesign, 2026-06-11).
private const val DEFAULT_RATE_HZ = 5
private const val DEFAULT_DUTY_ACTIVE = 30
private const val DEFAULT_DUTY_PERIOD = 300

private fun directBootSensorPrefs(context: Context): SharedPreferences {
    // Device-protected storage exists from API 24; below that there is no
    // file-based-encryption direct-boot window, so plain app storage is equivalent.
    val storageContext = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
        context.createDeviceProtectedStorageContext()
    } else {
        context
    }
    return storageContext.getSharedPreferences(DIRECT_BOOT_PREFS_FILE, Context.MODE_PRIVATE)
}

/**
 * Clears legacy direct-boot sensor configuration without retaining the research snapshot class.
 * The Play update path calls this neutral erasure seam even though locked-boot sensor collection
 * itself is removed from the Play artifact.
 */
fun clearDirectBootSensorSnapshot(context: Context): Boolean =
    directBootSensorPrefs(context).edit().clear().commit()

/**
 * A device-protected-storage (direct-boot) mirror of the currently *collectable* sensor set —
 * the sensors whose per-sensor module is server-enabled AND participant-acknowledged — plus
 * each sensor's sampling rate and duty cycle.
 *
 * After a reboot, Android holds `BOOT_COMPLETED` (and credential-encrypted storage — the
 * encrypted prefs and the SQLCipher Room DB) until the user first unlocks the device. This
 * snapshot is the only collection state readable in that window: `LockedBootReceiver` uses it
 * to decide whether to start sensor collection before first unlock, and the direct-boot
 * runtime uses it in place of `SensorSettings`/`CollectionGate`.
 *
 * **Contents are configuration only — never identity, consent text, or collected data.** No
 * study id, participant id, or sample ever lands here; device-protected storage is encrypted
 * with a boot-time key rather than the user credential, so it is held to config-only use.
 *
 * The snapshot is rewritten from live gate reads every time the sensor service starts or
 * reconciles in normal (unlocked) mode, and cleared on withdrawal / participation change —
 * so a locked-boot start can never collect for a withdrawn participant. A staleness bound
 * ([isUsableFor]) fails closed if the device somehow boots months after the last rewrite.
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

    /**
     * Replaces the snapshot with [collectable]. An empty map is a valid snapshot meaning
     * "nothing may collect before unlock". Synchronous commit so callers can trust the
     * locked-boot view once this returns.
     */
    fun write(collectable: Map<AndroidSensorType, SensorConfig>): Boolean {
        val editor = prefs.edit()
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

    /** Removes the snapshot entirely; a locked boot then starts nothing. */
    fun clear(): Boolean = prefs.edit().clear().commit()

    /** The sensors that may collect before first unlock. Unknown persisted names are ignored. */
    fun collectableSensors(): Set<AndroidSensorType> =
        (prefs.getStringSet(KEY_SENSORS, emptySet()) ?: emptySet())
            .mapNotNull { name ->
                try {
                    AndroidSensorType.valueOf(name)
                } catch (_: IllegalArgumentException) {
                    null
                }
            }
            .toSet()

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

    /**
     * Whether a locked boot may start collection from this snapshot: written, no older than
     * [maxAgeMillis], and naming at least one collectable sensor. Fails closed on all three.
     */
    fun isUsableFor(maxAgeMillis: Long): Boolean {
        val age = ageMillis() ?: return false
        return age <= maxAgeMillis && collectableSensors().isNotEmpty()
    }

    companion object {
        /** Snapshots older than this never start locked-boot collection (fail closed). */
        const val MAX_SNAPSHOT_AGE_MILLIS: Long = 14L * 24 * 60 * 60 * 1000

    }
}
