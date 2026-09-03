package com.openlattice.chronicle

import com.openlattice.chronicle.api.EnrollmentPreviewResponse
import com.openlattice.chronicle.collection.CollectionModuleId
import com.openlattice.chronicle.collection.CollectionModuleSetting
import com.openlattice.chronicle.serialization.ChronicleJson
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class StudyDisclosureCopyTest {

    @Test
    fun disclosureIncludesEveryRequiredStudyConsentElementAndDestination() {
        val previewJson = javaClass.getResource("/enrollment-preview.json")?.readText()
            ?: error("Missing enrollment-preview.json")
        val preview = ChronicleJson.moshi.adapter(EnrollmentPreviewResponse::class.java)
            .fromJson(previewJson)!!

        val copy = StudyDisclosureCopy.from(preview)
        val visibleCopy = copy.title + "\n" + copy.body

        listOf(
            "Example Study",
            "Example Research Institute",
            "study-team@example.org",
            "Study daily routines",
            "Twelve weeks",
            "Chronicle collects approved modules",
            "Daily patterns may be sensitive",
            "No direct benefit is promised",
            "Coded data is available",
            "retained for seven years",
            "research.example.org",
            "consent-1",
        ).forEach { requiredText ->
            assertTrue("Missing disclosure text: $requiredText", visibleCopy.contains(requiredText))
        }
    }

    @Test
    fun disclosureNamesEveryEnabledFunctionThatTheModuleWizardDoesNotPresent() {
        val preview = preview().let { parsed ->
            parsed.copy(
                manifest = parsed.manifest.copy(
                    collectionSettings = parsed.manifest.collectionSettings.copy(
                        modules = parsed.manifest.collectionSettings.modules +
                            (CollectionModuleId.USER_IDENTIFICATION to
                                CollectionModuleSetting(enabled = true)),
                    ),
                ),
            )
        }

        val body = StudyDisclosureCopy.from(preview).body

        listOf(
            "Enabled study functions covered by this agreement",
            "Upload delivery diagnostics",
            "Sensor availability",
            "Questionnaire reminders",
        ).forEach { requiredText ->
            assertTrue("Missing enabled-function disclosure: $requiredText", body.contains(requiredText))
        }
    }

    @Test
    fun disclosureDoesNotClaimAndroidUnsupportedAmbientAudio() {
        val baseline = preview()
        val withIosOnlyModule = baseline.copy(
            manifest = baseline.manifest.copy(
                collectionSettings = baseline.manifest.collectionSettings.copy(
                    modules = baseline.manifest.collectionSettings.modules +
                        (CollectionModuleId.AMBIENT_AUDIO to CollectionModuleSetting(enabled = true)),
                ),
            ),
        )

        assertEquals(
            nonChoiceModuleDisclosureLines(baseline),
            nonChoiceModuleDisclosureLines(withIosOnlyModule),
        )
    }

    private fun preview(): EnrollmentPreviewResponse {
        val previewJson = javaClass.getResource("/enrollment-preview.json")?.readText()
            ?: error("Missing enrollment-preview.json")
        return ChronicleJson.moshi.adapter(EnrollmentPreviewResponse::class.java)
            .fromJson(previewJson)!!
    }
}
