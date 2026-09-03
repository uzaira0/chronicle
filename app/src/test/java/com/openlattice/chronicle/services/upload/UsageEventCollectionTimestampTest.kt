package com.openlattice.chronicle.services.upload

import com.openlattice.chronicle.android.ChronicleUsageEvent
import com.openlattice.chronicle.models.ExtractedUsageEvent
import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.util.UUID

class UsageEventCollectionTimestampTest {

    @Test
    fun queueWriteTimeIsPreservedSeparatelyFromFrameworkEventTime() {
        val eventTime = OffsetDateTime.parse("2026-07-15T08:00:00Z")
        val queueWriteTime = OffsetDateTime.parse("2026-07-15T08:03:14.321Z")
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
            queueWriteTimestamp = queueWriteTime.toInstant().toEpochMilli(),
        ).single() as ChronicleUsageEvent

        assertEquals(eventTime, uploaded.timestamp)
        assertEquals(queueWriteTime.withOffsetSameInstant(ZoneOffset.UTC), uploaded.collectedAt)
        assertEquals(extracted.eventType, uploaded.eventType)
    }
}
