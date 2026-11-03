package com.openlattice.chronicle.services.upload

import com.openlattice.chronicle.android.ChronicleUsageEvent
import com.openlattice.chronicle.models.ExtractedUsageEvent
import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.OffsetDateTime
import java.util.UUID

class UsageEventCollectionTimestampTest {

    @Test
    fun uploadCarriesOnlyTheFrameworkEventTime() {
        val eventTime = OffsetDateTime.parse("2026-07-15T08:00:00Z")
        val extracted = ExtractedUsageEvent(
            appPackageName = "com.example.app",
            interactionType = "Move to Foreground",
            eventType = 1,
            timestamp = eventTime,
            timezone = "America/Chicago",
            user = "participant",
            applicationLabel = "Example",
            activityClass = "com.example.MainActivity",
        )

        val uploaded = mapUsageSamplesForUpload(
            data = listOf(extracted),
            studyId = UUID.randomUUID(),
            participantId = "P001",
        ).single() as ChronicleUsageEvent

        assertEquals(eventTime, uploaded.timestamp)
        // One clock: no phone queue-write time travels beside the event time.
        assertEquals(setOf("studyId", "participantId", "appPackageName", "interactionType", "eventType", "timestamp",
            "timezone", "user", "applicationLabel", "activityClass", "clientEventId"),
            ChronicleUsageEvent::class.java.declaredFields.map { it.name }.filterNot { it.startsWith("$") }.toSet())
        assertEquals(extracted.eventType, uploaded.eventType)
        assertEquals(extracted.activityClass, uploaded.activityClass)
    }
}
