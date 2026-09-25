package com.openlattice.chronicle.layout

import android.Manifest
import android.app.Application
import android.content.Context
import androidx.room.Room
import com.openlattice.chronicle.R
import com.openlattice.chronicle.api.EnrollmentPreviewResponse
import com.openlattice.chronicle.api.MobileEnrollmentManifest
import com.openlattice.chronicle.collection.CollectionModuleId
import com.openlattice.chronicle.collection.CollectionModuleSetting
import com.openlattice.chronicle.collection.state.ParticipantDecision
import com.openlattice.chronicle.data.ParticipationStatus
import com.openlattice.chronicle.preferences.EncryptedPrefsHelper
import com.openlattice.chronicle.preferences.PARTICIPANT_ID
import com.openlattice.chronicle.preferences.PARTICIPATION_STATUS
import com.openlattice.chronicle.preferences.STUDY_ID
import com.openlattice.chronicle.serialization.ChronicleJson
import com.openlattice.chronicle.storage.ChronicleDb
import com.openlattice.chronicle.storage.CollectionModuleStateEntity
import com.openlattice.chronicle.storage.UploadServerEntity
import org.robolectric.Shadows.shadowOf

/**
 * Robolectric has no AndroidKeyStore and no SQLCipher native library. Pre-fill the two cached
 * singletons with plain SharedPreferences and an in-memory Room database so real screens start.
 * An enrolled device is the Chile study: its eight modules enabled and accepted, and the
 * participant's device-user identification switched on, so every participant control renders.
 */
object TestStores {
    private val CHILE_MODULES = listOf(
        CollectionModuleId.USAGE_EVENTS,
        CollectionModuleId.IN_APP_ACTIVITY_CLASS,
        CollectionModuleId.DEVICE_LIFECYCLE,
        CollectionModuleId.USER_IDENTIFICATION,
        CollectionModuleId.UPLOAD_TELEMETRY,
        CollectionModuleId.BATTERY_TELEMETRY,
        CollectionModuleId.CONNECTIVITY_STATE,
        CollectionModuleId.DEVICE_SETTINGS,
    )

    fun install(context: Context, enrolled: Boolean) {
        val prefs = context.getSharedPreferences("layout_test_prefs", Context.MODE_PRIVATE)
        prefs.edit().clear().commit()
        setStatic(EncryptedPrefsHelper::class.java, "instance", EncryptedPrefsHelper, prefs)
        val db = Room.inMemoryDatabaseBuilder(context, ChronicleDb::class.java)
            .allowMainThreadQueries()
            .build()
        setStatic(ChronicleDb::class.java, "INSTANCE", null, db)
        if (!enrolled) return

        // A participant who allowed notifications, so identification is active, not paused.
        shadowOf(context as Application).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
        val preview = chilePreview()
        val manifest = preview.manifest
        prefs.edit()
            .putString(STUDY_ID, manifest.studyId.toString())
            .putString(PARTICIPANT_ID, manifest.participantId)
            .putString(PARTICIPATION_STATUS, ParticipationStatus.ENROLLED.name)
            .putBoolean(context.getString(R.string.identify_user), true)
            .commit()
        db.uploadServerDao().insert(
            UploadServerEntity(
                name = manifest.studyTitle,
                url = manifest.serverOrigin,
                studyId = manifest.studyId.toString(),
                participantId = manifest.participantId,
                sourceDeviceId = "device-layout-test",
                studyDisclosureJson = ChronicleJson.moshi.adapter(MobileEnrollmentManifest::class.java).toJson(manifest),
                disclosureVersion = manifest.participantPolicy.version,
                manifestDigest = preview.manifestDigest,
            ),
        )
        db.collectionModuleStateDao().upsertAll(
            CHILE_MODULES.map {
                CollectionModuleStateEntity(
                    moduleId = it.id,
                    serverEnabled = true,
                    decision = ParticipantDecision.ACCEPTED.name,
                    decidedAtEpochMillis = 1_760_000_000_000,
                    requiredApplied = it == CollectionModuleId.USAGE_EVENTS,
                    appliedVersion = manifest.collectionSettings.settingsVersion,
                    appliedPolicySnapshot = null,
                    lastDisposition = null,
                )
            },
        )
    }

    /** The shared unit-test enrollment fixture with the Chile modules switched on. */
    private fun chilePreview(): EnrollmentPreviewResponse {
        val json = TestStores::class.java.getResource("/enrollment-preview.json")!!.readText()
        val parsed = ChronicleJson.moshi.adapter(EnrollmentPreviewResponse::class.java).fromJson(json)!!
        return parsed.copy(
            manifest = parsed.manifest.copy(
                collectionSettings = parsed.manifest.collectionSettings.copy(
                    modules = parsed.manifest.collectionSettings.modules +
                        CHILE_MODULES.associateWith { CollectionModuleSetting(enabled = true) },
                ),
            ),
        )
    }

    private fun setStatic(owner: Class<*>, field: String, receiver: Any?, value: Any) {
        owner.getDeclaredField(field).apply { isAccessible = true }.set(receiver, value)
    }
}
