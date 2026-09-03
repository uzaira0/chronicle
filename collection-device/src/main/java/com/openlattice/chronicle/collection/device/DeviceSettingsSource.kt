package com.openlattice.chronicle.collection.device

import android.app.NotificationManager
import android.content.Context
import android.content.res.Configuration
import android.location.LocationManager
import android.media.AudioManager
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import android.view.accessibility.AccessibilityManager
import com.openlattice.chronicle.collection.RingerMode
import com.openlattice.chronicle.collection.ThermalStatus
import java.io.File

/**
 * A point-in-time device-settings snapshot produced by a [DeviceSettingsSource]. Every field is
 * nullable so a value unavailable on a given OS/OEM still yields a partial snapshot. Carries no
 * id or timestamp — [DeviceSettingsCollectionModule] adds those. Content-free and identity-free.
 */
public data class DeviceSettingsReading(
    public val darkMode: Boolean?,
    public val fontScale: Float?,
    public val accessibilityEnabled: Boolean?,
    public val dndActive: Boolean?,
    public val batterySaver: Boolean?,
    public val thermalStatus: ThermalStatus?,
    public val autoRotate: Boolean?,
    public val locationServicesEnabled: Boolean?,
    public val storageFreeBytes: Long?,
    public val storageTotalBytes: Long?,
    public val screenBrightness: Int?,
    public val screenBrightnessAuto: Boolean?,
    public val mediaVolume: Int?,
    public val mediaVolumeMax: Int?,
    public val ringVolume: Int?,
    public val ringVolumeMax: Int?,
    public val notificationVolume: Int?,
    public val notificationVolumeMax: Int?,
    public val alarmVolume: Int?,
    public val alarmVolumeMax: Int?,
    public val ringerMode: RingerMode?,
)

/** Dependency-inversion seam for reading device settings. Production impl: [AndroidDeviceSettingsSource]. */
public fun interface DeviceSettingsSource {
    /** Reads a current device-settings snapshot, or `null` if nothing could be read. */
    public fun read(): DeviceSettingsReading?
}

/**
 * Production [DeviceSettingsSource] over framework getters and `Settings.*`. Each field is read
 * defensively (a failure yields `null` for that field, never an aborted snapshot). Holds only an
 * application-`Context`. Reads only how the device is configured — never what the participant does.
 */
public class AndroidDeviceSettingsSource(context: Context) : DeviceSettingsSource {

    private val appContext = context.applicationContext

    override fun read(): DeviceSettingsReading? {
        val audio = appContext.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
        val reading = DeviceSettingsReading(
            darkMode = readDarkMode(),
            fontScale = runCatching { appContext.resources.configuration.fontScale }.getOrNull(),
            accessibilityEnabled = runCatching {
                (appContext.getSystemService(Context.ACCESSIBILITY_SERVICE) as? AccessibilityManager)?.isEnabled
            }.getOrNull(),
            dndActive = readDndActive(),
            batterySaver = runCatching {
                (appContext.getSystemService(Context.POWER_SERVICE) as? PowerManager)?.isPowerSaveMode
            }.getOrNull(),
            thermalStatus = readThermalStatus(),
            autoRotate = runCatching {
                Settings.System.getInt(appContext.contentResolver, Settings.System.ACCELEROMETER_ROTATION) == 1
            }.getOrNull(),
            locationServicesEnabled = readLocationEnabled(),
            storageFreeBytes = runCatching { File(appContext.filesDir.absolutePath).freeSpace }.getOrNull(),
            storageTotalBytes = runCatching { File(appContext.filesDir.absolutePath).totalSpace }.getOrNull(),
            screenBrightness = runCatching {
                Settings.System.getInt(appContext.contentResolver, Settings.System.SCREEN_BRIGHTNESS)
            }.getOrNull(),
            screenBrightnessAuto = runCatching {
                Settings.System.getInt(appContext.contentResolver, Settings.System.SCREEN_BRIGHTNESS_MODE) ==
                    Settings.System.SCREEN_BRIGHTNESS_MODE_AUTOMATIC
            }.getOrNull(),
            mediaVolume = streamVolume(audio, AudioManager.STREAM_MUSIC),
            mediaVolumeMax = streamMaxVolume(audio, AudioManager.STREAM_MUSIC),
            ringVolume = streamVolume(audio, AudioManager.STREAM_RING),
            ringVolumeMax = streamMaxVolume(audio, AudioManager.STREAM_RING),
            notificationVolume = streamVolume(audio, AudioManager.STREAM_NOTIFICATION),
            notificationVolumeMax = streamMaxVolume(audio, AudioManager.STREAM_NOTIFICATION),
            alarmVolume = streamVolume(audio, AudioManager.STREAM_ALARM),
            alarmVolumeMax = streamMaxVolume(audio, AudioManager.STREAM_ALARM),
            ringerMode = readRingerMode(audio),
        )
        // If every field came back null the snapshot carries nothing useful — treat as unavailable.
        val anyKnown = listOf<Any?>(
            reading.darkMode, reading.fontScale, reading.accessibilityEnabled, reading.dndActive,
            reading.batterySaver, reading.thermalStatus, reading.autoRotate, reading.locationServicesEnabled,
            reading.storageFreeBytes, reading.storageTotalBytes,
            reading.screenBrightness, reading.screenBrightnessAuto,
            reading.mediaVolume, reading.mediaVolumeMax, reading.ringVolume, reading.ringVolumeMax,
            reading.notificationVolume, reading.notificationVolumeMax, reading.alarmVolume, reading.alarmVolumeMax,
            reading.ringerMode,
        ).any { it != null }
        return if (anyKnown) reading else null
    }

    private fun streamVolume(audio: AudioManager?, stream: Int): Int? =
        runCatching { audio?.getStreamVolume(stream) }.getOrNull()

    private fun streamMaxVolume(audio: AudioManager?, stream: Int): Int? =
        runCatching { audio?.getStreamMaxVolume(stream) }.getOrNull()

    private fun readRingerMode(audio: AudioManager?): RingerMode? = runCatching {
        when (audio?.ringerMode) {
            AudioManager.RINGER_MODE_NORMAL -> RingerMode.NORMAL
            AudioManager.RINGER_MODE_VIBRATE -> RingerMode.VIBRATE
            AudioManager.RINGER_MODE_SILENT -> RingerMode.SILENT
            null -> null
            else -> RingerMode.UNKNOWN
        }
    }.getOrNull()

    private fun readDarkMode(): Boolean? = runCatching {
        val mode = appContext.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK
        mode == Configuration.UI_MODE_NIGHT_YES
    }.getOrNull()

    private fun readDndActive(): Boolean? = runCatching {
        val nm = appContext.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
        nm?.currentInterruptionFilter?.let { it != NotificationManager.INTERRUPTION_FILTER_ALL }
    }.getOrNull()

    private fun readThermalStatus(): ThermalStatus? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return null
        return runCatching {
            val pm = appContext.getSystemService(Context.POWER_SERVICE) as? PowerManager ?: return null
            when (pm.currentThermalStatus) {
                PowerManager.THERMAL_STATUS_NONE -> ThermalStatus.NONE
                PowerManager.THERMAL_STATUS_LIGHT -> ThermalStatus.LIGHT
                PowerManager.THERMAL_STATUS_MODERATE -> ThermalStatus.MODERATE
                PowerManager.THERMAL_STATUS_SEVERE -> ThermalStatus.SEVERE
                PowerManager.THERMAL_STATUS_CRITICAL -> ThermalStatus.CRITICAL
                PowerManager.THERMAL_STATUS_EMERGENCY -> ThermalStatus.EMERGENCY
                PowerManager.THERMAL_STATUS_SHUTDOWN -> ThermalStatus.SHUTDOWN
                else -> ThermalStatus.UNKNOWN
            }
        }.getOrNull()
    }

    private fun readLocationEnabled(): Boolean? = runCatching {
        val lm = appContext.getSystemService(Context.LOCATION_SERVICE) as? LocationManager ?: return null
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            lm.isLocationEnabled
        } else {
            @Suppress("DEPRECATION")
            Settings.Secure.getInt(
                appContext.contentResolver,
                Settings.Secure.LOCATION_MODE,
                Settings.Secure.LOCATION_MODE_OFF,
            ) != Settings.Secure.LOCATION_MODE_OFF
        }
    }.getOrNull()
}
