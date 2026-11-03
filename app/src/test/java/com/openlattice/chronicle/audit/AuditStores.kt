package com.openlattice.chronicle.audit

import com.openlattice.chronicle.preferences.IDENTIFY_USER
import android.Manifest
import android.app.Application
import android.content.Context
import android.content.SharedPreferences
import com.openlattice.chronicle.EnrollmentTestStores
import com.openlattice.chronicle.api.ChronicleStudyApi
import com.openlattice.chronicle.api.EnrollmentPreviewResponse
import com.openlattice.chronicle.api.MobileEnrollmentManifest
import com.openlattice.chronicle.collection.CollectionModuleId
import com.openlattice.chronicle.collection.CollectionModuleSetting
import com.openlattice.chronicle.collection.state.ParticipantDecision
import com.openlattice.chronicle.data.ParticipationStatus
import com.openlattice.chronicle.preferences.PARTICIPANT_ID
import com.openlattice.chronicle.preferences.PARTICIPATION_STATUS
import com.openlattice.chronicle.preferences.STUDY_ID
import com.openlattice.chronicle.serialization.ChronicleJson
import com.openlattice.chronicle.storage.ChronicleDb
import com.openlattice.chronicle.storage.CollectionModuleStateEntity
import com.openlattice.chronicle.storage.UploadServerEntity
import java.lang.reflect.Proxy
import org.robolectric.Shadows.shadowOf

/** Installs an API for the duration of one test operation, restoring a nested fixture on exit. */
internal fun <T> withStudyApi(onCall: (String) -> Any?, action: () -> T): T {
    return EnrollmentTestStores.installApi(studyApi { method, _ -> onCall(method) }).use { action() }
}

internal fun studyApi(onCall: (String, Array<out Any?>?) -> Any?): ChronicleStudyApi {
    val apiType = ChronicleStudyApi::class.java
    return Proxy.newProxyInstance(apiType.classLoader, arrayOf(apiType)) { _, method, args ->
        onCall(method.name, args)
    } as ChronicleStudyApi
}

/**
 * Uses the shared Robolectric native-store shadows with real preferences and Room operations.
 * An enrolled device is an eight-module study: its modules enabled and accepted, and the
 * participant's device-user identification switched on, so every participant control renders.
 */
object AuditStores {
    private val EIGHT_MODULES = listOf(
        CollectionModuleId.USAGE_EVENTS,
        CollectionModuleId.IN_APP_ACTIVITY_CLASS,
        CollectionModuleId.DEVICE_LIFECYCLE,
        CollectionModuleId.USER_IDENTIFICATION,
        CollectionModuleId.UPLOAD_TELEMETRY,
        CollectionModuleId.BATTERY_TELEMETRY,
        CollectionModuleId.CONNECTIVITY_STATE,
        CollectionModuleId.DEVICE_SETTINGS,
    )

    fun install(
        context: Context,
        enrolled: Boolean,
        manifestOverrides: Map<CollectionModuleId, CollectionModuleSetting> = emptyMap(),
    ) {
        EnrollmentTestStores.install(context, enrolled, manifestOverrides)
    }

    internal fun enroll(
        context: Context,
        db: ChronicleDb,
        prefs: SharedPreferences,
        manifestOverrides: Map<CollectionModuleId, CollectionModuleSetting>,
    ) {
        // A participant who allowed notifications, so identification is active, not paused.
        shadowOf(context as Application).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
        val preview = eightModulePreview(manifestOverrides)
        val manifest = preview.manifest
        db.uploadServerDao().insert(
            UploadServerEntity(
                name = manifest.studyTitle,
                url = manifest.serverOrigin,
                studyId = manifest.studyId.toString(),
                participantId = manifest.participantId,
                sourceRandomDeviceId = "device-audit-test",
                apiKey = "synthetic-audit-key",
                studyDisclosureJson = ChronicleJson.moshi.adapter(MobileEnrollmentManifest::class.java).toJson(manifest),
                disclosureVersion = manifest.participantPolicy.version,
                manifestDigest = preview.manifestDigest,
            ),
        )
        db.collectionModuleStateDao().upsertAll(
            (EIGHT_MODULES + manifestOverrides.filterValues { it.enabled }.keys).distinct().map {
                CollectionModuleStateEntity(
                    moduleId = it.id,
                    serverEnabled = true,
                    decision = ParticipantDecision.ACCEPTED.name,
                    decidedAtEpochMillis = 1_760_000_000_000,
                    requiredApplied = it == CollectionModuleId.USAGE_EVENTS,
                    appliedVersion = manifest.collectionSettings.settingsVersion,
                )
            },
        )
        prefs.edit()
            .putString(STUDY_ID, manifest.studyId.toString())
            .putString(PARTICIPANT_ID, manifest.participantId)
            .putString(PARTICIPATION_STATUS, ParticipationStatus.ENROLLED.name)
            .putBoolean(IDENTIFY_USER, true)
            .commit()
    }

    /** The shared unit-test enrollment fixture with the eight modules switched on. */
    private fun eightModulePreview(
        overrides: Map<CollectionModuleId, CollectionModuleSetting>,
    ): EnrollmentPreviewResponse {
        val json = AuditStores::class.java.getResource("/enrollment-preview.json")!!.readText()
        val parsed = ChronicleJson.moshi.adapter(EnrollmentPreviewResponse::class.java).fromJson(json)!!
        return parsed.copy(
            manifest = parsed.manifest.copy(
                serverOrigin = "https://localhost",
                collectionSettings = parsed.manifest.collectionSettings.copy(
                    modules = parsed.manifest.collectionSettings.modules +
                        EIGHT_MODULES.associateWith { CollectionModuleSetting(enabled = true) } + overrides,
                ),
            ),
        )
    }

}
