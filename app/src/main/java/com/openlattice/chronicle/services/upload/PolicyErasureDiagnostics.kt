package com.openlattice.chronicle.services.upload

import com.openlattice.chronicle.storage.ChronicleDb
import com.openlattice.chronicle.storage.UploadDiagnosticEntity
import java.time.LocalDate
import java.time.OffsetDateTime
import java.util.UUID

/** Call inside the same Room transaction as the policy-authorized deletion. */
internal fun recordPolicyErasureInTransaction(
    db: ChronicleDb,
    table: String,
    family: LocalUploadModuleFamily,
    issue: LocalOperationalIssue,
    where: String = "",
    args: Array<out Any> = emptyArray(),
): Long {
    val sql = "SELECT COUNT(*) FROM `$table`" + if (where.isEmpty()) "" else " WHERE $where"
    val count = db.openHelper.writableDatabase.query(sql, args).use { cursor ->
        if (cursor.moveToFirst()) cursor.getLong(0) else 0L
    }
    recordPolicyErasureCountInTransaction(db, count, family, issue)
    return count
}

internal fun recordPolicyErasureCountInTransaction(
    db: ChronicleDb,
    count: Long,
    family: LocalUploadModuleFamily,
    issue: LocalOperationalIssue,
    eventId: String = UUID.randomUUID().toString(),
) {
    if (count <= 0) return
    val server = db.uploadServerDao().getConfiguredServer()
    val now = OffsetDateTime.now()
    var remaining = count
    var part = 0
    while (remaining > 0) {
        val batchCount = minOf(remaining, Int.MAX_VALUE.toLong()).toInt()
        db.uploadDiagnosticDao().upsert(UploadDiagnosticEntity(
            id = if (part == 0) eventId else UUID.nameUUIDFromBytes("$eventId:$part".toByteArray()).toString(),
            studyId = server?.studyId,
            participantId = server?.participantId,
            deviceId = server?.sourceDeviceId,
            enrollmentEpoch = server?.let { "${it.id}:${it.createdAt}" },
            day = LocalDate.now().toString(),
            moduleFamily = family.name,
            issueCode = issue.name,
            count = batchCount,
            firstOccurredAt = now.toString(),
            lastOccurredAt = now.toString(),
            httpStatus = null,
            errorType = null,
        ))
        remaining -= batchCount
        part++
    }
}
