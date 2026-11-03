package com.openlattice.chronicle

import com.openlattice.chronicle.participants.ParticipantIds
import com.openlattice.chronicle.security.InvitedServerCa
import com.openlattice.chronicle.utils.Utils
import java.net.URI
import java.net.URLDecoder
import java.nio.charset.StandardCharsets
import java.security.cert.X509Certificate
import java.util.UUID

internal sealed interface EnrollmentLink {
    data class Invalid(val message: Int, val field: EnrollmentField? = null) : EnrollmentLink
    class Prefill(
        val studyId: UUID?,
        val participantId: String?,
        val serverUrl: String,
        val accessCode: String?,
        /** The server's private CA root from the link's `serverCa`, for a LAN trial server. */
        val serverCa: X509Certificate? = null,
    ) : EnrollmentLink
}

internal enum class EnrollmentField { STUDY, PARTICIPANT, SERVER }

internal sealed interface EnrollmentInputResult {
    data class Invalid(val message: Int, val field: EnrollmentField? = null) : EnrollmentInputResult
    class Valid(val invitation: EnrollmentInvitation) : EnrollmentInputResult
}

internal fun validateEnrollmentInput(
    studyId: String,
    participantId: String,
    serverUrl: String,
    accessCode: String?,
): EnrollmentInputResult {
    if (studyId.isBlank()) return EnrollmentInputResult.Invalid(R.string.invalid_study_id_blank, EnrollmentField.STUDY)
    if (participantId.isBlank()) return EnrollmentInputResult.Invalid(R.string.invalid_participant, EnrollmentField.PARTICIPANT)
    return when (val fields = enrollmentFields(studyId, participantId, serverUrl, accessCode)) {
        is EnrollmentLink.Invalid -> EnrollmentInputResult.Invalid(fields.message, fields.field)
        is EnrollmentLink.Prefill -> if (accessCode.isNullOrBlank()) EnrollmentInputResult.Invalid(R.string.enrollment_link_required)
        else EnrollmentInputResult.Valid(EnrollmentInvitation(
            requireNotNull(fields.studyId), requireNotNull(fields.participantId), fields.serverUrl,
            accessCode))
    }
}

internal fun parseEnrollmentLink(uri: String, detachedAccessCode: String?): EnrollmentLink {
    val destination = runCatching { URI(uri) }.getOrNull()
        ?: return EnrollmentLink.Invalid(R.string.enrollment_invitation_unverified)
    val enrollmentDestination = !destination.isOpaque && destination.scheme == "chronicle" && destination.host == "enroll"
    if (!enrollmentDestination || !destination.path.isNullOrEmpty()) {
        return EnrollmentLink.Invalid(R.string.enrollment_invitation_unverified)
    }
    return enrollmentFields(destination.enrollmentParameter("studyId"), destination.enrollmentParameter("participantId"),
        destination.enrollmentParameter("serverUrl"), detachedAccessCode, destination.enrollmentParameter("serverCa"))
}

private fun enrollmentFields(
    study: String?,
    participant: String?,
    server: String?,
    accessCode: String?,
    serverCa: String? = null,
): EnrollmentLink {
    val studyId = study?.trim()?.takeIf(String::isNotBlank)
    val parsedStudyId = studyId?.let { runCatching { UUID.fromString(it) }.getOrNull() }
    if (studyId != null && parsedStudyId == null) {
        return EnrollmentLink.Invalid(R.string.invalid_study_id_format, EnrollmentField.STUDY)
    }
    val participantId = participant?.trim()?.takeIf(String::isNotBlank)
    val trustedServerUrl = server?.let(Utils::normalizeTrustedServerUrl)
    val invitedRoot = serverCa?.let(InvitedServerCa::parse)
    return when {
        participantId != null && !ParticipantIds.isValid(participantId) ->
            EnrollmentLink.Invalid(R.string.invalid_participant, EnrollmentField.PARTICIPANT)
        server.isNullOrBlank() -> EnrollmentLink.Invalid(R.string.enrollment_missing_server_url, EnrollmentField.SERVER)
        trustedServerUrl == null -> EnrollmentLink.Invalid(R.string.enrollment_untrusted_server_url, EnrollmentField.SERVER)
        serverCa != null && (invitedRoot == null || !InvitedServerCa.acceptsOrigin(trustedServerUrl)) ->
            EnrollmentLink.Invalid(R.string.enrollment_invitation_unverified)
        else -> EnrollmentLink.Prefill(parsedStudyId, participantId, trustedServerUrl, accessCode, invitedRoot)
    }
}

private fun URI.enrollmentParameter(name: String): String? = rawQuery?.split('&')
    ?.firstOrNull { it.substringBefore('=') == name }?.substringAfter('=', "")
    ?.let { URLDecoder.decode(it, StandardCharsets.UTF_8.name()) }
