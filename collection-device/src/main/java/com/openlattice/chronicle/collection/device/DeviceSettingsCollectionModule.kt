package com.openlattice.chronicle.collection.device

import com.openlattice.chronicle.collection.AndroidDeviceSettingsEvent
import com.openlattice.chronicle.collection.CollectionModuleId
import com.openlattice.chronicle.collection.RingerMode
import com.openlattice.chronicle.collection.ThermalStatus
import com.openlattice.chronicle.collection.core.CollectionClock
import com.openlattice.chronicle.collection.core.CollectionLog
import com.openlattice.chronicle.collection.core.ModuleResult
import com.openlattice.chronicle.collection.core.PullCollectionModule
import com.openlattice.chronicle.collection.state.writeObservation
import com.openlattice.chronicle.collection.sink.DeviceSettingsSampleSink
import com.openlattice.chronicle.storage.DeviceSettingsSampleEntry
import com.openlattice.chronicle.storage.UploadServerEntity
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.util.TimeZone
import java.util.UUID

private const val TAG = "DeviceSettingsCollectionModule"

/**
 * The `device_settings` collection module — a **pull-style** module mirroring
 * `BatteryTelemetryCollectionModule`. Each [sample] reads a device-settings snapshot through the
 * injected [DeviceSettingsSource] and persists one row. DEVICE_STATE_METADATA-class (default OFF).
 */
public class DeviceSettingsCollectionModule(
    private val sink: DeviceSettingsSampleSink,
    private val source: DeviceSettingsSource,
    private val enrolled: () -> Boolean,
    private val ownerForSample: () -> UploadServerEntity? = { null },
    private val accountWriteResult: (UploadServerEntity?, ModuleResult, Int) -> Unit = { _, _, _ -> },
    clock: CollectionClock = CollectionClock.SYSTEM,
    private val log: CollectionLog = CollectionLog.LOGCAT,
) : PullCollectionModule(CollectionModuleId.DEVICE_SETTINGS, clock, sink::queueDepth) {

    override fun runSample(now: Long): ModuleResult {
        if (!enrolled()) return ModuleResult.Skipped("participant not enrolled")
        val observation = sink.captureAdmission()
        val owner = ownerForSample()

        val reading: DeviceSettingsReading? = try {
            source.read()
        } catch (e: Exception) {
            log.error(TAG, "Device-settings source threw while reading state", e)
            return ModuleResult.Failed(e, redactedMessage = "device settings source read failed: ${e.javaClass.simpleName}")
        }
        if (reading == null) {
            log.warn(TAG, "Device settings unavailable; will retry on the next poll")
            return ModuleResult.Retry("device settings unavailable")
        }

        val entry = DeviceSettingsSampleEntry(
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
        )

        return when (val writeResult = observation.writeObservation { sink.write(listOf(entry)) }.also {
            accountWriteResult(owner, it, 1)
        }) {
            is ModuleResult.Ok -> {
                log.info(TAG, "Persisted 1 device-settings snapshot")
                ModuleResult.Ok(1)
            }
            is ModuleResult.Failed -> {
                log.error(TAG, "Failed to persist device-settings snapshot", writeResult.error)
                writeResult
            }
            else -> writeResult
        }
    }

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
