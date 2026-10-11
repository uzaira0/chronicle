package com.openlattice.chronicle.services.upload

import com.openlattice.chronicle.storage.ChronicleDb
import com.openlattice.chronicle.storage.LocalDataQuarantineEntity
import com.openlattice.chronicle.storage.UploadDiagnosticEntity
import com.openlattice.chronicle.storage.UploadServerEntity
import java.time.LocalDate
import java.time.OffsetDateTime
import java.util.UUID

/** Keeps the original record before a cursor or successful batch may pass it. */
internal fun quarantineMalformedSample(
    db: ChronicleDb,
    server: UploadServerEntity,
    sourceTable: String,
    sourceId: String,
    rawData: ByteArray,
    family: LocalUploadModuleFamily,
    count: Int = 1,
    reason: String = LocalOperationalIssue.SAMPLE_QUARANTINED.name,
    httpStatus: Int? = null,
    settingsVersion: Int? = null,
    removeFromActiveQueue: () -> Unit = {},
) {
    val now = OffsetDateTime.now()
    val scopedSourceId = "${server.id}:${server.createdAt}:$sourceId"
    db.runInTransaction {
        val inserted = db.localDataQuarantineDao().insert(LocalDataQuarantineEntity(
            id = "$sourceTable:$scopedSourceId",
            sourceTable = sourceTable,
            sourceId = scopedSourceId,
            studyId = server.studyId,
            participantId = server.participantId,
            deviceId = server.sourceDeviceId,
            rawData = rawData,
            reason = reason,
            createdAt = now.toString(),
            settingsVersion = settingsVersion,
        ))
        removeFromActiveQueue()
        if (inserted != -1L) {
            db.uploadDiagnosticDao().upsert(UploadDiagnosticEntity(
                id = UUID.randomUUID().toString(),
                studyId = server.studyId,
                participantId = server.participantId,
                deviceId = server.sourceDeviceId,
                enrollmentEpoch = "${server.id}:${server.createdAt}",
                day = LocalDate.now().toString(),
                moduleFamily = family.name,
                issueCode = LocalOperationalIssue.SAMPLE_QUARANTINED.name,
                count = count,
                firstOccurredAt = now.toString(),
                lastOccurredAt = now.toString(),
                httpStatus = httpStatus,
                errorType = null,
            ))
        }
    }
}
