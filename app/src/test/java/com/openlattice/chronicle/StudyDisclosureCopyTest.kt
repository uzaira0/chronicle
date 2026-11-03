package com.openlattice.chronicle

import android.content.Context
import android.content.res.Configuration
import androidx.test.core.app.ApplicationProvider
import com.openlattice.chronicle.api.EnrollmentPreviewResponse
import com.openlattice.chronicle.collection.CollectionModuleId
import com.openlattice.chronicle.collection.CollectionModuleSetting
import com.openlattice.chronicle.serialization.ChronicleJson
import com.openlattice.chronicle.study.WithdrawalMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Locale

@org.junit.runner.RunWith(org.robolectric.RobolectricTestRunner::class)
class StudyDisclosureCopyTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    /** The resource-backed labels StudyDisclosureActivity shows: the text participants read. */
    private val labels get() = StudyDisclosureCopy.Labels.fromResources(context)

    @Test fun withdrawalCopyExplainsThatTheStudyPolicyControlsPriorData() {
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

        val copy = StudyDisclosureCopy.from(preview, labels)
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

        val body = StudyDisclosureCopy.from(preview, labels).body

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
    fun disclosureDoesNotClaimAReservedModule() {
        val baseline = preview()
        val withReservedModule = baseline.copy(
            manifest = baseline.manifest.copy(
                collectionSettings = baseline.manifest.collectionSettings.copy(
                    modules = baseline.manifest.collectionSettings.modules +
                        (CollectionModuleId.GAZE_TRACKING to CollectionModuleSetting(enabled = true)),
                ),
            ),
        )

        assertEquals(
            nonChoiceModuleDisclosureLines(baseline, labels.nonChoiceDisclosure),
            nonChoiceModuleDisclosureLines(withReservedModule, labels.nonChoiceDisclosure),
        )
    }

    @Test fun enrollmentPreviewDisclosesWithdrawalModeBeforeAcceptance() {
        val baseline = preview()
        for (mode in WithdrawalMode.values()) {
            val disclosure = StudyDisclosureCopy.from(baseline.copy(manifest = baseline.manifest.copy(withdrawalMode = mode)), labels)
            val erases = mode == WithdrawalMode.ERASE
            assertTrue(disclosure.body.contains(if (erases) labels.withdrawalPolicy else labels.withdrawalKeeps))
            org.junit.Assert.assertFalse(disclosure.body.contains(if (erases) labels.withdrawalKeeps else labels.withdrawalPolicy))
        }
    }

    /**
     * Sensor availability checks every sensor type Chronicle models and uploads the device's list, whether or not the
     * study enables a sensor module: the line says so in each locale, not "study-requested".
     */
    @Test fun sensorAvailabilityLineDescribesEveryModeledSensorInEachLocale() {
        val english = nonChoiceModuleDisclosureLines(preview(), labels.nonChoiceDisclosure).single { it.startsWith("Sensor availability") }
        assertTrue(english, english.contains("which of all the sensors Chronicle models this device reports as available or unavailable"))
        val spanish = context.createConfigurationContext(Configuration(context.resources.configuration).apply {
            setLocale(Locale.forLanguageTag("es"))
        }).getString(R.string.disclosure_function_sensor_availability)
        assertTrue(spanish, spanish.contains("cuáles de todos los sensores modelados por Chronicle están disponibles o no"))
        listOf(english, spanish).forEach { line ->
            org.junit.Assert.assertFalse(line, line.contains("study-requested") || line.contains("solicitados por el estudio"))
            assertTrue(line, line.contains("no sensor measurements") || line.contains("no contiene mediciones de sensores"))
        }
    }

    private fun preview(): EnrollmentPreviewResponse {
        val previewJson = javaClass.getResource("/enrollment-preview.json")?.readText()
            ?: error("Missing enrollment-preview.json")
        return ChronicleJson.moshi.adapter(EnrollmentPreviewResponse::class.java)
            .fromJson(previewJson)!!
    }

    @Test fun enrollmentDisclosureRequiresTheAdultOrGuardianAttestation() {
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
