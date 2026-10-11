package com.openlattice.chronicle.collection.sensors

import android.Manifest
import android.app.Application
import android.hardware.Sensor
import android.hardware.SensorManager
import android.os.Build
import androidx.test.core.app.ApplicationProvider
import com.openlattice.chronicle.android.AndroidSensorType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.SensorBuilder

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class SensorPlatformGateTest {
    private val app = ApplicationProvider.getApplicationContext<Application>()
    private val manager = requireNotNull(app.getSystemService(SensorManager::class.java)) { "sensor manager available" }
    private fun gateway(): AndroidSensorGateway = AndroidSensorGateway(app, object : SensorGateway.SampleListener {
        override fun onSample(sensorType: AndroidSensorType, values: FloatArray, accuracy: Int, timestamp: java.time.OffsetDateTime) = Unit
        override fun onTrigger(sensorType: AndroidSensorType, values: FloatArray, timestamp: java.time.OffsetDateTime) = Unit
    })

    @Test @Config(sdk = [Build.VERSION_CODES.P, 29, 36])
    fun stepCounterRequiresActivityPermissionStartingAt29() {
        shadowOf(manager).addSensor(SensorBuilder.newBuilder().setType(Sensor.TYPE_STEP_COUNTER).build())
        val gateway = gateway()
        try {
            shadowOf(app).denyPermissions(Manifest.permission.ACTIVITY_RECOGNITION)
            // Robolectric accepts an unguarded listener; assert our registration decision.
            assertEquals(Build.VERSION.SDK_INT < 29, gateway.registerPersistentSensor(AndroidSensorType.stepCounter))
            shadowOf(app).grantPermissions(Manifest.permission.ACTIVITY_RECOGNITION)
            assertTrue(gateway.registerPersistentSensor(AndroidSensorType.stepCounter))
        } finally { gateway.unregisterAll() }
    }

    @Test @Config(sdk = [30, Build.VERSION_CODES.S, 36])
    fun highRateRegistrationChecksPermissionAndAllowsThe200HzBoundary() {
        shadowOf(manager).addSensor(SensorBuilder.newBuilder().setType(Sensor.TYPE_ACCELEROMETER).build())
        val gateway = gateway()
        try {
            shadowOf(app).denyPermissions(Manifest.permission.HIGH_SAMPLING_RATE_SENSORS)
            assertEquals(Build.VERSION.SDK_INT < Build.VERSION_CODES.S,
                gateway.registerContinuousSensor(AndroidSensorType.accelerometer, 1_000, 0))
            assertTrue(gateway.registerContinuousSensor(AndroidSensorType.accelerometer, 5_000, 0))
            shadowOf(app).grantPermissions(Manifest.permission.HIGH_SAMPLING_RATE_SENSORS)
            assertTrue(gateway.registerContinuousSensor(AndroidSensorType.accelerometer, 1_000, 0))
            shadowOf(manager).setForceListenersToFail(true)
            assertFalse(gateway.registerContinuousSensor(AndroidSensorType.accelerometer, 1_000, 0))
        } finally { gateway.unregisterAll() }
    }
}
