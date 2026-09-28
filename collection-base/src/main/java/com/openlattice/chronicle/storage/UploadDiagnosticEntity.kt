package com.openlattice.chronicle.storage

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/** Local history is independent of the mutable upload destination and its foreign keys. */
@Entity(
    tableName = "upload_diagnostics",
    indices = [
        Index(value = ["studyId", "participantId", "deviceId", "enrollmentEpoch", "deliveryState", "day"]),
        Index(value = ["day", "moduleFamily", "issueCode"]),
        Index(value = ["studyId", "participantId", "deviceId", "enrollmentEpoch", "day", "moduleFamily", "issueCode", "deliveryState", "sealedForUpload"]),
    ],
)
data class UploadDiagnosticEntity(
    @PrimaryKey val id: String,
    val studyId: String?,
    val participantId: String?,
    val deviceId: String?,
    val enrollmentEpoch: String?,
    val day: String,
    val moduleFamily: String,
    val issueCode: String,
    val count: Int,
    val firstOccurredAt: String,
    val lastOccurredAt: String,
    val httpStatus: Int?,
    val errorType: String?,
    val deliveryState: String = "PENDING",
    val sealedForUpload: Boolean = false,
)

@Entity(tableName = "local_data_quarantine", indices = [Index(value = ["sourceTable", "sourceId"], unique = true)])
data class LocalDataQuarantineEntity(
    @PrimaryKey val id: String,
    val sourceTable: String,
    val sourceId: String,
    val studyId: String?,
    val participantId: String?,
    val deviceId: String?,
    val rawData: ByteArray,
    val reason: String,
    val createdAt: String,
)

@Entity(tableName = "diagnostic_import_checkpoints")
data class DiagnosticImportCheckpointEntity(
    @PrimaryKey val digest: String,
    val importedCount: Int,
    val quarantinedCount: Int,
)
