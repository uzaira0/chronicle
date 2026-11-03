package com.openlattice.chronicle.storage

import androidx.lifecycle.LiveData
import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Update
import java.util.UUID

@Dao
interface UploadServerDao {
    @Query("SELECT * FROM upload_servers ORDER BY createdAt ASC")
    fun getAll(): List<UploadServerEntity>

    @Query("SELECT * FROM upload_servers ORDER BY createdAt ASC")
    fun getAllLive(): LiveData<List<UploadServerEntity>>

    @Query("SELECT * FROM upload_servers WHERE enabled = 1 AND enrollmentSetupComplete = 1 ORDER BY createdAt ASC")
    fun getEnabled(): List<UploadServerEntity>

    @Query("SELECT * FROM upload_servers LIMIT 1")
    fun getConfiguredServer(): UploadServerEntity?

    @Query("SELECT * FROM upload_servers WHERE enabled = 1 AND enrollmentSetupComplete = 1 LIMIT 1")
    fun getEnabledServer(): UploadServerEntity?

    @Query("SELECT * FROM upload_servers WHERE id = :id")
    fun getById(id: Long): UploadServerEntity?

    @Query("SELECT * FROM upload_servers WHERE url = :url LIMIT 1")
    fun getByUrl(url: String): UploadServerEntity?

    @Query("""
        UPDATE upload_servers
        SET reservationNonce = :ownerNonce,
            reservationExpiresAtEpochMillis = :expiresAtEpochMillis
        WHERE id = :id
          AND enrollmentIssuedAtEpochMillis IS NULL
          AND pendingEnrollmentAttemptId IS NULL
          AND (
            reservationNonce IS NULL OR
            reservationNonce = :ownerNonce OR
            reservationExpiresAtEpochMillis IS NULL OR
            reservationExpiresAtEpochMillis < :nowEpochMillis
        )
    """)
    fun claimEnrollmentReservation(
        id: Long,
        ownerNonce: String,
        nowEpochMillis: Long,
        expiresAtEpochMillis: Long,
    ): Int

    @Query("SELECT COUNT(*) FROM upload_servers")
    fun count(): Int

    @Query("SELECT COUNT(*) FROM upload_servers WHERE enabled = 1 AND enrollmentSetupComplete = 1")
    fun countEnabled(): Int

    @Insert
    fun insert(server: UploadServerEntity): Long

    /**
     * Claims Chronicle's one enrollment slot before the server is contacted. This closes the
     * check-then-enroll race: a second Activity/process cannot create a different remote
     * enrollment while the first enrollment is in flight.
     */
    @Transaction
    fun reserveSingleEnrollment(
        server: UploadServerEntity,
        ownerNonce: String = UUID.randomUUID().toString(),
        nowEpochMillis: Long = System.currentTimeMillis(),
        leaseMillis: Long = ENROLLMENT_RESERVATION_LEASE_MILLIS,
    ): SingleEnrollmentReservation {
        require(ownerNonce.isNotBlank()) { "Enrollment reservation owner must not be blank" }
        server.validatePendingEnrollment()
        val expiresAtEpochMillis = Math.addExact(nowEpochMillis, leaseMillis)
        return when (
            val resolution = resolveSingleEnrollment(
                getAll(),
                server.url,
                server.studyId,
                server.participantId,
            )
        ) {
            SingleEnrollmentResolution.Insert -> insertEnrollmentReservation(server, ownerNonce, expiresAtEpochMillis)
            is SingleEnrollmentResolution.Refresh -> refreshEnrollmentReservation(
                server, resolution.serverId, ownerNonce, nowEpochMillis, expiresAtEpochMillis,
            )
            is SingleEnrollmentResolution.ReplaceProvisional -> {
                if (deleteAbandonedProvisional(resolution.serverId, nowEpochMillis) != 1) {
                    throw SingleEnrollmentConflictException()
                }
                insertEnrollmentReservation(server, ownerNonce, expiresAtEpochMillis)
            }
            is SingleEnrollmentResolution.Reject -> throw SingleEnrollmentConflictException()
        }
    }

    /** A provisional slot whose reservation lease lapsed before any attempt was bound to it. */
    @Query("""
        DELETE FROM upload_servers
        WHERE id = :id
          AND enabled = 0
          AND enrollmentIssuedAtEpochMillis IS NULL
          AND pendingEnrollmentAttemptId IS NULL
          AND (
              reservationNonce IS NULL OR
              reservationExpiresAtEpochMillis IS NULL OR
              reservationExpiresAtEpochMillis < :nowEpochMillis
          )
    """)
    fun deleteAbandonedProvisional(id: Long, nowEpochMillis: Long): Int

    @Transaction
    fun preparePendingEnrollmentAttempt(reservation: SingleEnrollmentReservation, server: UploadServerEntity): Int {
        val current = getById(reservation.serverId) ?: return 0
        if (current.reservationNonce != reservation.ownerNonce || current.enrollmentIssuedAtEpochMillis != null) return 0
        requireNotNull(server.pendingEnrollmentAttemptId)
        server.validatePendingEnrollment()
        update(current.withEnrollmentDetails(server).copy(
            pendingAcceptedModuleIds = server.pendingAcceptedModuleIds,
            pendingDeclinedModuleIds = server.pendingDeclinedModuleIds,
            pendingUnavailableModuleIds = server.pendingUnavailableModuleIds,
            pendingEnrollmentAttemptId = server.pendingEnrollmentAttemptId,
            pendingEnrollmentAccessCode = server.pendingEnrollmentAccessCode,
            pendingProposedApiKey = server.pendingProposedApiKey,
            pendingEnrollmentSourceDeviceJson = server.pendingEnrollmentSourceDeviceJson,
            pendingEnrollmentFirstRequestAtEpochMillis = null,
            enrollmentSetupComplete = false,
            enabled = false,
        ))
        return 1
    }

    /** Finalizes only the exact identity that acquired the singleton slot. */
    @Transaction
    fun finalizeSingleEnrollment(reservation: SingleEnrollmentReservation, server: UploadServerEntity) {
        require(server.hasDeviceCredential()) { "Enrollment response did not contain a device credential" }
        val current = getById(reservation.serverId) ?: throw SingleEnrollmentConflictException()
        if (current.url != server.url || current.studyId != server.studyId || current.participantId != server.participantId) {
            throw SingleEnrollmentConflictException()
        }
        check(current.reservationNonce == reservation.ownerNonce) { "Enrollment slot changed before finalization" }
        update(current.withEnrollmentDetails(server).copy(
            apiKey = server.apiKey,
            enabled = true,
            enrollmentIssuedAtEpochMillis = current.enrollmentIssuedAtEpochMillis ?: System.currentTimeMillis(),
            consecutiveFailures = 0,
            sensorConsecutiveFailures = 0,
            batteryConsecutiveFailures = 0,
            lastUploadError = null,
            lastSensorUploadError = null,
        ))
        check(completeEnrollmentSetup(reservation.serverId, reservation.ownerNonce) == 1) {
            "Enrollment slot disappeared before it could be finalized"
        }
        check(setEnabled(reservation.serverId, server.enabled) == 1) { "Finalized enrollment disappeared before activation" }
    }

    /** Durably records the remote-issued credential before any fallible local setup step. */
    @Transaction
    fun persistIssuedEnrollment(
        reservation: SingleEnrollmentReservation,
        enrollmentAttemptId: String,
        server: UploadServerEntity,
        issuedAtEpochMillis: Long,
    ): Int {
        val current = getById(reservation.serverId) ?: return 0
        if (current.reservationNonce != reservation.ownerNonce || current.pendingEnrollmentAttemptId != enrollmentAttemptId ||
            current.pendingProposedApiKey != server.apiKey
        ) return 0
        requireNotNull(server.studyDisclosureJson)
        requireNotNull(server.disclosureVersion)
        requireNotNull(server.manifestDigest)
        update(current.withEnrollmentDetails(server).copy(
            apiKey = requireNotNull(server.apiKey),
            pendingAcceptedModuleIds = requireNotNull(server.pendingAcceptedModuleIds),
            pendingDeclinedModuleIds = requireNotNull(server.pendingDeclinedModuleIds),
            pendingUnavailableModuleIds = requireNotNull(server.pendingUnavailableModuleIds),
            enrollmentIssuedAtEpochMillis = issuedAtEpochMillis,
            enrollmentSetupComplete = false,
            enabled = false,
            pendingEnrollmentAttemptId = null,
            pendingEnrollmentAccessCode = null,
            pendingProposedApiKey = null,
            pendingEnrollmentSourceDeviceJson = null,
            pendingEnrollmentFirstRequestAtEpochMillis = null,
        ))
        return 1
    }

    @Query("""
        UPDATE upload_servers SET enabled = 1
        WHERE id = :id
          AND reservationNonce = :ownerNonce
          AND enrollmentIssuedAtEpochMillis IS NOT NULL
          AND enrollmentSetupComplete = 0
    """)
    fun activateIssuedEnrollment(id: Long, ownerNonce: String): Int

    @Query("""
        UPDATE upload_servers
        SET enrollmentSetupComplete = 1,
            reservationNonce = NULL,
            reservationExpiresAtEpochMillis = NULL,
            pendingAcceptedModuleIds = NULL,
            pendingDeclinedModuleIds = NULL,
            pendingUnavailableModuleIds = NULL,
            pendingEnrollmentAttemptId = NULL,
            pendingEnrollmentAccessCode = NULL,
            pendingProposedApiKey = NULL,
            pendingEnrollmentSourceDeviceJson = NULL,
            pendingEnrollmentFirstRequestAtEpochMillis = NULL
        WHERE id = :id
          AND reservationNonce = :ownerNonce
          AND enrollmentIssuedAtEpochMillis IS NOT NULL
          AND enabled = 1
    """)
    fun completeEnrollmentSetup(id: Long, ownerNonce: String): Int

    @Query("""
        SELECT * FROM upload_servers
        WHERE enrollmentSetupComplete = 0
          AND enrollmentIssuedAtEpochMillis IS NOT NULL
        LIMIT 1
    """)
    fun getRecoverableIssuedEnrollment(): UploadServerEntity?

    @Query("""
        SELECT * FROM upload_servers
        WHERE enrollmentSetupComplete = 0
          AND enrollmentIssuedAtEpochMillis IS NULL
          AND (
              pendingEnrollmentAttemptId IS NOT NULL OR
              pendingEnrollmentAccessCode IS NOT NULL OR
              pendingProposedApiKey IS NOT NULL OR
              pendingEnrollmentSourceDeviceJson IS NOT NULL
          )
        LIMIT 1
    """)
    fun getRecoverablePendingEnrollment(): UploadServerEntity?

    @Query("""
        UPDATE upload_servers
        SET pendingEnrollmentFirstRequestAtEpochMillis = :firstRequestAtEpochMillis
        WHERE id = :id
          AND reservationNonce = :ownerNonce
          AND pendingEnrollmentAttemptId = :enrollmentAttemptId
          AND enrollmentIssuedAtEpochMillis IS NULL
          AND pendingEnrollmentFirstRequestAtEpochMillis IS NULL
    """)
    fun markPendingEnrollmentRequestStarted(
        id: Long,
        ownerNonce: String,
        enrollmentAttemptId: String,
        firstRequestAtEpochMillis: Long,
    ): Int

    @Query("""
        DELETE FROM upload_servers
        WHERE id = :id
          AND reservationNonce = :ownerNonce
          AND pendingEnrollmentAttemptId = :enrollmentAttemptId
          AND enrollmentIssuedAtEpochMillis IS NULL
          AND enabled = 0
          AND pendingEnrollmentFirstRequestAtEpochMillis IS :firstRequestAtEpochMillis
    """)
    fun deletePendingEnrollmentAttempt(
        id: Long,
        ownerNonce: String,
        enrollmentAttemptId: String,
        firstRequestAtEpochMillis: Long?,
    ): Int

    @Query("""
        DELETE FROM upload_servers
        WHERE id = :id
          AND enrollmentSetupComplete = 0
          AND enrollmentIssuedAtEpochMillis IS NULL
          AND enabled = 0
          AND pendingEnrollmentFirstRequestAtEpochMillis IS NULL
    """)
    fun deleteCorruptPendingEnrollment(id: Long): Int

    @Update
    fun update(server: UploadServerEntity)

    @Query("DELETE FROM upload_servers WHERE id = :id")
    fun delete(id: Long)

    @Query(
        "UPDATE upload_servers SET lastSensorUploadTime = :time, lastSensorUploadError = :error, sensorConsecutiveFailures = :failures, " +
        "lastUploadedSensorId = :lastSensorId WHERE id = :id")
    fun updateSensorUploadStatus(id: Long, time: String, error: String?, failures: Int, lastSensorId: String?): Int

    @Query("""
        UPDATE upload_servers
        SET lastUploadTime = :time,
            lastUploadError = NULL,
            consecutiveFailures = 0,
            lastUsageUploadSuccessTime = :time,
            usageUploadSuccessCount = usageUploadSuccessCount + :count
        WHERE id = :id
    """)
    fun recordUsageUploadSuccess(id: Long, time: String, count: Int): Int

    @Query("""
        UPDATE upload_servers
        SET lastUploadTime = :time,
            lastUploadError = :error,
            consecutiveFailures = :failures,
            usageUploadFailureCount = usageUploadFailureCount + 1
        WHERE id = :id
    """)
    fun recordUsageUploadFailure(id: Long, time: String, error: String, failures: Int): Int

    @Query("""
        UPDATE upload_servers
        SET lastSensorUploadTime = :time,
            lastSensorUploadError = NULL,
            sensorConsecutiveFailures = 0,
            lastUploadedSensorId = :lastSensorId,
            lastSensorUploadSuccessTime = :time,
            sensorUploadSuccessCount = sensorUploadSuccessCount + :count
        WHERE id = :id
    """)
    fun recordSensorUploadSuccess(id: Long, time: String, lastSensorId: String?, count: Int): Int

    @Query("""
        UPDATE upload_servers
        SET lastSensorUploadTime = :time,
            lastSensorUploadError = :error,
            sensorConsecutiveFailures = :failures,
            sensorUploadFailureCount = sensorUploadFailureCount + 1
        WHERE id = :id
    """)
    fun recordSensorUploadFailure(id: Long, time: String, error: String, failures: Int): Int

    @Query("""
        UPDATE upload_servers
        SET lastBatteryUploadTime = :time,
            batteryConsecutiveFailures = 0,
            lastBatteryUploadSuccessTime = :time,
            batteryUploadSuccessCount = batteryUploadSuccessCount + :count
        WHERE id = :id
    """)
    fun recordBatteryUploadSuccess(id: Long, time: String, count: Int): Int

    @Query("""
        UPDATE upload_servers
        SET lastBatteryUploadTime = :time,
            batteryConsecutiveFailures = :failures,
            batteryUploadFailureCount = batteryUploadFailureCount + 1
        WHERE id = :id
    """)
    fun recordBatteryUploadFailure(id: Long, time: String, failures: Int): Int

    @Query(
        "UPDATE upload_servers SET enabled = :enabled, consecutiveFailures = CASE WHEN :enabled = 1 THEN 0 ELSE consecutiveFailures END, " +
        "sensorConsecutiveFailures = CASE WHEN :enabled = 1 THEN 0 ELSE sensorConsecutiveFailures END, batteryConsecutiveFailures = CASE " +
        "WHEN :enabled = 1 THEN 0 ELSE batteryConsecutiveFailures END WHERE id = :id AND (:enabled = 0 OR enrollmentSetupComplete = 1)")
    fun setEnabled(id: Long, enabled: Boolean): Int

    companion object {
        const val ENROLLMENT_RESERVATION_LEASE_MILLIS: Long = 15 * 60 * 1_000L
    }
}

private fun UploadServerEntity.validatePendingEnrollment() {
    val pendingReplayFields = listOf(
        pendingEnrollmentAttemptId,
        pendingEnrollmentAccessCode,
        pendingProposedApiKey,
        pendingEnrollmentSourceDeviceJson,
    )
    require(pendingReplayFields.all { it == null } || pendingReplayFields.all { it != null }) {
        "Replay-safe enrollment fields must be persisted together"
    }
    val pendingDecisionFields = listOf(
        pendingAcceptedModuleIds,
        pendingDeclinedModuleIds,
        pendingUnavailableModuleIds,
    )
    require(
        pendingDecisionFields.all { it == null } || pendingDecisionFields.all { it != null },
    ) { "Replay-safe enrollment evidence must be persisted together" }
    require(
        (pendingEnrollmentAttemptId == null) == pendingDecisionFields.all { it == null },
    ) { "Enrollment request and acknowledgment evidence must be persisted together" }
}

private fun UploadServerDao.insertEnrollmentReservation(
    server: UploadServerEntity,
    ownerNonce: String,
    expiresAtEpochMillis: Long,
): SingleEnrollmentReservation {
    val id = insert(
        server.copy(
            enabled = false,
            apiKey = null,
            enrollmentSetupComplete = false,
            enrollmentIssuedAtEpochMillis = null,
            reservationNonce = ownerNonce,
            reservationExpiresAtEpochMillis = expiresAtEpochMillis,
        ),
    )
    return SingleEnrollmentReservation(id, provisional = true, ownerNonce = ownerNonce)
}

private fun UploadServerDao.refreshEnrollmentReservation(
    server: UploadServerEntity,
    serverId: Long,
    ownerNonce: String,
    nowEpochMillis: Long,
    expiresAtEpochMillis: Long,
): SingleEnrollmentReservation {
    val existing = getById(serverId)
        ?: throw SingleEnrollmentConflictException()
    val provisional = !existing.enabled && existing.enrollmentIssuedAtEpochMillis == null
    if (
        claimEnrollmentReservation(
            existing.id,
            ownerNonce,
            nowEpochMillis,
            expiresAtEpochMillis,
        ) != 1
    ) throw SingleEnrollmentConflictException()
    if (server.pendingEnrollmentAttemptId != null &&
        preparePendingEnrollmentAttempt(
            SingleEnrollmentReservation(serverId, provisional = provisional, ownerNonce = ownerNonce),
            server,
        ) != 1
    ) throw SingleEnrollmentConflictException()
    return SingleEnrollmentReservation(
        serverId,
        provisional = provisional,
        ownerNonce = ownerNonce,
    )
}

private fun UploadServerEntity.withEnrollmentDetails(server: UploadServerEntity): UploadServerEntity = copy(
    name = server.name,
    sourceRandomDeviceId = server.sourceRandomDeviceId,
    studyDisclosureJson = server.studyDisclosureJson,
    disclosureVersion = server.disclosureVersion,
    manifestDigest = server.manifestDigest,
)
