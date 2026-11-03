package com.openlattice.chronicle.services.upload

import android.content.Context
import com.openlattice.chronicle.preferences.EnrollmentSettings
import com.openlattice.chronicle.storage.hasDeviceCredential
import com.openlattice.chronicle.storage.ChronicleDb
import com.openlattice.chronicle.storage.UploadServerEntity
import com.openlattice.chronicle.utils.Utils
import java.util.UUID

class UploadPolicy(
    private val context: Context,
    private val chronicleDb: ChronicleDb,
) {

    fun resolveDestination(): UploadDestinationResolution =
        exactActiveEnrollmentServerResolution(context, chronicleDb)

    fun getEligibleServers(): List<UploadServerEntity> {
        // TODO: Add wifi-only check (respect user preference for metered data)
        // TODO: Add battery level check (skip upload below threshold)
        return listOfNotNull(resolveDestination().server)
    }
}

enum class UploadDestinationIssue {
    DESTINATION_MISSING,
    DESTINATION_IDENTITY_MISMATCH,
    DESTINATION_SOURCE_DEVICE_MISSING,
    DESTINATION_SETUP_INCOMPLETE,
    DESTINATION_DISABLED,
    DESTINATION_NONCANONICAL,
    DESTINATION_CREDENTIAL_INCOMPLETE,
}

data class UploadDestinationResolution(
    val server: UploadServerEntity?,
    val issue: UploadDestinationIssue?,
) {
    init {
        require((server == null) != (issue == null))
    }
}

/** Re-binds the selected destination to the encrypted active identity at every network boundary. */
internal fun exactActiveEnrollmentServer(
    context: Context,
    chronicleDb: ChronicleDb,
): UploadServerEntity? = exactActiveEnrollmentServerResolution(context, chronicleDb).server

internal fun exactActiveEnrollmentServerResolution(
    context: Context,
    chronicleDb: ChronicleDb,
): UploadDestinationResolution {
    val settings = EnrollmentSettings(context.applicationContext)
    return resolveServerForIdentity(
        chronicleDb.uploadServerDao().getConfiguredServer(),
        settings.getStudyId(),
        settings.getParticipantId(),
    )
}

/**
 * Returns the one fully configured destination only when it belongs to the exact local enrollment.
 * Missing, provisional, disabled, mismatched, or credential-incomplete rows fail closed; callers
 * must never substitute a compiled-in endpoint for any of those states.
 */
internal fun completeServerForIdentity(
    server: UploadServerEntity?,
    studyId: UUID,
    participantId: String,
): UploadServerEntity? = resolveServerForIdentity(
    server,
    studyId,
    participantId,
).server

internal fun resolveServerForIdentity(
    server: UploadServerEntity?,
    studyId: UUID,
    participantId: String,
    requireSetupComplete: Boolean = true,
): UploadDestinationResolution {
    if (server == null) return UploadDestinationResolution(null, UploadDestinationIssue.DESTINATION_MISSING)
    if (server.studyId != studyId.toString() || server.participantId != participantId) {
        return UploadDestinationResolution(null, UploadDestinationIssue.DESTINATION_IDENTITY_MISMATCH)
    }
    if (server.sourceRandomDeviceId.isBlank()) {
        return UploadDestinationResolution(null, UploadDestinationIssue.DESTINATION_SOURCE_DEVICE_MISSING)
    }
    if (requireSetupComplete && !server.enrollmentSetupComplete) {
        return UploadDestinationResolution(null, UploadDestinationIssue.DESTINATION_SETUP_INCOMPLETE)
    }
    if (!server.enabled) {
        return UploadDestinationResolution(null, UploadDestinationIssue.DESTINATION_DISABLED)
    }
    if (Utils.normalizeTrustedServerUrl(server.url) != server.url) {
        return UploadDestinationResolution(null, UploadDestinationIssue.DESTINATION_NONCANONICAL)
    }
    if (!server.hasDeviceCredential()) {
        return UploadDestinationResolution(null, UploadDestinationIssue.DESTINATION_CREDENTIAL_INCOMPLETE)
    }
    return UploadDestinationResolution(server, null)
}

/** Exact provisional owner used only while the initial consent acknowledgment is dispatched. */
internal fun isExpectedProvisionalEnrollmentServer(
    current: UploadServerEntity?,
    expected: UploadServerEntity,
): Boolean {
    val studyId = runCatching { UUID.fromString(expected.studyId) }.getOrNull() ?: return false
    val eligible = resolveServerForIdentity(
        current,
        studyId,
        expected.participantId,
        requireSetupComplete = false,
    ).server ?: return false
    return !eligible.enrollmentSetupComplete &&
        !expected.enrollmentSetupComplete &&
        !expected.reservationNonce.isNullOrBlank() &&
        eligible.reservationNonce == expected.reservationNonce &&
        eligible.studyId == expected.studyId &&
        eligible.participantId == expected.participantId &&
        eligible.sourceRandomDeviceId == expected.sourceRandomDeviceId &&
        eligible.disclosureVersion == expected.disclosureVersion &&
        eligible.manifestDigest == expected.manifestDigest &&
        eligible.url == expected.url &&
        eligible.apiKey == expected.apiKey
}
