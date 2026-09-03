package com.openlattice.chronicle.api

import com.openlattice.chronicle.collection.AndroidDataCollectionSetting
import com.openlattice.chronicle.study.StudyParticipantPolicy
import java.net.URI
import java.time.OffsetDateTime
import java.util.UUID

/** Android wire view of the API enrollment manifest. */
data class MobileEnrollmentManifest(
    val schemaVersion: Int,
    val serverOrigin: String,
    val studyId: UUID,
    val participantId: String,
    val studyTitle: String,
    val studyDescription: String,
    val participantPolicy: StudyParticipantPolicy,
    val collectionSettings: AndroidDataCollectionSetting,
    val settingsVersion: Int,
    val issuedAt: OffsetDateTime,
    val expiresAt: OffsetDateTime,
) {
    init {
        require(schemaVersion == 1) { "Unsupported enrollment manifest schema: $schemaVersion" }
        require(participantId.isNotBlank()) { "participantId must not be blank" }
        require(studyTitle.isNotBlank()) { "studyTitle must not be blank" }
        require(settingsVersion == collectionSettings.settingsVersion) {
            "settingsVersion must match collectionSettings.settingsVersion"
        }
        require(expiresAt.isAfter(issuedAt)) { "Enrollment manifest must expire after issuance" }
        val origin = runCatching { URI(serverOrigin) }.getOrNull()
        require(
            origin != null &&
                origin.isAbsolute &&
                origin.scheme.equals("https", ignoreCase = true) &&
                !origin.host.isNullOrBlank() &&
                origin.userInfo == null &&
                (origin.path.isNullOrEmpty() || origin.path == "/") &&
                origin.query == null &&
                origin.fragment == null
        ) { "serverOrigin must be an HTTPS root origin" }
    }
}

data class EnrollmentPreviewResponse(
    val manifest: MobileEnrollmentManifest,
    val manifestDigest: String,
) {
    init {
        require(MANIFEST_DIGEST.matches(manifestDigest)) {
            "manifestDigest must be a lowercase SHA-256 hex digest"
        }
    }

    private companion object {
        val MANIFEST_DIGEST = Regex("^[0-9a-f]{64}$")
    }
}
