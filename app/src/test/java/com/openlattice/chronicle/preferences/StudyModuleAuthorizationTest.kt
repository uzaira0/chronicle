package com.openlattice.chronicle.preferences

import com.openlattice.chronicle.api.EnrollmentPreviewResponse
import com.openlattice.chronicle.api.MobileEnrollmentManifest
import com.openlattice.chronicle.collection.CollectionModuleId
import com.openlattice.chronicle.collection.CollectionModuleSetting
import com.openlattice.chronicle.serialization.ChronicleJson
import com.openlattice.chronicle.storage.UploadServerEntity
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class StudyModuleAuthorizationTest {

    @Test
    fun enabledManifestModuleAuthorizesOnlyItsExactEnrollmentIdentity() {
        val preview = previewWith(CollectionModuleId.USER_IDENTIFICATION, enabled = true)
        val server = server(preview)

        assertTrue(
            configuredStudyModuleEnabled(
                server,
                preview.manifest.studyId,
                preview.manifest.participantId,
                CollectionModuleId.USER_IDENTIFICATION,
            ),
        )
        assertFalse(
            configuredStudyModuleEnabled(
                server.copy(participantId = "other-participant"),
                preview.manifest.studyId,
                preview.manifest.participantId,
                CollectionModuleId.USER_IDENTIFICATION,
            ),
        )
    }

    @Test
    fun disabledMissingCorruptOrIncompleteManifestFailsClosed() {
        val preview = previewWith(CollectionModuleId.USER_IDENTIFICATION, enabled = false)
        val server = server(preview)

        assertFalse(
            configuredStudyModuleEnabled(
                server,
                preview.manifest.studyId,
                preview.manifest.participantId,
                CollectionModuleId.USER_IDENTIFICATION,
            ),
        )
        listOf(
            server.copy(studyDisclosureJson = null),
            server.copy(studyDisclosureJson = "not-json"),
            server.copy(enabled = false),
            server.copy(enrollmentSetupComplete = false),
            server.copy(disclosureVersion = "other-version"),
        ).forEach { invalid ->
            assertFalse(
                configuredStudyModuleEnabled(
                    invalid,
                    preview.manifest.studyId,
                    preview.manifest.participantId,
                    CollectionModuleId.USER_IDENTIFICATION,
                ),
            )
        }
    }

    @Test
    fun androidUnsupportedModuleIsNeverAuthorizedEvenWhenExplicitlyEnabled() {
        val preview = previewWith(CollectionModuleId.AMBIENT_AUDIO, enabled = true)

        assertFalse(
            configuredStudyModuleEnabled(
                server(preview),
                preview.manifest.studyId,
                preview.manifest.participantId,
                CollectionModuleId.AMBIENT_AUDIO,
            ),
        )
    }

    private fun previewWith(moduleId: CollectionModuleId, enabled: Boolean): EnrollmentPreviewResponse {
        val previewJson = javaClass.getResource("/enrollment-preview.json")?.readText()
            ?: error("Missing enrollment-preview.json")
        val parsed = ChronicleJson.moshi.adapter(EnrollmentPreviewResponse::class.java)
            .fromJson(previewJson)!!
        return parsed.copy(
            manifest = parsed.manifest.copy(
                collectionSettings = parsed.manifest.collectionSettings.copy(
                    modules = parsed.manifest.collectionSettings.modules +
                        (moduleId to CollectionModuleSetting(enabled = enabled)),
                ),
            ),
        )
    }

    private fun server(preview: EnrollmentPreviewResponse): UploadServerEntity = UploadServerEntity(
        name = preview.manifest.studyTitle,
        url = preview.manifest.serverOrigin,
        studyId = preview.manifest.studyId.toString(),
        participantId = preview.manifest.participantId,
        sourceDeviceId = "device-a",
        studyDisclosureJson = ChronicleJson.moshi
            .adapter(MobileEnrollmentManifest::class.java)
            .toJson(preview.manifest),
        disclosureVersion = preview.manifest.participantPolicy.version,
        manifestDigest = preview.manifestDigest,
        enabled = true,
        enrollmentSetupComplete = true,
    )
}
