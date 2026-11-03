package com.openlattice.chronicle.sensors

import android.hardware.Sensor
import com.openlattice.chronicle.android.AndroidSensorType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SensorTypeMappingTest {
    @Test
    fun mapsStandardAndTabletSpecificSensors() {
        assertEquals(Sensor.TYPE_SIGNIFICANT_MOTION, SensorTypeMapping.toAndroidType(AndroidSensorType.significantMotion))
        assertEquals(AndroidSensorType.tiltDetector, SensorTypeMapping.fromAndroidType(22))
        assertEquals(AndroidSensorType.screenOrientation, SensorTypeMapping.fromAndroidType(27))
    }



    @Test
    fun treatsSignificantMotionAsTriggerSensor() {
        assertTrue(SensorTypeMapping.isTriggerSensor(AndroidSensorType.significantMotion))
    }

    @Test
    fun classifiesContinuousStreamingSensorsAsDutyCycleable() {
        listOf(
            AndroidSensorType.accelerometer,
            AndroidSensorType.gyroscope,
            AndroidSensorType.magnetometer,
            AndroidSensorType.gravity,
            AndroidSensorType.linearAcceleration,
            AndroidSensorType.rotationVector,
        ).forEach {
            assertTrue("$it should be continuous", SensorTypeMapping.isContinuousSensor(it))
            assertFalse("$it should not be persistent", SensorTypeMapping.isPersistentSensor(it))
        }
    }

    @Test
    fun classifiesEventSensorsAsPersistentNotDutyCycleable() {
        // On-change / one-shot / special-trigger sensors must be armed persistently — these
        // are exactly the ones that collected zero data under the old duty-cycle-everything path.
        listOf(
            AndroidSensorType.significantMotion,
            AndroidSensorType.tiltDetector,
            AndroidSensorType.screenOrientation,
            AndroidSensorType.stepCounter,
            AndroidSensorType.light,
            AndroidSensorType.proximity,
        ).forEach {
            assertFalse("$it should not be continuous", SensorTypeMapping.isContinuousSensor(it))
            assertTrue("$it should be persistent", SensorTypeMapping.isPersistentSensor(it))
        }
    }
}
