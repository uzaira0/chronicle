package com.openlattice.chronicle.services.upload

import android.app.ApplicationExitInfo
import com.openlattice.chronicle.collection.AndroidUploadDiagnosticEvent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

@org.junit.runner.RunWith(org.robolectric.RobolectricTestRunner::class)
@org.robolectric.annotation.Config(sdk = [26])
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
        val byIssue = store.pending().groupBy { it.issue }
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
        assertEquals(2, store.pending().filter { it.issue == "APP_CRASH" }.sumOf { it.count })
    }

    @Test
    fun historicalExitsBeforeTheEnrollmentFloorAreNeverAttributedToItsOwner() {
        val store = LocalUploadDiagnosticsStore(MemoryPersistence())
        val floor = now - 1_000
        recordProcessExits(store, listOf(
            ProcessExit(ApplicationExitInfo.REASON_CRASH, floor - 1),
            ProcessExit(ApplicationExitInfo.REASON_ANR, floor + 1),
        ), watermarkMillis = 0, admissionFloor = floor)
        val pending = store.pending()
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
        assertTrue(store.pending().isEmpty())
    }

    @Test
    fun crashBetweenDiagnosticAndWatermarkDoesNotCountTheSameExitTwice() {
        val store = LocalUploadDiagnosticsStore(MemoryPersistence())
        val exit = ProcessExit(ApplicationExitInfo.REASON_CRASH, now)

        recordProcessExits(store, listOf(exit), watermarkMillis = 0)
        recordProcessExits(store, listOf(exit), watermarkMillis = 0)

        assertEquals(1, store.pending().single().count)
    }

    @Test fun everyPreRCrashBeforeImportIsKept() {
        val context = androidx.test.core.app.ApplicationProvider.getApplicationContext<android.content.Context>()
        val prefs = context.createDeviceProtectedStorageContext()
            .getSharedPreferences(PROCESS_EXIT_PREFS, android.content.Context.MODE_PRIVATE)
        prefs.edit().clear().commit()
        val original = Thread.getDefaultUncaughtExceptionHandler()
        try {
            Thread.setDefaultUncaughtExceptionHandler { _, _ -> }
            installPreAndroid11CrashHandler(context)
            val handler = Thread.getDefaultUncaughtExceptionHandler()!!
            handler.uncaughtException(Thread.currentThread(), IllegalStateException("first"))
            Thread.sleep(2)
            handler.uncaughtException(Thread.currentThread(), IllegalStateException("second"))
        } finally {
            Thread.setDefaultUncaughtExceptionHandler(original)
        }
        assertEquals(2, prefs.all.values.filterIsInstance<Long>().distinct().size)
        val store = LocalUploadDiagnosticsStore(MemoryPersistence())
        val exits = pendingPreRCrashes(prefs).values.map { ProcessExit(ApplicationExitInfo.REASON_CRASH, it) }
        // An import interrupted before its keys are dropped replays without a second count.
        repeat(2) { recordProcessExits(store, exits, watermarkMillis = 0) }
        assertEquals(2, store.pending().sumOf { it.count })
    }

    @Test fun preRCrashHandlerStoresOnlyATimestampAndChainsThePreviousHandler() {
        val context = androidx.test.core.app.ApplicationProvider.getApplicationContext<android.content.Context>()
        val prefs = context.createDeviceProtectedStorageContext()
            .getSharedPreferences(PROCESS_EXIT_PREFS, android.content.Context.MODE_PRIVATE)
        prefs.edit().clear().commit()
        val failure = IllegalStateException("private-crash-sentinel")
        var chained = 0
        val previous = Thread.UncaughtExceptionHandler { thread, error ->
            assertEquals(Thread.currentThread(), thread); assertEquals(failure, error); chained++
        }
        val original = Thread.getDefaultUncaughtExceptionHandler()
        try {
            Thread.setDefaultUncaughtExceptionHandler(previous)
            installPreAndroid11CrashHandler(context)
            Thread.getDefaultUncaughtExceptionHandler()!!.uncaughtException(Thread.currentThread(), failure)
        } finally {
            Thread.setDefaultUncaughtExceptionHandler(original)
        }
        assertEquals(1, chained)
        val timestamp = pendingPreRCrashes(prefs).values.single()
        assertEquals(setOf("$PRE_R_CRASH_PREFIX$timestamp"), prefs.all.keys)
        assertTrue(timestamp > 0)
        val store = LocalUploadDiagnosticsStore(MemoryPersistence())
        val exit = ProcessExit(ApplicationExitInfo.REASON_CRASH, timestamp)
        val watermark = recordProcessExits(store, listOf(exit), 0)
        recordProcessExits(store, listOf(exit), watermark)
        assertEquals(1, store.pending().single().count)
    }
}
