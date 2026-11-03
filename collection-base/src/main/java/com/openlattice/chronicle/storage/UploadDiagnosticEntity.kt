package com.openlattice.chronicle.storage

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * A diagnostic row is PENDING until the server's receipt settles it as DELIVERED (stored) or DISCARDED (refused by
 * the participation window or an erasure), or until it is QUARANTINED locally.
 */
enum class DiagnosticDeliveryState {
    PENDING, DELIVERED, DISCARDED, QUARANTINED;

    /** This state as the outcome that settles a PENDING row. */
    fun asOutcome(): DiagnosticDeliveryState = also { require(it != PENDING) { "A diagnostic settles only as an outcome" } }
}

/**
 * One immutable occurrence: [count] equivalent failures at one instant ([firstOccurredAt] equals [lastOccurredAt]).
 * Rows are never merged, because only the server knows the participation window that may separate two occurrences.
 * Local history is independent of the mutable upload destination and its foreign keys.
 */
@Entity(
    tableName = "upload_diagnostics",
    indices = [
        Index(value = ["studyId", "participantId", "randomDeviceId", "enrollmentEpoch", "deliveryState", "day"]),
        Index(value = ["day", "moduleFamily", "issueCode"]),
        Index(value = ["studyId", "participantId", "randomDeviceId", "enrollmentEpoch", "day", "moduleFamily", "issueCode", "deliveryState",
            "sealedForUpload"]),
    ],
)
data class UploadDiagnosticEntity(
    @PrimaryKey val id: String,
    val studyId: String?,
    val participantId: String?,
    val randomDeviceId: String?,
    val enrollmentEpoch: String?,
    val day: String,
    val moduleFamily: String,
    val issueCode: String,
    val count: Int,
    val firstOccurredAt: String,
    val lastOccurredAt: String,
    val httpStatus: Int?,
    val errorType: String?,
    val deliveryState: DiagnosticDeliveryState = DiagnosticDeliveryState.PENDING,
    /** Retired with merging and always false; kept so schema version 1, shipped in build 79, keeps its identity. */
    val sealedForUpload: Boolean = false,
) {
    fun settled(outcome: DiagnosticDeliveryState) = copy(deliveryState = outcome.asOutcome())
}

@Entity(tableName = "local_data_quarantine", indices = [Index(value = ["sourceTable", "sourceId"], unique = true)])
data class LocalDataQuarantineEntity(
    @PrimaryKey val id: String,
    val sourceTable: String,
    val sourceId: String,
    val studyId: String?,
    val participantId: String?,
    val randomDeviceId: String?,
    val rawData: ByteArray,
    val reason: String,
    val createdAt: String,
    val settingsVersion: Int? = null,
    /** HTTP status of the upload that rejected this row, when [reason] is an upload rejection. */
    val httpStatus: Int? = null,
    /** App version that quarantined the row. */
    val appVersion: String? = null,
)

/** Archive metadata can be reported without filling a cursor with its payload. */
data class QuarantinedQueueRow(val id: String, val sourceTable: String, val createdAt: String)
