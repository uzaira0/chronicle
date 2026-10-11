package com.openlattice.chronicle.preferences

import com.openlattice.chronicle.api.MobileEnrollmentManifest
import com.openlattice.chronicle.collection.CollectionModuleId
import com.openlattice.chronicle.collection.AndroidDataCollectionSetting
import com.openlattice.chronicle.collection.state.CollectionModuleState
import com.openlattice.chronicle.collection.state.CollectionStateMachine
import com.openlattice.chronicle.serialization.ChronicleJson
import com.openlattice.chronicle.storage.UploadServerEntity
import java.net.URI
import java.util.Locale
import java.util.UUID

/**
 * Verifies enrollment evidence before using the settings state applied for that enrollment.
 * Consent-gated modules also require the participant's current decision.
 */
internal fun configuredStudyModuleEnabled(
    server: UploadServerEntity?,
    studyId: UUID,
    participantId: String,
    moduleId: CollectionModuleId,
    currentState: CollectionModuleState? = null,
): Boolean {
    if (server == null || !server.enabled || !server.enrollmentSetupComplete) return false
    if (server.studyId != studyId.toString() || server.participantId != participantId) return false
    val rawManifest = server.studyDisclosureJson ?: return false
    val disclosureVersion = server.disclosureVersion ?: return false
    val manifestDigest = server.manifestDigest ?: return false
    if (!LOWERCASE_SHA_256.matches(manifestDigest)) return false
    val manifest = runCatching {
        ChronicleJson.moshi.adapter(MobileEnrollmentManifest::class.java).fromJson(rawManifest)
    }.getOrNull() ?: return false
    if (
        manifest.studyId != studyId ||
        manifest.participantId != participantId ||
        manifest.participantPolicy.version != disclosureVersion ||
        canonicalHttpsOrigin(manifest.serverOrigin) != canonicalHttpsOrigin(server.url)
    ) return false
    val enabledAtEnrollment = moduleId in manifest.collectionSettings.effectiveEnabledModuleIds()
    val consentGated = moduleId in CollectionStateMachine.ACK_GATED_MODULES
    if (currentState != null) {
        return moduleId in AndroidDataCollectionSetting.androidSupportedModuleIds &&
            currentState.moduleId == moduleId &&
            currentState.appliedVersion >= manifest.collectionSettings.settingsVersion &&
            (currentState.appliedVersion > manifest.collectionSettings.settingsVersion || enabledAtEnrollment) &&
            (if (consentGated) currentState.collectsWhenEnrolled
            else currentState.serverEnabled)
    }
    return enabledAtEnrollment && !consentGated
}

private fun canonicalHttpsOrigin(raw: String): String? {
    val uri = runCatching { URI(raw) }.getOrNull() ?: return null
    val scheme = uri.scheme?.lowercase(Locale.ROOT)
    val host = uri.host?.lowercase(Locale.ROOT)
    if (
        scheme != "https" ||
        host.isNullOrBlank() ||
        uri.userInfo != null ||
        uri.query != null ||
        uri.fragment != null ||
        (!uri.path.isNullOrEmpty() && uri.path != "/") ||
        (uri.port != -1 && uri.port !in 1..65535)
    ) return null
    return buildString {
        append("https://")
        append(host)
        if (uri.port != -1 && uri.port != 443) append(":${uri.port}")
    }
}

private val LOWERCASE_SHA_256 = Regex("^[0-9a-f]{64}$")
