package com.openlattice.chronicle.ui

import com.openlattice.chronicle.services.upload.LocalUploadIssueBucket
import com.openlattice.chronicle.storage.UploadStatsEntity
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class UploadDiagnosticsSummaryTest {

    @Test
    fun recoveredFailureRemainsVisibleInCumulativeAndRecentLocalHistory() {
        val stats = UploadStatsEntity(
            serverId = 1L,
            date = "2026-08-22",
            usageEventsUploaded = 12,
            sensorSamplesUploaded = 9,
            batterySamplesUploaded = 2,
            usageUploadFailures = 1,
            sensorUploadFailures = 4,
        )
        val daily = formatDailyUploadStats(
            stats,
            includeRestricted = false,
        )
        val summary = renderUploadHistory(
            server = UploadServerSummary(
                id = 1L,
                name = "Example study server",
                url = "https://study.example.org",
                enabled = true,
                healthLabel = "Healthy",
                lastSuccess = "Aug 22, 12:00 PM",
                history = listOf(daily),
                usageItemsUploaded = 12,
                usageFailedAttempts = 1,
                batteryItemsUploaded = 2,
            ),
            includeRestricted = false,
        )

        assertTrue(summary.contains("Example study server - Healthy"))
        assertTrue(summary.contains("Delivered items: usage/lifecycle 12, battery 2"))
        assertTrue(summary.contains("Failed attempts: usage/lifecycle 1, battery 0"))
        assertTrue(summary.contains("failures 1/0"))
        assertFalse(summary.contains("sensor"))

        val researchDaily = formatDailyUploadStats(stats, includeRestricted = true)
        assertTrue(researchDaily.contains("9 sensors"))
        assertTrue(researchDaily.contains("failures 1/4/0"))
    }

    @Test
    fun localUploadDiagnosticsOwnersDoNotCreateNetworkClientsOrQueueSamples() {
        val repository = File(
            "src/main/java/com/openlattice/chronicle/ui/DashboardDataRepository.kt",
        ).readText()
        val screen = File(
            "src/main/java/com/openlattice/chronicle/ui/UploadsFragment.kt",
        ).readText()
        val source = repository + screen

        assertTrue(repository.contains("getRecentStats(server.id, 7)"))
        assertTrue(repository.contains("usageFailedAttempts = server.usageUploadFailureCount"))
        assertTrue(screen.contains("renderUploadHistory"))
        listOf(
            "ChronicleStudyApi",
            "getChronicleStudyApi",
            "OkHttpClient",
            "Retrofit",
            "QueueEntry",
        ).forEach { networkOrQueueOwner ->
            assertFalse(
                "Device-local upload diagnostics must not own network or queue code: " +
                    networkOrQueueOwner,
                source.contains(networkOrQueueOwner),
            )
        }
    }

    @Test
    fun recoveredDestinationFailureRendersOnlyClosedRedactedFields() {
        val rendered = renderLocalUploadIssues(
            listOf(
                LocalUploadIssueBucket(
                    day = "2026-08-22",
                    moduleFamily = "USAGE_LIFECYCLE",
                    issue = "DESTINATION_NONCANONICAL",
                    count = 2,
                ),
            ),
        )

        assertTrue(rendered.contains("2026-08-22: usage/lifecycle"))
        assertTrue(rendered.contains("destination address rejected (2)"))
        listOf("participant", "api-key", "https://", "Exception").forEach { forbidden ->
            assertFalse(rendered.contains(forbidden, ignoreCase = true))
        }
    }
}
