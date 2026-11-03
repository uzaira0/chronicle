package com.openlattice.chronicle.services.upload

import com.openlattice.chronicle.collection.CollectionModuleId
import com.openlattice.chronicle.collection.state.eraseSharedQueueModuleQuarantine
import com.openlattice.chronicle.models.ExtractedUsageEvent
import com.openlattice.chronicle.serialization.JsonSerializer
import com.openlattice.chronicle.storage.MAX_PERSISTED_QUEUE_PAYLOAD_BYTES
import com.openlattice.chronicle.storage.forEachArchive
import com.openlattice.chronicle.storage.QueueEntry
import com.openlattice.chronicle.storage.UploadQueueTable
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test

class UploadReviewRegressionTest : UsageUploadFixture() {
    @Test fun currentIndivisibleUsageArchiveCanStillBeRedactedBeyondTheCursorWindow() {
        val sample = JsonSerializer.deserializeQueueEntry(validEntry(1).data).single() as ExtractedUsageEvent
        val raw = JsonSerializer.serializeQueueEntry(listOf(sample.copy(applicationLabel = "é".repeat(MAX_PERSISTED_QUEUE_PAYLOAD_BYTES))))
        db.queueEntryData().insertEntry(QueueEntry(1001, 1, raw))
        assertEquals(0, db.queueEntryData().getSize())
        var retained: ExtractedUsageEvent? = null
        db.runInTransaction {
            eraseSharedQueueModuleQuarantine(db, CollectionModuleId.USER_IDENTIFICATION)
            db.forEachArchive(UploadQueueTable.USAGE) {
                retained = JsonSerializer.deserializeQueueEntry(it.rawData).single() as ExtractedUsageEvent
            }
        }
        assertEquals("", retained!!.user)
        assertEquals(MAX_PERSISTED_QUEUE_PAYLOAD_BYTES, retained.applicationLabel.length)
    }

    @Test fun oversizedArchiveRetainsUsageEvidenceWhenIdentificationIsErased() {
        db.queueEntryData().insertEntry(validEntry(1, activityClass = "kept-activity"))
        executor { httpError(java.net.HttpURLConnection.HTTP_ENTITY_TOO_LARGE) }.run()
        db.runInTransaction { eraseSharedQueueModuleQuarantine(db, CollectionModuleId.USER_IDENTIFICATION) }
        val archive = db.localDataQuarantineDao().forSource(UploadQueueTable.USAGE.tableName).single()
        val retained = JsonSerializer.deserializeQueueEntry(archive.rawData).single() as ExtractedUsageEvent
        assertEquals("", retained.user)
        assertEquals("kept-activity", retained.activityClass)
        assertEquals("a.b.c", retained.appPackageName)
    }

    @Test fun redactingAnArchiveKeepsItsRejectionStatusAndAppVersion() {
        db.queueEntryData().insertEntry(validEntry(1))
        executor { httpError(java.net.HttpURLConnection.HTTP_BAD_REQUEST, rowRejectedBody) }.run()
        val quarantine = db.localDataQuarantineDao()
        val before = quarantine.forSource(UploadQueueTable.USAGE.tableName).single()
        assertEquals(java.net.HttpURLConnection.HTTP_BAD_REQUEST, before.httpStatus)

        db.runInTransaction { eraseSharedQueueModuleQuarantine(db, CollectionModuleId.USER_IDENTIFICATION) }

        val after = quarantine.forSource(UploadQueueTable.USAGE.tableName).single()
        assertEquals(java.net.HttpURLConnection.HTTP_BAD_REQUEST, after.httpStatus)
        assertEquals(before.appVersion, after.appVersion)
        assertNotNull(after.appVersion)
    }
}
