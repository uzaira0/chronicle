package com.openlattice.chronicle.services.sensors

import com.openlattice.chronicle.android.InteractionPointerCaptureCapability
import org.junit.Assert.assertEquals
import org.junit.Test

class SensorAvailabilityReporterTest {
    @Test
    fun preAndroid14ReportsPointerAPIUnavailable() {
        assertEquals(
            InteractionPointerCaptureCapability.PLATFORM_API_UNAVAILABLE,
            SensorAvailabilityReporter.interactionPointerCapabilityFor(33),
        )
    }

    @Test
    fun android14AndLaterReportThatPointerCaptureRequiresInterception() {
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
