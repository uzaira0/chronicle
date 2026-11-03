package com.openlattice.chronicle.preferences

import android.content.Context
import com.openlattice.chronicle.api.MobileEnrollmentManifest
import com.openlattice.chronicle.collection.CollectionModuleId
import com.openlattice.chronicle.collection.AndroidDataCollectionSetting
import com.openlattice.chronicle.collection.state.CollectionModuleState
import com.openlattice.chronicle.collection.state.CollectionStateMachine
import com.openlattice.chronicle.collection.state.ResearchErasureFence
import com.openlattice.chronicle.serialization.ChronicleJson
import com.openlattice.chronicle.storage.UploadServerEntity
import com.openlattice.chronicle.storage.checkLocalStoreWrite
import java.net.URI
import java.util.Locale
import java.util.UUID

/**
 * Verifies enrollment evidence before using the settings state applied for that enrollment.
 * Consent-gated modules also require the participant's current decision; an operational module follows
 * [appliedOperationalModules], the latest applied settings, once a settings sync has stored them.
 */
internal fun configuredStudyModuleEnabled(
    server: UploadServerEntity?,
    studyId: UUID,
    participantId: String,
    moduleId: CollectionModuleId,
    currentState: CollectionModuleState? = null,
    appliedOperationalModules: Set<CollectionModuleId>? = null,
): Boolean {
    val manifest = verifiedManifest(server, studyId, participantId) ?: return false
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
    return !consentGated && (appliedOperationalModules?.contains(moduleId) ?: enabledAtEnrollment)
}

/** The enrollment's manifest, when the stored enrollment evidence matches the expected study, participant and origin. */
private fun verifiedManifest(server: UploadServerEntity?, studyId: UUID, participantId: String): MobileEnrollmentManifest? {
    if (server == null || !server.enabled || !server.enrollmentSetupComplete) return null
    if (server.studyId != studyId.toString() || server.participantId != participantId) return null
    if (server.manifestDigest?.let(LOWERCASE_SHA_256::matches) != true) return null
    val manifest = server.studyDisclosureJson?.let { raw ->
        runCatching { ChronicleJson.moshi.adapter(MobileEnrollmentManifest::class.java).fromJson(raw) }.getOrNull()
    } ?: return null
    return manifest.takeIf {
        it.studyId == studyId && it.participantId == participantId &&
            it.participantPolicy.version == server.disclosureVersion &&
            canonicalHttpsOrigin(it.serverOrigin) == canonicalHttpsOrigin(server.url)
    }
}

/** The operational modules (those with no consent decision) the latest applied study settings enable, per enrollment. */
internal object AppliedOperationalModules {
    private const val PREFS = "applied_operational_modules"

    private fun prefs(context: Context) = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun save(context: Context, owner: UploadServerEntity, enabled: Set<CollectionModuleId>) = checkLocalStoreWrite(
        prefs(context).edit().clear()
            .putStringSet(ResearchErasureFence.enrollmentKey(owner), enabled.mapTo(HashSet()) { it.id }).commit(),
    )

    fun load(context: Context, owner: UploadServerEntity): Set<CollectionModuleId>? =
        prefs(context).getStringSet(ResearchErasureFence.enrollmentKey(owner), null)
            ?.mapNotNullTo(HashSet(), CollectionModuleId::fromIdOrNull)
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
