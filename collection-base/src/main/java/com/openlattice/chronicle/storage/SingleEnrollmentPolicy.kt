package com.openlattice.chronicle.storage

/**
 * Chronicle supports one active logical study enrollment per app installation.
 *
 * A credential refresh is allowed only when the server, study, and participant identity are all
 * unchanged. A different destination or identity must go through the explicit withdrawal flow
 * first so queued records and consent cannot cross a study boundary.
 */
sealed interface SingleEnrollmentResolution {
    data object Insert : SingleEnrollmentResolution
    data class Refresh(val serverId: Long) : SingleEnrollmentResolution
    data class ReplaceProvisional(val serverId: Long) : SingleEnrollmentResolution
    data class Reject(val existing: UploadServerEntity) : SingleEnrollmentResolution
}

data class SingleEnrollmentReservation(
    val serverId: Long,
    val provisional: Boolean,
    val ownerNonce: String,
)

class SingleEnrollmentConflictException : IllegalStateException(
    "Withdraw from the current study before enrolling in another study.",
)

fun UploadServerEntity.reservationIsOwnedBy(ownerNonce: String, nowEpochMillis: Long): Boolean =
    reservationNonce == ownerNonce &&
        reservationExpiresAtEpochMillis?.let { nowEpochMillis <= it } == true

fun UploadServerEntity.hasRecoverableIssuedEnrollment(): Boolean =
    !enrollmentSetupComplete &&
        enrollmentIssuedAtEpochMillis != null &&
        !apiKey.isNullOrBlank() &&
        !studyDisclosureJson.isNullOrBlank() &&
        !manifestDigest.isNullOrBlank() &&
        !reservationNonce.isNullOrBlank()

fun UploadServerEntity.pendingEnrollmentAttemptIsExpired(nowEpochMillis: Long): Boolean {
    if (pendingEnrollmentAttemptId == null || enrollmentIssuedAtEpochMillis != null) return false
    return if (pendingEnrollmentFirstRequestAtEpochMillis == null) {
        pendingEnrollmentReplayDeadlineEpochMillis == null &&
            pendingEnrollmentInviteExpiresAtEpochMillis?.let { it <= nowEpochMillis } == true
    } else {
        pendingEnrollmentReplayDeadlineEpochMillis?.let { it <= nowEpochMillis } == true
    }
}

fun resolveSingleEnrollment(
    existing: List<UploadServerEntity>,
    requestedUrl: String,
    requestedStudyId: String,
    requestedParticipantId: String,
    nowEpochMillis: Long = System.currentTimeMillis(),
): SingleEnrollmentResolution {
    if (existing.isEmpty()) return SingleEnrollmentResolution.Insert
    if (existing.size != 1) return SingleEnrollmentResolution.Reject(existing.first())

    val enrolled = existing.single()
    val sameIdentity =
        enrolled.url == requestedUrl &&
        enrolled.studyId == requestedStudyId &&
        enrolled.participantId == requestedParticipantId
    return if (
        sameIdentity && enrolled.pendingEnrollmentAttemptIsExpired(nowEpochMillis)
    ) {
        SingleEnrollmentResolution.ReplaceProvisional(enrolled.id)
    } else if (
        sameIdentity &&
        !enrolled.enabled &&
        !enrolled.enrollmentSetupComplete &&
        enrolled.enrollmentIssuedAtEpochMillis == null
    ) {
        SingleEnrollmentResolution.Refresh(enrolled.id)
    } else if (sameIdentity) {
        SingleEnrollmentResolution.Reject(enrolled)
    } else if (
        !enrolled.enabled &&
        !enrolled.enrollmentSetupComplete &&
        enrolled.enrollmentIssuedAtEpochMillis == null &&
        enrolled.apiKey == null
    ) {
        SingleEnrollmentResolution.ReplaceProvisional(enrolled.id)
    } else {
        SingleEnrollmentResolution.Reject(enrolled)
    }
}
