package com.openlattice.chronicle.storage

import androidx.lifecycle.LiveData
import androidx.room.*

data class UploadQueueCursor(
    val lastUploadedTimestamp: Long,
    val lastUploadedQueueId: Long
)

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
        SET name = :name,
            sourceDeviceId = :sourceDeviceId,
            authMode = :authMode,
            apiKey = :apiKey,
            mobileSigningSecretOverride = :mobileSigningSecretOverride,
            studyDisclosureJson = :studyDisclosureJson,
            disclosureVersion = :disclosureVersion,
            manifestDigest = :manifestDigest,
            enabled = 1,
            enrollmentSetupComplete = 1,
            reservationNonce = NULL,
            reservationExpiresAtEpochMillis = NULL,
            enrollmentIssuedAtEpochMillis = COALESCE(enrollmentIssuedAtEpochMillis, :issuedAtEpochMillis),
            pendingAcceptedModuleIds = NULL,
            pendingDeclinedModuleIds = NULL,
            pendingUnavailableModuleIds = NULL,
            pendingEnrollmentAttemptId = NULL,
            pendingEnrollmentAccessCode = NULL,
            pendingEnrollmentInviteExpiresAtEpochMillis = NULL,
            pendingProposedApiKey = NULL,
            pendingEnrollmentSourceDeviceJson = NULL,
            pendingEnrollmentFirstRequestAtEpochMillis = NULL,
            pendingEnrollmentReplayDeadlineEpochMillis = NULL,
            sensorDeliveryGeneration = sensorDeliveryGeneration + 1,
            consecutiveFailures = 0,
            sensorConsecutiveFailures = 0,
            batteryConsecutiveFailures = 0,
            lastUploadError = NULL,
            lastSensorUploadError = NULL,
            lastBatteryUploadError = NULL
        WHERE id = :id AND reservationNonce = :ownerNonce
    """)
    fun updateEnrollmentCredentials(
        id: Long,
        name: String,
        sourceDeviceId: String,
        authMode: String,
        apiKey: String?,
        mobileSigningSecretOverride: String?,
        studyDisclosureJson: String?,
        disclosureVersion: String?,
        manifestDigest: String?,
        ownerNonce: String,
        issuedAtEpochMillis: Long,
    ): Int

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

    @Query("UPDATE upload_servers SET sourceDeviceId = :legacyDeviceId WHERE sourceDeviceId = ''")
    fun backfillEmptySourceDeviceId(legacyDeviceId: String): Int

    @Query("""
        UPDATE upload_servers
        SET url = :currentUrl,
            sensorDeliveryGeneration = sensorDeliveryGeneration + 1,
            consecutiveFailures = 0,
            sensorConsecutiveFailures = 0,
            batteryConsecutiveFailures = 0,
            lastUploadError = NULL,
            lastSensorUploadError = NULL,
            lastBatteryUploadError = NULL
        WHERE url = :retiredUrl
    """)
    fun replaceServerUrl(retiredUrl: String, currentUrl: String): Int

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
        ownerNonce: String = java.util.UUID.randomUUID().toString(),
        nowEpochMillis: Long = System.currentTimeMillis(),
        leaseMillis: Long = ENROLLMENT_RESERVATION_LEASE_MILLIS,
    ): SingleEnrollmentReservation {
        require(ownerNonce.isNotBlank()) { "Enrollment reservation owner must not be blank" }
        val pendingReplayFields = listOf(
            server.pendingEnrollmentAttemptId,
            server.pendingEnrollmentAccessCode,
            server.pendingEnrollmentInviteExpiresAtEpochMillis,
            server.pendingProposedApiKey,
            server.pendingEnrollmentSourceDeviceJson,
        )
        require(pendingReplayFields.all { it == null } || pendingReplayFields.all { it != null }) {
            "Replay-safe enrollment fields must be persisted together"
        }
        val pendingDecisionFields = listOf(
            server.pendingAcceptedModuleIds,
            server.pendingDeclinedModuleIds,
            server.pendingUnavailableModuleIds,
        )
        require(
            pendingDecisionFields.all { it == null } || pendingDecisionFields.all { it != null },
        ) { "Replay-safe enrollment evidence must be persisted together" }
        require(
            (server.pendingEnrollmentAttemptId == null) == pendingDecisionFields.all { it == null },
        ) { "Enrollment request and acknowledgment evidence must be persisted together" }
        require(
            (server.pendingEnrollmentFirstRequestAtEpochMillis == null) ==
                (server.pendingEnrollmentReplayDeadlineEpochMillis == null),
        ) { "Enrollment request timing must be persisted together" }
        val expiresAtEpochMillis = Math.addExact(nowEpochMillis, leaseMillis)
        return when (
            val resolution = resolveSingleEnrollment(
                getAll(),
                server.url,
                server.studyId,
                server.participantId,
                nowEpochMillis,
            )
        ) {
            SingleEnrollmentResolution.Insert -> {
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
                SingleEnrollmentReservation(id, provisional = true, ownerNonce = ownerNonce)
            }
            is SingleEnrollmentResolution.Refresh -> {
                val existing = getById(resolution.serverId)
                    ?: throw SingleEnrollmentConflictException()
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
                        id = existing.id,
                        ownerNonce = ownerNonce,
                        name = server.name,
                        sourceDeviceId = server.sourceDeviceId,
                        mobileSigningSecretOverride = server.mobileSigningSecretOverride,
                        studyDisclosureJson = server.studyDisclosureJson,
                        disclosureVersion = server.disclosureVersion,
                        manifestDigest = server.manifestDigest,
                        pendingAcceptedModuleIds = server.pendingAcceptedModuleIds,
                        pendingDeclinedModuleIds = server.pendingDeclinedModuleIds,
                        pendingUnavailableModuleIds = server.pendingUnavailableModuleIds,
                        pendingEnrollmentAttemptId = server.pendingEnrollmentAttemptId,
                        pendingEnrollmentAccessCode = requireNotNull(server.pendingEnrollmentAccessCode),
                        pendingEnrollmentInviteExpiresAtEpochMillis = requireNotNull(
                            server.pendingEnrollmentInviteExpiresAtEpochMillis,
                        ),
                        pendingProposedApiKey = requireNotNull(server.pendingProposedApiKey),
                        pendingEnrollmentSourceDeviceJson = requireNotNull(
                            server.pendingEnrollmentSourceDeviceJson,
                        ),
                    ) != 1
                ) throw SingleEnrollmentConflictException()
                SingleEnrollmentReservation(
                    resolution.serverId,
                    provisional = !existing.enabled && existing.enrollmentIssuedAtEpochMillis == null,
                    ownerNonce = ownerNonce,
                )
            }
            is SingleEnrollmentResolution.ReplaceProvisional -> {
                if (deleteAbandonedProvisional(resolution.serverId, nowEpochMillis) != 1) {
                    throw SingleEnrollmentConflictException()
                }
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
                SingleEnrollmentReservation(id, provisional = true, ownerNonce = ownerNonce)
            }
            is SingleEnrollmentResolution.Reject -> throw SingleEnrollmentConflictException()
        }
    }

    @Query("""
        DELETE FROM upload_servers
        WHERE id = :id
          AND enabled = 0
          AND enrollmentIssuedAtEpochMillis IS NULL
          AND (
              (
                  pendingEnrollmentAttemptId IS NULL AND (
                      reservationNonce IS NULL OR
                      reservationExpiresAtEpochMillis IS NULL OR
                      reservationExpiresAtEpochMillis < :nowEpochMillis
                  )
              ) OR (
                  pendingEnrollmentAttemptId IS NOT NULL AND (
                      (
                          pendingEnrollmentFirstRequestAtEpochMillis IS NULL AND
                          pendingEnrollmentInviteExpiresAtEpochMillis <= :nowEpochMillis
                      ) OR (
                          pendingEnrollmentFirstRequestAtEpochMillis IS NOT NULL AND
                          pendingEnrollmentReplayDeadlineEpochMillis <= :nowEpochMillis
                      )
                  )
              )
          )
    """)
    fun deleteAbandonedProvisional(id: Long, nowEpochMillis: Long): Int

    @Query("""
        UPDATE upload_servers
        SET name = :name,
            sourceDeviceId = :sourceDeviceId,
            mobileSigningSecretOverride = :mobileSigningSecretOverride,
            studyDisclosureJson = :studyDisclosureJson,
            disclosureVersion = :disclosureVersion,
            manifestDigest = :manifestDigest,
            pendingAcceptedModuleIds = :pendingAcceptedModuleIds,
            pendingDeclinedModuleIds = :pendingDeclinedModuleIds,
            pendingUnavailableModuleIds = :pendingUnavailableModuleIds,
            pendingEnrollmentAttemptId = :pendingEnrollmentAttemptId,
            pendingEnrollmentAccessCode = :pendingEnrollmentAccessCode,
            pendingEnrollmentInviteExpiresAtEpochMillis = :pendingEnrollmentInviteExpiresAtEpochMillis,
            pendingProposedApiKey = :pendingProposedApiKey,
            pendingEnrollmentSourceDeviceJson = :pendingEnrollmentSourceDeviceJson,
            pendingEnrollmentFirstRequestAtEpochMillis = NULL,
            pendingEnrollmentReplayDeadlineEpochMillis = NULL,
            enrollmentSetupComplete = 0,
            enabled = 0
        WHERE id = :id
          AND reservationNonce = :ownerNonce
          AND enrollmentIssuedAtEpochMillis IS NULL
    """)
    fun preparePendingEnrollmentAttempt(
        id: Long,
        ownerNonce: String,
        name: String,
        sourceDeviceId: String,
        mobileSigningSecretOverride: String?,
        studyDisclosureJson: String?,
        disclosureVersion: String?,
        manifestDigest: String?,
        pendingAcceptedModuleIds: String?,
        pendingDeclinedModuleIds: String?,
        pendingUnavailableModuleIds: String?,
        pendingEnrollmentAttemptId: String,
        pendingEnrollmentAccessCode: String,
        pendingEnrollmentInviteExpiresAtEpochMillis: Long,
        pendingProposedApiKey: String,
        pendingEnrollmentSourceDeviceJson: String,
    ): Int

    /** Finalizes only the exact identity that acquired the singleton slot. */
    @Transaction
    fun finalizeSingleEnrollment(
        reservation: SingleEnrollmentReservation,
        requestedUrl: String,
        requestedStudyId: String,
        requestedParticipantId: String,
        name: String,
        sourceDeviceId: String,
        authMode: String,
        apiKey: String?,
        mobileSigningSecretOverride: String?,
        studyDisclosureJson: String? = null,
        disclosureVersion: String? = null,
        manifestDigest: String? = null,
    ) {
        val current = getById(reservation.serverId)
            ?: throw SingleEnrollmentConflictException()
        if (
            current.url != requestedUrl ||
            current.studyId != requestedStudyId ||
            current.participantId != requestedParticipantId
        ) {
            throw SingleEnrollmentConflictException()
        }
        check(
            updateEnrollmentCredentials(
                reservation.serverId,
                name,
                sourceDeviceId,
                authMode,
                apiKey,
                mobileSigningSecretOverride,
                studyDisclosureJson,
                disclosureVersion,
                manifestDigest,
                reservation.ownerNonce,
                System.currentTimeMillis(),
            ) == 1
        ) { "Enrollment slot disappeared before it could be finalized" }
    }

    /** Durably records the remote-issued credential before any fallible local setup step. */
    @Query("""
        UPDATE upload_servers
        SET name = :name,
            sourceDeviceId = :sourceDeviceId,
            authMode = :authMode,
            apiKey = :apiKey,
            mobileSigningSecretOverride = :mobileSigningSecretOverride,
            studyDisclosureJson = :studyDisclosureJson,
            disclosureVersion = :disclosureVersion,
            manifestDigest = :manifestDigest,
            pendingAcceptedModuleIds = :pendingAcceptedModuleIds,
            pendingDeclinedModuleIds = :pendingDeclinedModuleIds,
            pendingUnavailableModuleIds = :pendingUnavailableModuleIds,
            enrollmentIssuedAtEpochMillis = :issuedAtEpochMillis,
            enrollmentSetupComplete = 0,
            enabled = 0,
            pendingEnrollmentAttemptId = NULL,
            pendingEnrollmentAccessCode = NULL,
            pendingEnrollmentInviteExpiresAtEpochMillis = NULL,
            pendingProposedApiKey = NULL,
            pendingEnrollmentSourceDeviceJson = NULL,
            pendingEnrollmentFirstRequestAtEpochMillis = NULL,
            pendingEnrollmentReplayDeadlineEpochMillis = NULL
        WHERE id = :id
          AND reservationNonce = :ownerNonce
          AND pendingEnrollmentAttemptId = :enrollmentAttemptId
          AND pendingProposedApiKey = :apiKey
    """)
    fun persistIssuedEnrollment(
        id: Long,
        ownerNonce: String,
        name: String,
        sourceDeviceId: String,
        authMode: String,
        apiKey: String,
        mobileSigningSecretOverride: String?,
        studyDisclosureJson: String,
        disclosureVersion: String,
        manifestDigest: String,
        pendingAcceptedModuleIds: String,
        pendingDeclinedModuleIds: String,
        pendingUnavailableModuleIds: String,
        enrollmentAttemptId: String,
        issuedAtEpochMillis: Long,
    ): Int

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
            pendingEnrollmentInviteExpiresAtEpochMillis = NULL,
            pendingProposedApiKey = NULL,
            pendingEnrollmentSourceDeviceJson = NULL,
            pendingEnrollmentFirstRequestAtEpochMillis = NULL,
            pendingEnrollmentReplayDeadlineEpochMillis = NULL
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
        SET pendingEnrollmentFirstRequestAtEpochMillis = :firstRequestAtEpochMillis,
            pendingEnrollmentReplayDeadlineEpochMillis = :replayDeadlineEpochMillis
        WHERE id = :id
          AND reservationNonce = :ownerNonce
          AND pendingEnrollmentAttemptId = :enrollmentAttemptId
          AND enrollmentIssuedAtEpochMillis IS NULL
          AND pendingEnrollmentFirstRequestAtEpochMillis IS NULL
          AND pendingEnrollmentReplayDeadlineEpochMillis IS NULL
    """)
    fun markPendingEnrollmentRequestStarted(
        id: Long,
        ownerNonce: String,
        enrollmentAttemptId: String,
        firstRequestAtEpochMillis: Long,
        replayDeadlineEpochMillis: Long,
    ): Int

    @Query("""
        DELETE FROM upload_servers
        WHERE id = :id
          AND reservationNonce = :ownerNonce
          AND pendingEnrollmentAttemptId = :enrollmentAttemptId
          AND enrollmentIssuedAtEpochMillis IS NULL
          AND enabled = 0
    """)
    fun deletePendingEnrollmentAttempt(
        id: Long,
        ownerNonce: String,
        enrollmentAttemptId: String,
    ): Int

    @Query("""
        DELETE FROM upload_servers
        WHERE id = :id
          AND enrollmentSetupComplete = 0
          AND enrollmentIssuedAtEpochMillis IS NULL
          AND enabled = 0
    """)
    fun deleteCorruptPendingEnrollment(id: Long): Int

    /** Releases only an unfinished slot created by this enrollment attempt. */
    @Query("""
        DELETE FROM upload_servers
        WHERE id = :id
          AND reservationNonce = :ownerNonce
          AND enabled = 0
          AND enrollmentIssuedAtEpochMillis IS NULL
    """)
    fun releaseProvisionalEnrollment(id: Long, ownerNonce: String): Int

    @Query("""
        UPDATE upload_servers
        SET reservationNonce = NULL,
            reservationExpiresAtEpochMillis = NULL,
            pendingAcceptedModuleIds = NULL,
            pendingDeclinedModuleIds = NULL,
            pendingUnavailableModuleIds = NULL,
            pendingEnrollmentAttemptId = NULL,
            pendingEnrollmentAccessCode = NULL,
            pendingEnrollmentInviteExpiresAtEpochMillis = NULL,
            pendingProposedApiKey = NULL,
            pendingEnrollmentSourceDeviceJson = NULL,
            pendingEnrollmentFirstRequestAtEpochMillis = NULL,
            pendingEnrollmentReplayDeadlineEpochMillis = NULL
        WHERE id = :id
          AND reservationNonce = :ownerNonce
          AND enrollmentIssuedAtEpochMillis IS NULL
    """)
    fun clearUnissuedReservation(id: Long, ownerNonce: String): Int

    @Transaction
    fun releaseEnrollmentReservation(reservation: SingleEnrollmentReservation) {
        if (reservation.provisional) {
            releaseProvisionalEnrollment(reservation.serverId, reservation.ownerNonce)
        } else {
            clearUnissuedReservation(reservation.serverId, reservation.ownerNonce)
        }
    }

    @Update
    fun update(server: UploadServerEntity)

    @Query("DELETE FROM upload_servers WHERE id = :id")
    fun delete(id: Long)

    @Query("UPDATE upload_servers SET lastUploadTime = :time, lastUploadError = :error, consecutiveFailures = :failures, lastUploadedTimestamp = :lastTimestamp, lastUploadedQueueId = :lastQueueId WHERE id = :id")
    fun updateUploadStatus(id: Long, time: String, error: String?, failures: Int, lastTimestamp: Long, lastQueueId: Long): Int

    @Query("UPDATE upload_servers SET lastSensorUploadTime = :time, lastSensorUploadError = :error, sensorConsecutiveFailures = :failures, lastUploadedSensorId = :lastSensorId WHERE id = :id")
    fun updateSensorUploadStatus(id: Long, time: String, error: String?, failures: Int, lastSensorId: String?): Int

    @Query("UPDATE upload_servers SET lastBatteryUploadTime = :time, lastBatteryUploadError = :error, batteryConsecutiveFailures = :failures WHERE id = :id")
    fun updateBatteryUploadStatus(id: Long, time: String, error: String?, failures: Int): Int

    @Query("""
        UPDATE upload_servers
        SET lastUploadTime = :time,
            lastUploadError = NULL,
            consecutiveFailures = 0,
            lastUploadedTimestamp = :lastTimestamp,
            lastUploadedQueueId = :lastQueueId,
            lastUsageUploadAttemptTime = :time,
            lastUsageUploadSuccessTime = :time,
            usageUploadSuccessCount = usageUploadSuccessCount + :count
        WHERE id = :id
    """)
    fun recordUsageUploadSuccess(id: Long, time: String, lastTimestamp: Long, lastQueueId: Long, count: Int): Int

    @Query("""
        UPDATE upload_servers
        SET lastUploadTime = :time,
            lastUploadError = :error,
            consecutiveFailures = :failures,
            lastUsageUploadAttemptTime = :time,
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
            lastSensorUploadAttemptTime = :time,
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
            lastSensorUploadAttemptTime = :time,
            sensorUploadFailureCount = sensorUploadFailureCount + 1
        WHERE id = :id
    """)
    fun recordSensorUploadFailure(id: Long, time: String, error: String, failures: Int): Int

    @Query("""
        UPDATE upload_servers
        SET lastBatteryUploadTime = :time,
            lastBatteryUploadError = NULL,
            batteryConsecutiveFailures = 0,
            lastBatteryUploadAttemptTime = :time,
            lastBatteryUploadSuccessTime = :time,
            batteryUploadSuccessCount = batteryUploadSuccessCount + :count
        WHERE id = :id
    """)
    fun recordBatteryUploadSuccess(id: Long, time: String, count: Int): Int

    @Query("""
        UPDATE upload_servers
        SET lastBatteryUploadTime = :time,
            lastBatteryUploadError = :error,
            batteryConsecutiveFailures = :failures,
            lastBatteryUploadAttemptTime = :time,
            batteryUploadFailureCount = batteryUploadFailureCount + 1
        WHERE id = :id
    """)
    fun recordBatteryUploadFailure(id: Long, time: String, error: String, failures: Int): Int

    // Cursor MIN must include disabled rows so a temporarily-disabled server's data
    // isn't trimmed past its frozen cursor; re-enabling would otherwise lose
    // every event between the disabled cursor and the new MIN. Hard delete
    // (Settings → Delete Server) removes the row entirely and is the only path
    // that releases data held for that server.
    @Query("SELECT MIN(lastUploadedTimestamp) FROM upload_servers")
    fun getMinUploadCursor(): Long?

    @Query("SELECT lastUploadedTimestamp, lastUploadedQueueId FROM upload_servers ORDER BY lastUploadedTimestamp ASC, lastUploadedQueueId ASC LIMIT 1")
    fun getMinUploadQueueCursor(): UploadQueueCursor?

    @Query("UPDATE upload_servers SET enabled = :enabled, consecutiveFailures = CASE WHEN :enabled = 1 THEN 0 ELSE consecutiveFailures END, sensorConsecutiveFailures = CASE WHEN :enabled = 1 THEN 0 ELSE sensorConsecutiveFailures END, batteryConsecutiveFailures = CASE WHEN :enabled = 1 THEN 0 ELSE batteryConsecutiveFailures END WHERE id = :id AND (:enabled = 0 OR enrollmentSetupComplete = 1)")
    fun setEnabled(id: Long, enabled: Boolean): Int

    companion object {
        const val ENROLLMENT_RESERVATION_LEASE_MILLIS: Long = 15 * 60 * 1_000L
    }
}
