package com.openlattice.chronicle.sensors

import android.app.usage.UsageEvents
import org.junit.Assert.assertEquals
import org.junit.Test

class UsageEventsChronicleSensorTest {

    @Test
    fun interactionLabelsMatchTheOriginalUpstreamExport() {
        assertEquals("Screen Interactive", usageInteractionType(UsageEvents.Event.SCREEN_INTERACTIVE))
        assertEquals("Screen Non-interactive", usageInteractionType(UsageEvents.Event.SCREEN_NON_INTERACTIVE))
        assertEquals("Keyguard Shown", usageInteractionType(UsageEvents.Event.KEYGUARD_SHOWN))
        assertEquals("Keyguard Hidden", usageInteractionType(UsageEvents.Event.KEYGUARD_HIDDEN))
        assertEquals("Device Startup", usageInteractionType(UsageEvents.Event.DEVICE_STARTUP))
        assertEquals("Device Shutdown", usageInteractionType(UsageEvents.Event.DEVICE_SHUTDOWN))
    }

    @Test
    fun hiddenNotificationEventTypesAreNamedInsteadOfLeakingTheRawCode() {
        assertEquals("Notification Seen", usageInteractionType(USAGE_EVENT_NOTIFICATION_SEEN))
        assertEquals("Notification Interruption", usageInteractionType(USAGE_EVENT_NOTIFICATION_INTERRUPTION))
        assertEquals("Unknown importance: 99", usageInteractionType(99))
    }
}
