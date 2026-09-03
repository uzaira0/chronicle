package com.openlattice.chronicle.services.lifecycle

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.Instant

class DeviceLifecycleEventRecorderTest {
    @Test
    fun eventForBroadcastIgnoresUsageStatsOwnedShutdown() {
        assertNull(
            DeviceLifecycleEventRecorder.eventForBroadcastAction(
                "android.intent.action.ACTION_SHUTDOWN",
                1_700_000_000_000,
            ),
        )
    }

    @Test
    fun eventForBroadcastMapsBatteryTransitions() {
        val charging = DeviceLifecycleEventRecorder.eventForBroadcastAction(
            "android.intent.action.ACTION_POWER_CONNECTED",
            1_700_000_000_000
        )
        val discharging = DeviceLifecycleEventRecorder.eventForBroadcastAction(
            "android.intent.action.ACTION_POWER_DISCONNECTED",
            1_700_000_000_000
        )

        assertEquals(INTERACTION_BATTERY_CHARGING, charging?.interactionType)
        assertEquals(INTERACTION_BATTERY_DISCHARGING, discharging?.interactionType)
    }

    @Test
    fun eventForBroadcastIgnoresUnsupportedActions() {
        assertNull(DeviceLifecycleEventRecorder.eventForBroadcastAction("not.supported", 1_700_000_000_000))
    }

    @Test
    fun buildEventUsesUtcTimestampAndNoUserAttribution() {
        val event = DeviceLifecycleEventRecorder.buildEvent(
            activityClass = "network:wifi",
            interactionType = INTERACTION_NETWORK_CONNECTED,
            timestampMillis = 1_700_000_000_000
        )

        assertEquals(Instant.ofEpochMilli(1_700_000_000_000), event.timestamp.toInstant())
        assertEquals("network:wifi", event.activityClass)
        assertEquals("", event.user)
    }

    @Test
    fun lowMemoryEventStoresOnlyCoarseTrimLevel() {
        val event = DeviceLifecycleEventRecorder.lowMemoryEvent(80, 1_700_000_000_000)

        assertEquals(INTERACTION_LOW_MEMORY, event.interactionType)
        assertEquals("memory:trim-level:80", event.activityClass)
        assertEquals(ANDROID_SYSTEM_PACKAGE, event.appPackageName)
        assertEquals(ANDROID_SYSTEM_LABEL, event.applicationLabel)
        assertEquals("", event.user)
    }
}
