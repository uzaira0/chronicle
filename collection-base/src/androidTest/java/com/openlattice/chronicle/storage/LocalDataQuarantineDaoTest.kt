package com.openlattice.chronicle.storage

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.time.OffsetDateTime
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class LocalDataQuarantineDaoTest {
    @Test fun reenrollmentScopesHistoryWithoutErasingOldRows() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val db = Room.inMemoryDatabaseBuilder(context, ChronicleDb::class.java)
            .allowMainThreadQueries().build()
        try {
            listOf("old", "new").forEach { epoch ->
                db.uploadDiagnosticDao().upsert(UploadDiagnosticEntity(
                    id = epoch, studyId = "study", participantId = "participant",
                    deviceId = "device", enrollmentEpoch = epoch,
                    day = "2026-01-01", moduleFamily = "SENSOR", issueCode = "LOCAL_BUFFER_OVERFLOW",
                    count = 1, firstOccurredAt = "2026-01-01T00:00:00Z",
                    lastOccurredAt = "2026-01-01T00:00:00Z", httpStatus = null, errorType = null,
                ))
            }
            assertEquals(listOf("old"), db.uploadDiagnosticDao()
                .forEnrollment("study", "participant", "device", "old").map { it.id })
            assertEquals(listOf("new"), db.uploadDiagnosticDao()
                .forEnrollment("study", "participant", "device", "new").map { it.id })
            assertEquals(2, db.uploadDiagnosticDao()
                .forEnrollment("study", "participant", "device", "old").size +
                db.uploadDiagnosticDao().forEnrollment("study", "participant", "device", "new").size)
        } finally {
            db.close()
        }
    }

    @Test fun preservesOriginalBytesAndDeduplicatesSourceRecord() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val db = Room.inMemoryDatabaseBuilder(context, ChronicleDb::class.java)
            .allowMainThreadQueries().build()
        try {
            val raw = byteArrayOf(0, 1, 2, -1)
            val entry = LocalDataQuarantineEntity(
                id = "battery_samples:sample-1", sourceTable = "battery_samples",
                sourceId = "sample-1", studyId = "study", participantId = "participant",
                deviceId = "device", rawData = raw,
                reason = "SAMPLE_QUARANTINED", createdAt = OffsetDateTime.now().toString(),
            )
            db.localDataQuarantineDao().insert(entry)
            db.localDataQuarantineDao().insert(entry)
            assertEquals(1, db.localDataQuarantineDao().count("battery_samples"))
            assertArrayEquals(raw, db.localDataQuarantineDao().get("battery_samples", "sample-1")?.rawData)
        } finally {
            db.close()
        }
    }
}
