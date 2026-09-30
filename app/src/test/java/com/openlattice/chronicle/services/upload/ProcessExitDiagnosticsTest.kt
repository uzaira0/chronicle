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
        val byIssue = store.pending(today).groupBy { it.issue }
        assertEquals(setOf("APP_CRASH", "APP_CRASH_NATIVE", "APP_ANR"), byIssue.keys)
        assertEquals(2, byIssue.getValue("APP_CRASH").sumOf { it.count })
        assertEquals(1, byIssue.getValue("APP_ANR").sumOf { it.count })
        byIssue.values.flatten().forEach { bucket ->
            assertEquals("APP_RUNTIME", bucket.moduleFamily)
            assertNull(bucket.errorType)
            assertNull(bucket.httpStatus)
        }

        // A second pass with the advanced watermark adds nothing.
        recordProcessExits(store, exits, watermark)
        assertEquals(2, store.pending(today).filter { it.issue == "APP_CRASH" }.sumOf { it.count })
    }

    @Test
    fun historicalExitsBeforeTheEnrollmentFloorAreNeverAttributedToItsOwner() {
        val store = LocalUploadDiagnosticsStore(MemoryPersistence())
        val floor = now - 1_000
        recordProcessExits(store, listOf(
            ProcessExit(ApplicationExitInfo.REASON_CRASH, floor - 1),
            ProcessExit(ApplicationExitInfo.REASON_ANR, floor + 1),
        ), watermarkMillis = 0, admissionFloor = floor)
        val pending = store.pending(LocalDate.now())
        assertEquals(1, pending.size)
        assertEquals("APP_ANR", pending.single().issue)
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

    @Test
    fun crashBetweenDiagnosticAndWatermarkDoesNotCountTheSameExitTwice() {
        val store = LocalUploadDiagnosticsStore(MemoryPersistence())
        val exit = ProcessExit(ApplicationExitInfo.REASON_CRASH, now)

        recordProcessExits(store, listOf(exit), watermarkMillis = 0)
        recordProcessExits(store, listOf(exit), watermarkMillis = 0)

        assertEquals(1, store.pending(LocalDate.now()).single().count)
    }
}
