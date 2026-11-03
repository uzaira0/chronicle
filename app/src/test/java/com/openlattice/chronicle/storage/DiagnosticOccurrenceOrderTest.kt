package com.openlattice.chronicle.storage

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** Pending diagnostics upload oldest first, whatever offset each writer stamped its occurrence with. */
@RunWith(RobolectricTestRunner::class)
class DiagnosticOccurrenceOrderTest {
    private val db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext<Context>(), ChronicleDb::class.java)
        .allowMainThreadQueries().build()

    @After fun tearDown() = db.close()

    private fun occurrence(id: String, at: String) = UploadDiagnosticEntity(id, "study", "participant", "device", "epoch",
        "2026-10-28", "USAGE", "TIMEOUT", 1, at, at, null, null)

    @Test fun pendingOccurrencesComeInTimeOrder() {
        val dao = db.uploadDiagnosticDao()
        // Santiago local time (13:00Z), UTC without seconds (12:30Z) and UTC with a fraction (12:45:00.5Z).
        dao.upsert(occurrence("santiago", "2026-10-28T10:00:00-03:00"))
        dao.insertIfAbsent(occurrence("no-seconds", "2026-10-28T12:30Z"))
        dao.upsert(occurrence("fraction", "2026-10-28T12:45:00.5Z"))
        val pending = dao.pending("study", "participant", "device", "epoch")
        assertEquals(listOf("no-seconds", "fraction", "santiago"), pending.map { it.id })
        assertEquals(listOf("2026-10-28T12:30:00.000Z", "2026-10-28T12:45:00.500Z", "2026-10-28T13:00:00.000Z"),
            pending.map { it.firstOccurredAt })
    }
}
