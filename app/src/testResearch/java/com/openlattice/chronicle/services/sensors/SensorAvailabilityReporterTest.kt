package com.openlattice.chronicle.services.sensors

import com.openlattice.chronicle.android.InteractionPointerCaptureCapability
import org.junit.Assert.assertEquals
import org.junit.Test

class SensorAvailabilityReporterTest {
    @Test
    fun `pre Android 14 reports pointer API unavailable`() {
        assertEquals(
            InteractionPointerCaptureCapability.PLATFORM_API_UNAVAILABLE,
            SensorAvailabilityReporter.interactionPointerCapabilityFor(33),
        )
    }

    @Test
    fun `Android 14 and later report that pointer capture requires interception`() {
        assertEquals(
            InteractionPointerCaptureCapability.REQUIRES_INPUT_INTERCEPTION,
            SensorAvailabilityReporter.interactionPointerCapabilityFor(34),
        )
        assertEquals(
            InteractionPointerCaptureCapability.REQUIRES_INPUT_INTERCEPTION,
            SensorAvailabilityReporter.interactionPointerCapabilityFor(36),
        )
    }
}
