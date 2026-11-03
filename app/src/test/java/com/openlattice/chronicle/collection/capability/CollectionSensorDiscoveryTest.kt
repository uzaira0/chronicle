package com.openlattice.chronicle.collection.capability

import android.app.Application
import android.hardware.Sensor
import android.hardware.SensorManager
import androidx.test.core.app.ApplicationProvider
import com.openlattice.chronicle.android.AndroidSensorType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements
import org.robolectric.shadows.ShadowSensorManager

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, shadows = [
    CollectionSensorDiscoveryTest.FailedDiscovery::class,
    CollectionSensorDiscoveryTest.SystemFailedDiscovery::class,
])
class CollectionSensorDiscoveryTest {
    @Implements(SensorManager::class)
    open class FailedDiscovery : ShadowSensorManager() {
        companion object { var failure: Throwable? = null }
        @Implementation override fun getDefaultSensor(type: Int): Sensor? {
            failure?.let { throw it }
            return super.getDefaultSensor(type)
        }
    }

    // Bind the concrete service too, as in the existing trigger-sensor regression fixture.
    @Implements(className = "android.hardware.SystemSensorManager")
    class SystemFailedDiscovery : FailedDiscovery()

    @Test fun discoveryFailurePropagatesInsteadOfClaimingAbsentHardware() {
        for (failure in listOf(IllegalStateException("discovery unavailable"), LinkageError("broken sensor provider"))) {
            FailedDiscovery.failure = failure
            try {
                assertThrows(failure.javaClass) {
                    CollectionCapabilityResolver.availableSensors(ApplicationProvider.getApplicationContext())
                }
            } finally {
                FailedDiscovery.failure = null
            }
        }
    }

    @Test fun absentHardwareIsStillAnEmptySensorSet() {
        assertEquals(emptySet<AndroidSensorType>(),
            CollectionCapabilityResolver.availableSensors(ApplicationProvider.getApplicationContext()))
    }
}
