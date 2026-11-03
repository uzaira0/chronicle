package com.openlattice.chronicle.services.usage

import com.openlattice.chronicle.android.fromInteractionType

import com.openlattice.chronicle.models.ExtractedUsageEvent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.OffsetDateTime

class UsageCollectionDelegateTest {

    @Test
    fun buildUsageQueueEntriesUsesStrictlyIncreasingWriteTimestamps() {
        val samples = (0 until 2500).map {
            ExtractedUsageEvent(eventType = fromInteractionType("Activity Resumed"),
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
