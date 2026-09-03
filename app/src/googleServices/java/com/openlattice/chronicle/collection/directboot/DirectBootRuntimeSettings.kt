package com.openlattice.chronicle.collection.directboot

import com.openlattice.chronicle.android.AndroidSensorType
import com.openlattice.chronicle.collection.sensors.SensorRuntimeSettings
import com.openlattice.chronicle.preferences.DirectBootSensorSnapshot

/**
 * [SensorRuntimeSettings] for the direct-boot window, backed by the device-protected
 * [DirectBootSensorSnapshot] instead of the credential-encrypted `SensorSettings` (which
 * cannot be opened before first unlock).
 *
 * The snapshot's collectable set already has the per-sensor collection gate applied (it is
 * written from live gate reads in unlocked mode), so the direct-boot runtime uses the same
 * set for both "which sensors to attempt" and the gate predicate — gate state cannot change
 * while the device is still locked.
 */
class DirectBootRuntimeSettings(
    private val snapshot: DirectBootSensorSnapshot,
) : SensorRuntimeSettings {

    override fun enabledSensors(): Set<AndroidSensorType> = snapshot.collectableSensors()

    override fun samplingRateHz(sensor: AndroidSensorType): Int =
        snapshot.config(sensor).samplingRateHz

    override fun dutyCycleActiveSeconds(sensor: AndroidSensorType): Int =
        snapshot.config(sensor).dutyCycleActiveSeconds

    override fun dutyCyclePeriodSeconds(sensor: AndroidSensorType): Int =
        snapshot.config(sensor).dutyCyclePeriodSeconds
}
