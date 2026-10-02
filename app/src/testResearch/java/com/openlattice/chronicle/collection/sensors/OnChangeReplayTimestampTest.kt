@file:Suppress("DEPRECATION")

package com.openlattice.chronicle.collection.sensors

import android.app.Application
import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.SystemClock
import androidx.test.core.app.ApplicationProvider
import com.openlattice.chronicle.android.AndroidSensorType
import com.openlattice.chronicle.collection.state.CollectionPersistenceGuard
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowSensor
import org.robolectric.util.ReflectionHelpers
import java.time.Duration
import java.time.OffsetDateTime
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [28])
class OnChangeReplayTimestampTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val manager = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
    private val captured = mutableListOf<OffsetDateTime>()

    private val gateway = AndroidSensorGateway(context, object : SensorGateway.SampleListener {
        override fun onSample(sensorType: AndroidSensorType, values: FloatArray, accuracy: Int, timestamp: OffsetDateTime) = Unit
        override fun onTrigger(sensorType: AndroidSensorType, values: FloatArray, timestamp: OffsetDateTime) = Unit
        override fun onCapturedSample(sensorType: AndroidSensorType, values: FloatArray, accuracy: Int?,
                                      timestamp: OffsetDateTime, origin: CollectionPersistenceGuard) {
            captured += timestamp
        }
        override fun onPersistentRegistrationLost(sensorType: AndroidSensorType) = Unit
    })

    private fun deliver(eventNanos: Long) {
        @Suppress("UNCHECKED_CAST")
        val listeners = gateway.javaClass.getDeclaredField("persistentListeners").apply { isAccessible = true }
            .get(gateway) as Map<AndroidSensorType, SensorEventListener>
        val event = ReflectionHelpers.callConstructor(SensorEvent::class.java,
            ReflectionHelpers.ClassParameter.from(Int::class.javaPrimitiveType, 1))
        ReflectionHelpers.setField(event, "sensor", manager.getDefaultSensor(Sensor.TYPE_STEP_COUNTER))
        ReflectionHelpers.setField(event, "timestamp", eventNanos)
        event.values[0] = 54f
        listeners.getValue(AndroidSensorType.stepCounter).onSensorChanged(event)
    }

    @Test
    fun stepCounterReplayOfAStepBeforeRegistrationIsStampedAtRegistration() {
        shadowOf(manager).addSensor(ShadowSensor.newInstance(Sensor.TYPE_STEP_COUNTER))
        SystemClock.setCurrentTimeMillis(SystemClock.elapsedRealtime() + TimeUnit.HOURS.toMillis(20))
        val registeredAt = OffsetDateTime.now()
        val registeredNanos = SystemClock.elapsedRealtimeNanos()
        try {
            assertTrue(gateway.registerPersistentSensor(AndroidSensorType.stepCounter))
            // The last step was 20 hours before registration (before enrollment, on the Pixel trial).
            deliver(registeredNanos - TimeUnit.HOURS.toNanos(20))

            assertEquals(1, captured.size)
            assertTrue("replay ${captured[0]} was not stamped at registration $registeredAt",
                Duration.between(registeredAt, captured[0]).abs() < Duration.ofSeconds(1))

            // Only the replay is clamped; a later event keeps its own (earlier) time.
            deliver(registeredNanos - TimeUnit.HOURS.toNanos(1))
            assertTrue("later event ${captured[1]} was clamped to registration",
                Duration.between(captured[1], registeredAt) > Duration.ofMinutes(59))
        } finally {
            gateway.unregisterAll()
        }
    }
}
