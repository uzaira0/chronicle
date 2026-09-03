package com.openlattice.chronicle.services.upload

import java.time.LocalDate
import java.net.SocketTimeoutException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LocalUploadDiagnosticsStoreTest {
    @Test
    fun closedDestinationIssuePersistsAcrossLaterSuccessfulRuns() {
        val persistence = FakePersistence()
        val store = LocalUploadDiagnosticsStore(persistence)
        val day = LocalDate.parse("2026-08-22")

        store.record(
            LocalUploadModuleFamily.USAGE_LIFECYCLE,
            UploadDestinationIssue.DESTINATION_MISSING,
            day,
        )
        store.record(
            LocalUploadModuleFamily.USAGE_LIFECYCLE,
            UploadDestinationIssue.DESTINATION_MISSING,
            day,
        )

        val bucket = store.recent(today = day).single()
        assertEquals("2026-08-22", bucket.day)
        assertEquals("USAGE_LIFECYCLE", bucket.moduleFamily)
        assertEquals("DESTINATION_MISSING", bucket.issue)
        assertEquals(2, bucket.count)
        assertTrue(runCatching { java.util.UUID.fromString(bucket.id) }.isSuccess)
    }

    @Test
    fun historyIsBoundedValidatedAndClearedAtTheEnrollmentBoundary() {
        val persistence = FakePersistence().apply {
            buckets = listOf(
                LocalUploadIssueBucket("2026-08-22", "BATTERY", "DESTINATION_DISABLED", 1),
                LocalUploadIssueBucket("2026-07-01", "BATTERY", "DESTINATION_DISABLED", 4),
                LocalUploadIssueBucket("2026-08-22", "UNKNOWN", "DESTINATION_DISABLED", 3),
                LocalUploadIssueBucket("2026-08-22", "BATTERY", "raw-error-text", 5),
                LocalUploadIssueBucket("2026-08-23", "BATTERY", "DESTINATION_DISABLED", 6),
            )
        }
        val store = LocalUploadDiagnosticsStore(persistence)

        assertEquals(1, store.recent(today = LocalDate.parse("2026-08-22")).single().count)
        assertEquals(1, persistence.buckets.size)
        store.clear()
        assertTrue(store.recent(today = LocalDate.parse("2026-08-22")).isEmpty())
    }

    @Test
    fun localHistoryHasAHardBucketCap() {
        val day = LocalDate.parse("2026-08-22")
        val persistence = FakePersistence().apply {
            buckets = List(600) { index ->
                LocalUploadIssueBucket(
                    day = day.toString(),
                    moduleFamily = "BATTERY",
                    issue = "UPLOAD_FAILURE",
                    count = 1,
                    errorType = "Failure$index",
                )
            }
        }
        val store = LocalUploadDiagnosticsStore(persistence)

        store.record(LocalUploadModuleFamily.BATTERY, UploadDestinationIssue.DESTINATION_MISSING, day)

        assertEquals(500, persistence.buckets.size)
    }

    @Test
    fun acknowledgmentRemovesOnlyServerAcknowledgedAggregates() {
        val persistence = FakePersistence()
        val store = LocalUploadDiagnosticsStore(persistence)
        val day = LocalDate.parse("2026-08-22")
        store.record(LocalUploadModuleFamily.BATTERY, UploadDestinationIssue.DESTINATION_MISSING, day)
        store.record(LocalUploadModuleFamily.DEVICE_TELEMETRY, UploadDestinationIssue.DESTINATION_MISSING, day)

        val pending = store.pending(today = day)
        store.acknowledge(setOf(pending.first().id))

        val remaining = store.pending(today = day)
        assertEquals(1, remaining.size)
        assertFalse(remaining.single().id == pending.first().id)
    }

    @Test
    fun failureDetailsAreClassifiedWithoutPersistingFreeFormText() {
        assertEquals("TIMEOUT", classifyUploadFailure(SocketTimeoutException("late")))
        val fields = LocalUploadIssueBucket::class.java.declaredFields.mapTo(mutableSetOf()) { it.name }
        assertFalse("errorMessage" in fields)
        assertFalse("serverOrigin" in fields)
    }

    private class FakePersistence : LocalUploadDiagnosticsPersistence {
        var buckets: List<LocalUploadIssueBucket> = emptyList()

        override fun load(): List<LocalUploadIssueBucket> = buckets

        override fun save(buckets: List<LocalUploadIssueBucket>) {
            this.buckets = buckets
        }
    }
}
