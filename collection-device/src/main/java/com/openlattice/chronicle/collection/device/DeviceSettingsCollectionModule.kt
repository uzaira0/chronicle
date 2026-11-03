package com.openlattice.chronicle.collection.device

import com.openlattice.chronicle.collection.AndroidDeviceSettingsEvent
import com.openlattice.chronicle.collection.RingerMode
import com.openlattice.chronicle.collection.ThermalStatus
import com.openlattice.chronicle.collection.core.ModuleResult
import com.openlattice.chronicle.collection.core.PullAccounting
import com.openlattice.chronicle.collection.core.SnapshotCollectionModule
import com.openlattice.chronicle.collection.sink.DeviceSettingsSampleSink
import com.openlattice.chronicle.collection.state.CollectionPersistenceGuard
import com.openlattice.chronicle.storage.DeviceSettingsSampleEntry
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.util.TimeZone
import java.util.UUID

/**
 * The `device_settings` collection module — a **pull-style** module mirroring
 * `BatteryTelemetryCollectionModule`. Each [sample] reads a device-settings snapshot through the
 * injected [DeviceSettingsSource] and persists one row. DEVICE_STATE_METADATA-class (default OFF).
 */
public class DeviceSettingsCollectionModule(
    private val sink: DeviceSettingsSampleSink,
    private val source: DeviceSettingsSource,
    enrolled: () -> Boolean,
    accounting: PullAccounting = PullAccounting(),
    now: () -> Long = System::currentTimeMillis,
) : SnapshotCollectionModule<DeviceSettingsReading>("device settings", enrolled, accounting, now) {
    override fun captureAdmission(): CollectionPersistenceGuard = sink.captureAdmission()

    override fun read(): DeviceSettingsReading? = source.read()

    override fun persist(reading: DeviceSettingsReading, now: Long): ModuleResult = sink.write(listOf(
        DeviceSettingsSampleEntry(
            id = UUID.randomUUID().toString(),
            timestamp = OffsetDateTime.ofInstant(Instant.ofEpochMilli(now), ZoneOffset.UTC).toString(),
            timezone = TimeZone.getDefault().id,
            darkMode = reading.darkMode,
            fontScale = reading.fontScale,
            accessibilityEnabled = reading.accessibilityEnabled,
            dndActive = reading.dndActive,
            batterySaver = reading.batterySaver,
            thermalStatus = reading.thermalStatus?.name,
            autoRotate = reading.autoRotate,
            locationServicesEnabled = reading.locationServicesEnabled,
            storageFreeBytes = reading.storageFreeBytes,
            storageTotalBytes = reading.storageTotalBytes,
            screenBrightness = reading.screenBrightness,
            screenBrightnessAuto = reading.screenBrightnessAuto,
            mediaVolume = reading.mediaVolume,
            mediaVolumeMax = reading.mediaVolumeMax,
            ringVolume = reading.ringVolume,
            ringVolumeMax = reading.ringVolumeMax,
            notificationVolume = reading.notificationVolume,
            notificationVolumeMax = reading.notificationVolumeMax,
            alarmVolume = reading.alarmVolume,
            alarmVolumeMax = reading.alarmVolumeMax,
            ringerMode = reading.ringerMode?.name,
        ),
    ))
}

/**
 * Converts a stored [DeviceSettingsSampleEntry] row into the [AndroidDeviceSettingsEvent] wire
 * DTO. Throws if the row carries an unparseable timestamp or unknown thermal-status name; the
 * upload path catches this per row so one corrupt row never aborts a batch.
 */
public fun DeviceSettingsSampleEntry.toAndroidDeviceSettingsEvent(): AndroidDeviceSettingsEvent =
    AndroidDeviceSettingsEvent(
        id = id,
        timestamp = OffsetDateTime.parse(timestamp),
        timezone = timezone,
        darkMode = darkMode,
        fontScale = fontScale,
        accessibilityEnabled = accessibilityEnabled,
        dndActive = dndActive,
        batterySaver = batterySaver,
        thermalStatus = thermalStatus?.let { ThermalStatus.valueOf(it) },
        autoRotate = autoRotate,
        locationServicesEnabled = locationServicesEnabled,
        storageFreeBytes = storageFreeBytes,
        storageTotalBytes = storageTotalBytes,
        screenBrightness = screenBrightness,
        screenBrightnessAuto = screenBrightnessAuto,
        mediaVolume = mediaVolume,
        mediaVolumeMax = mediaVolumeMax,
        ringVolume = ringVolume,
        ringVolumeMax = ringVolumeMax,
        notificationVolume = notificationVolume,
        notificationVolumeMax = notificationVolumeMax,
        alarmVolume = alarmVolume,
        alarmVolumeMax = alarmVolumeMax,
        ringerMode = ringerMode?.let { RingerMode.valueOf(it) },
    )
