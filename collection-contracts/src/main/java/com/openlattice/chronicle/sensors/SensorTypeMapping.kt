package com.openlattice.chronicle.sensors

import android.hardware.Sensor
import com.openlattice.chronicle.android.AndroidSensorType

object SensorTypeMapping {
    private const val TYPE_TILT_DETECTOR = 22
    private const val TYPE_SCREEN_ORIENTATION = 27
    private const val TYPE_SAMSUNG_MOTION = 65559
    private const val TYPE_SAMSUNG_GRIP_WIFI = 65575

    private val TYPE_MAP = mapOf(
        AndroidSensorType.accelerometer to Sensor.TYPE_ACCELEROMETER,
        AndroidSensorType.gyroscope to Sensor.TYPE_GYROSCOPE,
        AndroidSensorType.magnetometer to Sensor.TYPE_MAGNETIC_FIELD,
        AndroidSensorType.gravity to Sensor.TYPE_GRAVITY,
        AndroidSensorType.linearAcceleration to Sensor.TYPE_LINEAR_ACCELERATION,
        AndroidSensorType.rotationVector to Sensor.TYPE_ROTATION_VECTOR,
        AndroidSensorType.stepCounter to Sensor.TYPE_STEP_COUNTER,
        AndroidSensorType.light to Sensor.TYPE_LIGHT,
        AndroidSensorType.proximity to Sensor.TYPE_PROXIMITY,
        AndroidSensorType.significantMotion to Sensor.TYPE_SIGNIFICANT_MOTION,
        AndroidSensorType.tiltDetector to TYPE_TILT_DETECTOR,
        AndroidSensorType.screenOrientation to TYPE_SCREEN_ORIENTATION,
        AndroidSensorType.samsungGripWifi to TYPE_SAMSUNG_GRIP_WIFI,
        AndroidSensorType.samsungMotion to TYPE_SAMSUNG_MOTION
    )

    private val VALUE_COUNT = mapOf(
        AndroidSensorType.accelerometer to 3,
        AndroidSensorType.gyroscope to 3,
        AndroidSensorType.magnetometer to 3,
        AndroidSensorType.gravity to 3,
        AndroidSensorType.linearAcceleration to 3,
        AndroidSensorType.rotationVector to 4,
        AndroidSensorType.stepCounter to 1,
        AndroidSensorType.light to 1,
        AndroidSensorType.proximity to 1,
        AndroidSensorType.significantMotion to 1,
        AndroidSensorType.tiltDetector to 1,
        AndroidSensorType.screenOrientation to 1,
        AndroidSensorType.samsungGripWifi to 16,
        AndroidSensorType.samsungMotion to 4
    )

    fun toAndroidType(sensorType: AndroidSensorType): Int {
        return TYPE_MAP[sensorType]
            ?: throw IllegalArgumentException("Unknown sensor type: $sensorType")
    }

    fun valueCount(sensorType: AndroidSensorType): Int {
        return VALUE_COUNT[sensorType] ?: 3
    }

    fun fromAndroidType(androidType: Int): AndroidSensorType? {
        return TYPE_MAP.entries.firstOrNull { it.value == androidType }?.key
    }

    fun isTriggerSensor(sensorType: AndroidSensorType): Boolean {
        return sensorType == AndroidSensorType.significantMotion
    }

    /**
     * Continuous-streaming sensors (Android `REPORTING_MODE_CONTINUOUS`) — they emit a
     * steady stream at the requested rate, so sampling them in short duty-cycle bursts
     * still captures a representative signal and saves battery.
     */
    private val CONTINUOUS_SENSORS = setOf(
        AndroidSensorType.accelerometer,
        AndroidSensorType.gyroscope,
        AndroidSensorType.magnetometer,
        AndroidSensorType.gravity,
        AndroidSensorType.linearAcceleration,
        AndroidSensorType.rotationVector,
    )

    /**
     * Whether [sensorType] streams continuously and is therefore safe to duty-cycle.
     *
     * Everything that is **not** continuous — on-change (`light`, `proximity`,
     * `stepCounter`, `screenOrientation`, the Samsung vendor sensors), one-shot
     * (`significantMotion`) and special-trigger (`tiltDetector`) sensors — emits only on a
     * discrete physical event. Duty-cycling those (arming ~8% of the time, then tearing the
     * listener down every idle window) loses almost every event, so they must be registered
     * **persistently** and left armed across idle windows. These are low-power,
     * hardware-backed sensors designed to stay always-on, so the battery cost of keeping
     * them armed is negligible.
     */
    fun isContinuousSensor(sensorType: AndroidSensorType): Boolean = sensorType in CONTINUOUS_SENSORS

    /**
     * Whether [sensorType] must be registered persistently (always-armed) rather than
     * duty-cycled — the complement of [isContinuousSensor]. Persistent sensors are the
     * on-change / one-shot / special-trigger sensors; the gateway arms one-shot trigger
     * sensors ([isTriggerSensor]) via `requestTriggerSensor` and the rest via a persistent
     * `registerListener`.
     */
    fun isPersistentSensor(sensorType: AndroidSensorType): Boolean = !isContinuousSensor(sensorType)
}
