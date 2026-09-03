package com.openlattice.chronicle.collection.lifecycle

import com.openlattice.chronicle.services.lifecycle.ANDROID_SYSTEM_LABEL
import com.openlattice.chronicle.services.lifecycle.ANDROID_SYSTEM_PACKAGE
import com.openlattice.chronicle.services.lifecycle.INTERACTION_BATTERY_CHARGING
import com.openlattice.chronicle.services.lifecycle.INTERACTION_BATTERY_DISCHARGING
import com.openlattice.chronicle.services.lifecycle.INTERACTION_BATTERY_LOW
import com.openlattice.chronicle.services.lifecycle.INTERACTION_BATTERY_OKAY
import com.openlattice.chronicle.services.lifecycle.INTERACTION_LOW_MEMORY
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.Instant
import java.util.TimeZone

/**
 * JVM unit coverage for [LifecycleEventMapper] — the Phase 5A centralized event mapping.
 *
 * Asserts supplemental lifecycle mappings preserve their wire shape while the original
 * UsageStats-owned startup/shutdown/screen/keyguard events cannot be emitted by this path.
 */
class LifecycleEventMapperTest {

    private val ts = 1_700_000_000_000L

    private fun event(action: String) =
        LifecycleEventMapper.eventForBroadcastAction(action, ts)
            ?: error("expected a mapped event for $action")

    @Test
    fun originalUsageStatsActionsAreNotMappedBySupplementalCollector() {
        listOf(
            "android.intent.action.BOOT_COMPLETED",
            "android.intent.action.ACTION_SHUTDOWN",
            "android.intent.action.SCREEN_ON",
            "android.intent.action.SCREEN_OFF",
            "android.intent.action.USER_PRESENT",
        ).forEach { action ->
            assertNull(
                "UsageStats-owned action must not be mapped: $action",
                LifecycleEventMapper.eventForBroadcastAction(action, ts),
            )
        }
    }

    @Test
    fun powerConnectedMapsToBatteryCharging() {
        val e = event("android.intent.action.ACTION_POWER_CONNECTED")
        assertEquals(INTERACTION_BATTERY_CHARGING, e.interactionType)
    }

    @Test
    fun powerDisconnectedMapsToBatteryDischarging() {
        val e = event("android.intent.action.ACTION_POWER_DISCONNECTED")
        assertEquals(INTERACTION_BATTERY_DISCHARGING, e.interactionType)
    }

    @Test
    fun batteryLowMapsToBatteryLow() {
        val e = event("android.intent.action.BATTERY_LOW")
        assertEquals(INTERACTION_BATTERY_LOW, e.interactionType)
    }

    @Test
    fun batteryOkayMapsToBatteryOkay() {
        val e = event("android.intent.action.BATTERY_OKAY")
        assertEquals(INTERACTION_BATTERY_OKAY, e.interactionType)
    }

    @Test
    fun unsupportedActionMapsToNull() {
        assertNull(LifecycleEventMapper.eventForBroadcastAction("not.a.lifecycle.action", ts))
        assertNull(LifecycleEventMapper.eventForBroadcastAction(null, ts))
    }

    @Test
    fun lowMemoryMapsToLowMemoryWithCoarseTrimLevel() {
        val e = LifecycleEventMapper.lowMemoryEvent(80, ts)
        assertEquals(INTERACTION_LOW_MEMORY, e.interactionType)
        assertEquals("memory:trim-level:80", e.activityClass)
    }

    @Test
    fun everyMappedEventUsesAndroidSystemPackageAndLabelAndEmptyUser() {
        val actions = listOf(
            "android.intent.action.ACTION_POWER_CONNECTED",
            "android.intent.action.ACTION_POWER_DISCONNECTED",
            "android.intent.action.BATTERY_LOW",
            "android.intent.action.BATTERY_OKAY",
        )
        for (action in actions) {
            val e = event(action)
            assertEquals("package for $action", ANDROID_SYSTEM_PACKAGE, e.appPackageName)
            assertEquals("label for $action", ANDROID_SYSTEM_LABEL, e.applicationLabel)
            assertEquals("user for $action", "", e.user)
        }
        // low-memory event carries the same system identity.
        val mem = LifecycleEventMapper.lowMemoryEvent(40, ts)
        assertEquals(ANDROID_SYSTEM_PACKAGE, mem.appPackageName)
        assertEquals(ANDROID_SYSTEM_LABEL, mem.applicationLabel)
        assertEquals("", mem.user)
    }

    @Test
    fun buildEventUsesUtcTimestampAndDeviceTimezone() {
        val e = LifecycleEventMapper.buildEvent("network:wifi", "Network Connected", ts)
        assertEquals(Instant.ofEpochMilli(ts), e.timestamp.toInstant())
        // Timestamp is normalized to UTC offset.
        assertEquals(0, e.timestamp.offset.totalSeconds)
        // Timezone is the device default zone id (unchanged from the legacy recorder).
        assertEquals(TimeZone.getDefault().id, e.timezone)
    }
}
