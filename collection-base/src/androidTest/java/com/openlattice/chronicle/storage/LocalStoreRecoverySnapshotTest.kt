package com.openlattice.chronicle.storage

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.json.JSONObject
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class LocalStoreRecoverySnapshotTest {
    @Test fun exportKeepsDiagnosticNumbersAndQuarantineBytes() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val db = Room.inMemoryDatabaseBuilder(context, ChronicleDb::class.java).allowMainThreadQueries().build()
        try {
            db.uploadDiagnosticDao().insertCheckpoint(DiagnosticImportCheckpointEntity("digest", 2, 1))
            val bytes = byteArrayOf(0, 1, -1)
            db.localDataQuarantineDao().insert(LocalDataQuarantineEntity(
                "quarantine", "direct_boot_diagnostic", "source", null, null, null, bytes,
                "corrupt", "2026-10-06T00:00:00Z",
            ))
            val result = JSONObject(LocalStoreRecoveryManager.diagnosticSnapshot(db, null).toString(Charsets.UTF_8))
            val checkpoint = result.getJSONArray("diagnostic_import_checkpoints").getJSONObject(0)
            assertTrue(checkpoint.get("importedCount") is Number)
            assertEquals(2, checkpoint.getInt("importedCount"))
            val quarantine = result.getJSONArray("diagnostic_quarantine").getJSONObject(0)
            assertFalse(quarantine.has("studyId"))
            assertArrayEquals(bytes, android.util.Base64.decode(quarantine.getString("rawData"), android.util.Base64.NO_WRAP))
        } finally { db.close() }
    }
}
