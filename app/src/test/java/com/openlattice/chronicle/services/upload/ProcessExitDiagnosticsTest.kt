package com.openlattice.chronicle.services.upload

import android.app.ApplicationExitInfo
import com.openlattice.chronicle.collection.AndroidUploadDiagnosticEvent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

class ProcessExitDiagnosticsTest {
    private class MemoryPersistence : LocalUploadDiagnosticsPersistence {
        var buckets: List<LocalUploadIssueBucket> = emptyList()
        override fun load() = buckets
        override fun save(buckets: List<LocalUploadIssueBucket>) {
            this.buckets = buckets
        }
    }

    private val now = System.currentTimeMillis()

    @Test
    fun crashesAndAnrsNewerThanTheWatermarkBecomeRedactedCounts() {
        val store = LocalUploadDiagnosticsStore(MemoryPersistence())
        val exits = listOf(
            ProcessExit(ApplicationExitInfo.REASON_CRASH, now - 3_000),
            ProcessExit(ApplicationExitInfo.REASON_CRASH, now - 2_000),
            ProcessExit(ApplicationExitInfo.REASON_CRASH_NATIVE, now - 1_500),
            ProcessExit(ApplicationExitInfo.REASON_ANR, now - 1_000),
            ProcessExit(ApplicationExitInfo.REASON_USER_REQUESTED, now - 500),
            ProcessExit(ApplicationExitInfo.REASON_CRASH, now - 10_000), // already reported
        )

        val watermark = recordProcessExits(store, exits, watermarkMillis = now - 5_000)

        assertEquals(now - 500, watermark)
        val today = Instant.ofEpochMilli(now).atZone(ZoneId.systemDefault()).toLocalDate()
        val byIssue = store.pending(today).associate { it.issue to it }
        assertEquals(setOf("APP_CRASH", "APP_CRASH_NATIVE", "APP_ANR"), byIssue.keys)
        assertEquals(2, byIssue.getValue("APP_CRASH").count)
        assertEquals(1, byIssue.getValue("APP_ANR").count)
        byIssue.values.forEach { bucket ->
            assertEquals("APP_RUNTIME", bucket.moduleFamily)
            assertNull(bucket.errorType)
            assertNull(bucket.httpStatus)
        }

        // A second pass with the advanced watermark adds nothing.
        recordProcessExits(store, exits, watermark)
        assertEquals(2, store.pending(today).first { it.issue == "APP_CRASH" }.count)
    }

    @Test
    fun wirePayloadHasNoMessageOrStackField() {
        val fields = AndroidUploadDiagnosticEvent::class.java.declaredFields.map { it.name.lowercase() }
        assertTrue(fields.none { "message" in it || "stack" in it || "trace" in it || "description" in it })
    }

    @Test
    fun noExitsLeavesTheWatermarkAlone() {
        val store = LocalUploadDiagnosticsStore(MemoryPersistence())
        assertEquals(42L, recordProcessExits(store, emptyList(), 42L))
        assertTrue(store.pending(LocalDate.now()).isEmpty())
    }
}
