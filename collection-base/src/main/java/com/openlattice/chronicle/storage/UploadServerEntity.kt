package com.openlattice.chronicle.storage

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import java.time.OffsetDateTime

@Entity(
    tableName = "upload_servers",
    indices = [Index(value = ["singletonKey"], unique = true)],
)
data class UploadServerEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    /** Database-enforced singleton slot: an installation can belong to only one study. */
    @ColumnInfo(defaultValue = "1")
    val singletonKey: Int = 1,
    val name: String,
    val url: String,
    val studyId: String,
    val participantId: String,
    /**
     * Client-side source device UUID sent to enrollment and subsequent upload
     * requests as `X-Chronicle-Random-Device-Id`. The server derives its internal
     * Chronicle device UUID from this value.
     */
    val sourceRandomDeviceId: String,
    /**
     * Per-device API key issued by the server during enrollment, sent as X-Api-Key.
     * Stored in the SQLCipher database.
     */
    val apiKey: String? = null,
    /** Authenticated study disclosure shown before enrollment, serialized in the encrypted database. */
    val studyDisclosureJson: String? = null,
    /** Consent/disclosure version accepted by this installation. */
    val disclosureVersion: String? = null,
    /** SHA-256 digest of the exact enrollment manifest accepted by this installation. */
    val manifestDigest: String? = null,
    /** Random owner for the one in-flight enrollment attempt; stored only in SQLCipher. */
    val reservationNonce: String? = null,
    /** Epoch-millis lease expiry after which an abandoned, unissued reservation may be replaced. */
    val reservationExpiresAtEpochMillis: Long? = null,
    /** Remote enrollment response was durably stored at this instant; null before issuance. */
    val enrollmentIssuedAtEpochMillis: Long? = null,
    /** False until identity, consent state, and runtime policy are all reconciled locally. */
    @ColumnInfo(defaultValue = "1")
    val enrollmentSetupComplete: Boolean = true,
    /** Canonical newline-delimited module ids used only to recover an interrupted setup. */
    val pendingAcceptedModuleIds: String? = null,
    /** Canonical newline-delimited module ids used only to recover an interrupted setup. */
    val pendingDeclinedModuleIds: String? = null,
    /** Canonical hardware-unavailable module ids bound to the enrollment acknowledgment. */
    val pendingUnavailableModuleIds: String? = null,
    /** Canonical UUID binding every retry to the same server-side enrollment attempt. */
    val pendingEnrollmentAttemptId: String? = null,
    /** One-time invitation capability, retained only in SQLCipher until terminal cleanup. */
    val pendingEnrollmentAccessCode: String? = null,
    /** Client-generated credential proposed to the server; retained only until a matching response. */
    val pendingProposedApiKey: String? = null,
    /** Exact SourceDevice JSON bound to the attempt; replay must not rebuild mutable OS fields. */
    val pendingEnrollmentSourceDeviceJson: String? = null,
    /** Set once this exact request may have reached the server; from then on only the server can end the attempt. */
    val pendingEnrollmentFirstRequestAtEpochMillis: Long? = null,
    val enabled: Boolean = true,
    val lastUploadTime: String? = null,
    val lastUploadError: String? = null,
    val consecutiveFailures: Int = 0,
    val lastSensorUploadTime: String? = null,
    val lastSensorUploadError: String? = null,
    val sensorConsecutiveFailures: Int = 0,
    val lastBatteryUploadTime: String? = null,
    val batteryConsecutiveFailures: Int = 0,
    val lastUsageUploadSuccessTime: String? = null,
    val usageUploadSuccessCount: Int = 0,
    val usageUploadFailureCount: Int = 0,
    val lastSensorUploadSuccessTime: String? = null,
    val sensorUploadSuccessCount: Int = 0,
    val sensorUploadFailureCount: Int = 0,
    val lastBatteryUploadSuccessTime: String? = null,
    val batteryUploadSuccessCount: Int = 0,
    val batteryUploadFailureCount: Int = 0,
    // These two have no writer or reader; uploaders delete delivered rows by key. Kept to avoid a migration.
    val lastUploadedTimestamp: Long = 0,
    val lastUploadedQueueId: Long = Long.MIN_VALUE,
    val lastUploadedSensorId: String? = null,
    val createdAt: String = OffsetDateTime.now().toString()
) {
    override fun toString(): String =
        "UploadServerEntity(enabled=$enabled, enrollmentSetupComplete=$enrollmentSetupComplete)"
}

fun UploadServerEntity.hasDeviceCredential(): Boolean = !apiKey.isNullOrBlank()
