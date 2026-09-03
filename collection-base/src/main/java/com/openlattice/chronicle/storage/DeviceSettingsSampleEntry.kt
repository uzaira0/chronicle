package com.openlattice.chronicle.storage

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * Buffered `device_settings` snapshot, one row per AndroidDeviceSettingsEvent before upload.
 * DEVICE_STATE_METADATA-class: a content-free/identity-free snapshot of device toggles. Every
 * descriptive column is nullable so a partial snapshot still persists.
 */
@Entity(tableName = "device_settings_samples")
data class DeviceSettingsSampleEntry(
    @PrimaryKey val id: String,
    val timestamp: String,
    val timezone: String,
    val darkMode: Boolean?,
    val fontScale: Float?,
    val accessibilityEnabled: Boolean?,
    val dndActive: Boolean?,
    val batterySaver: Boolean?,
    val thermalStatus: String?,
    val autoRotate: Boolean?,
    val locationServicesEnabled: Boolean?,
    val storageFreeBytes: Long?,
    val storageTotalBytes: Long?,
    val screenBrightness: Int? = null,
    val screenBrightnessAuto: Boolean? = null,
    val mediaVolume: Int? = null,
    val mediaVolumeMax: Int? = null,
    val ringVolume: Int? = null,
    val ringVolumeMax: Int? = null,
    val notificationVolume: Int? = null,
    val notificationVolumeMax: Int? = null,
    val alarmVolume: Int? = null,
    val alarmVolumeMax: Int? = null,
    val ringerMode: String? = null,
)
