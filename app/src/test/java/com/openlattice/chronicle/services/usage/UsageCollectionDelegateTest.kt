package com.openlattice.chronicle.services.usage

import com.openlattice.chronicle.android.ChronicleSample
import com.openlattice.chronicle.models.ExtractedUsageEvent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.OffsetDateTime

class UsageCollectionDelegateTest {

    private fun usageEvent(activityClass: String?) = ExtractedUsageEvent(
        appPackageName = "com.example.app",
        interactionType = "Activity Resumed",
        timestamp = OffsetDateTime.parse("2026-06-18T00:00:00Z"),
        timezone = "UTC",
        user = "",
        applicationLabel = "Example",
        activityClass = activityClass,
    )

    /** A non-usage sample (stand-in for a device-state row) that the gate must leave untouched. */
    private object OtherSample : ChronicleSample

    @Test
    fun gateActivityClassStripsClassWhenModuleNotCollected() {
        val events = listOf(usageEvent("com.example.app.FeedActivity"), OtherSample)

        val gated = gateActivityClass(events, collectActivityClass = false)

        // Package-level usage is preserved; only the within-app activity class is removed.
        val usage = gated.filterIsInstance<ExtractedUsageEvent>().single()
        assertNull("activity class must be stripped when in_app_activity_class is off", usage.activityClass)
        assertEquals("com.example.app", usage.appPackageName)
        // Non-usage samples pass through unchanged (same instance).
        assertTrue(gated.any { it === OtherSample })
    }

    @Test
    fun gateActivityClassPreservesClassWhenModuleCollected() {
        val events = listOf(usageEvent("com.example.app.FeedActivity"))

        val gated = gateActivityClass(events, collectActivityClass = true)

        // When enabled the batch is returned untouched (same instances, class intact).
        assertEquals(events, gated)
        assertEquals("com.example.app.FeedActivity", gated.filterIsInstance<ExtractedUsageEvent>().single().activityClass)
    }
    @Test
    fun buildUsageQueueEntriesUsesStrictlyIncreasingWriteTimestamps() {
        val samples = (0 until 2500).map {
            ExtractedUsageEvent(
                appPackageName = "com.example.$it",
                interactionType = "Activity Resumed",
                timestamp = OffsetDateTime.parse("2026-05-19T00:00:00Z"),
                timezone = "UTC",
                user = "",
                applicationLabel = "Example",
                activityClass = "com.example.MainActivity"
            )
        }

        val entries = buildUsageQueueEntries(samples, firstWriteTimestamp = 100) { 1L }

        assertEquals(3, entries.size)
        assertEquals(listOf(100L, 101L, 102L), entries.map { it.writeTimestamp })
        assertTrue(entries.zipWithNext().all { (first, second) -> first.writeTimestamp < second.writeTimestamp })
    }
}
