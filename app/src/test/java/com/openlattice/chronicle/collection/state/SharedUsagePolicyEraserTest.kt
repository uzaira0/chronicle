package com.openlattice.chronicle.collection.state

import com.openlattice.chronicle.collection.CollectionModuleId
import com.openlattice.chronicle.constants.ANDROID_SYSTEM_PACKAGE
import com.openlattice.chronicle.constants.INTERACTION_BATTERY_LOW
import com.openlattice.chronicle.models.ExtractedUsageEvent
import com.openlattice.chronicle.services.lifecycle.INTERACTION_NETWORK_CONNECTED
import java.time.OffsetDateTime
import org.junit.Assert.assertEquals
import org.junit.Test

class SharedUsagePolicyEraserTest {
    private fun event(pkg: String, interaction: String, activity: String? = null) = ExtractedUsageEvent(
        appPackageName = pkg, interactionType = interaction,
        timestamp = OffsetDateTime.parse("2026-01-01T00:00:00Z"), timezone = "UTC",
        user = "", applicationLabel = pkg, activityClass = activity,
    )

    @Test fun disablingUsagePreservesSupplementalLifecycle() {
        assertEquals(SharedUsageDisposition.KEEP,
            sharedUsageDisposition(CollectionModuleId.USAGE_EVENTS,
                event(ANDROID_SYSTEM_PACKAGE, INTERACTION_BATTERY_LOW)))
        assertEquals(SharedUsageDisposition.KEEP,
            sharedUsageDisposition(CollectionModuleId.USAGE_EVENTS,
                event(ANDROID_SYSTEM_PACKAGE, INTERACTION_NETWORK_CONNECTED)))
        assertEquals(SharedUsageDisposition.ERASE,
            sharedUsageDisposition(CollectionModuleId.USAGE_EVENTS,
                event("app.example", "MOVE_TO_FOREGROUND")))
    }

    @Test fun disablingLifecyclePreservesUsageAndActivityClassDecisionRedactsOnlyItsField() {
        val usage = event("app.example", "MOVE_TO_FOREGROUND", "MainActivity")
        assertEquals(SharedUsageDisposition.KEEP,
            sharedUsageDisposition(CollectionModuleId.DEVICE_LIFECYCLE, usage))
        assertEquals(SharedUsageDisposition.REDACT_ACTIVITY_CLASS,
            sharedUsageDisposition(CollectionModuleId.IN_APP_ACTIVITY_CLASS, usage))
    }
}
