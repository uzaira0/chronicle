package com.openlattice.chronicle

import com.openlattice.chronicle.api.EnrollmentPreviewResponse
import com.openlattice.chronicle.collection.CollectionModuleId
import com.openlattice.chronicle.collection.CollectionModuleSetting
import com.openlattice.chronicle.serialization.ChronicleJson
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

@org.junit.runner.RunWith(org.robolectric.RobolectricTestRunner::class)
class StudyDisclosureCopyTest {
    @Test fun withdrawalCopyExplainsThatTheStudyPolicyControlsPriorData() {
        val context = androidx.test.core.app.ApplicationProvider.getApplicationContext<android.content.Context>()
        val message = context.getString(R.string.server_delete_confirm_message)
        assertTrue(message.contains("Data on this device is erased after the server confirms"))
        assertTrue(message.contains("Previously uploaded data is kept or erased according to the study's withdrawal policy"))
        assertEquals("Withdraw from study", context.getString(R.string.server_delete_confirm_action))
    }

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

    @Test fun enrollmentPreviewDisclosesWithdrawalModeBeforeAcceptance() {
        val baseline = preview()
        val labels = StudyDisclosureCopy.Labels()
        for (mode in com.openlattice.chronicle.study.WithdrawalMode.values()) {
            val disclosure = StudyDisclosureCopy.from(baseline.copy(manifest = baseline.manifest.copy(withdrawalMode = mode)))
            val erases = mode == com.openlattice.chronicle.study.WithdrawalMode.ERASE
            assertTrue(disclosure.body.contains(if (erases) labels.withdrawalErases else labels.withdrawalKeeps))
            org.junit.Assert.assertFalse(disclosure.body.contains(if (erases) labels.withdrawalKeeps else labels.withdrawalErases))
        }
    }

    private fun preview(): EnrollmentPreviewResponse {
        val previewJson = javaClass.getResource("/enrollment-preview.json")?.readText()
            ?: error("Missing enrollment-preview.json")
        return ChronicleJson.moshi.adapter(EnrollmentPreviewResponse::class.java)
            .fromJson(previewJson)!!
    }

    @Test fun enrollmentDisclosureRequiresTheAdultOrGuardianAttestation() {
        val context = androidx.test.core.app.ApplicationProvider.getApplicationContext<android.content.Context>()
        val activity = org.robolectric.Robolectric.buildActivity(StudyDisclosureActivity::class.java,
            StudyDisclosureActivity.intent(context, preview())).setup().get()
        val accept = activity.findViewById<com.google.android.material.button.MaterialButton>(R.id.acceptStudyDisclosureButton)
        val attestation = activity.findViewById<com.google.android.material.checkbox.MaterialCheckBox>(R.id.studyEligibilityAttestation)
        org.junit.Assert.assertFalse(accept.isEnabled)
        accept.performClick()
        org.junit.Assert.assertFalse(activity.isFinishing)
        attestation.isChecked = true
        assertTrue(accept.isEnabled)
        accept.performClick()
        assertTrue(activity.isFinishing)
        assertEquals(android.app.Activity.RESULT_OK, org.robolectric.Shadows.shadowOf(activity).resultCode)
    }
}
