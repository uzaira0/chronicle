package com.openlattice.chronicle

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.button.MaterialButton
import com.openlattice.chronicle.api.EnrollmentPreviewResponse
import com.openlattice.chronicle.collection.CollectionModuleId
import com.openlattice.chronicle.collection.state.CollectionStateMachine
import java.net.URI

private val NON_CHOICE_MODULE_DISCLOSURES: Map<CollectionModuleId, String> = mapOf(
    CollectionModuleId.UPLOAD_TELEMETRY to
        "Upload delivery diagnostics — Chronicle keeps local queue counts and upload success, " +
        "failure, retry, and scheduling status. This diagnostic view excludes app content, " +
        "participant labels, server addresses, and credentials.",
    CollectionModuleId.SENSOR_AVAILABILITY to
        "Sensor availability — Chronicle tells this study which study-requested modeled sensors " +
        "this device reports as available or unavailable. This report contains no sensor measurements.",
    CollectionModuleId.QUESTIONNAIRE to
        "Questionnaire reminders — Chronicle can schedule study reminders and open this study's " +
        "public HTTPS questionnaire. Questionnaire responses are handled by that study page.",
)

internal fun nonChoiceModuleDisclosureLines(
    preview: EnrollmentPreviewResponse,
    disclosure: (CollectionModuleId) -> String? = NON_CHOICE_MODULE_DISCLOSURES::get,
): List<String> {
    val enabledNonChoiceModules = preview.manifest.collectionSettings.effectiveEnabledModuleIds() -
        CollectionStateMachine.ACK_GATED_MODULES
    return enabledNonChoiceModules.map { moduleId ->
        requireNotNull(disclosure(moduleId)) {
            "Enabled Android module '${moduleId.id}' has no study-level participant disclosure"
        }
    }
}

/** Resource id of a non-choice module's disclosure line, or null when the module has none. */
private fun nonChoiceModuleDisclosureRes(moduleId: CollectionModuleId): Int? = when (moduleId) {
    CollectionModuleId.UPLOAD_TELEMETRY -> R.string.disclosure_function_upload_telemetry
    CollectionModuleId.SENSOR_AVAILABILITY -> R.string.disclosure_function_sensor_availability
    CollectionModuleId.QUESTIONNAIRE -> R.string.disclosure_function_questionnaire
    else -> null
}

data class StudyDisclosureCopy(
    val title: String,
    val body: String,
    val privacyPolicyUrl: String,
    val consentDocumentUrl: String?,
) {
    /** Section headings of the disclosure body. Defaults are the tested English; the activity passes resources. */
    data class Labels(
        val responsibleInstitution: (String) -> String = { "Responsible institution: $it" },
        val serverOperator: (String) -> String = { "Server operator: $it" },
        val researchContact: (String) -> String = { "Research contact: $it" },
        val dataDestination: (String) -> String = { "Data destination: $it" },
        val purpose: String = "Purpose",
        val expectedDuration: String = "Expected duration",
        val whatHappens: String = "What happens",
        val enabledFunctions: String = "Enabled study functions covered by this agreement",
        val enabledFunctionsBody: String =
            "These enabled functions are not separate choices in the next screen. " +
                "Any research records they create are sent only to the study server named above.",
        val risks: String = "Foreseeable risks",
        val benefits: String = "Expected benefits",
        val useAndSharing: String = "Use and sharing",
        val retention: String = "Retention and deletion",
        val version: (String) -> String = { "Disclosure version: $it" },
        val nonChoiceDisclosure: (CollectionModuleId) -> String? = NON_CHOICE_MODULE_DISCLOSURES::get,
    ) {
        companion object {
            fun fromResources(context: Context) = Labels(
                responsibleInstitution = { context.getString(R.string.disclosure_responsible_institution, it) },
                serverOperator = { context.getString(R.string.disclosure_server_operator, it) },
                researchContact = { context.getString(R.string.disclosure_research_contact, it) },
                dataDestination = { context.getString(R.string.disclosure_data_destination, it) },
                purpose = context.getString(R.string.disclosure_purpose),
                expectedDuration = context.getString(R.string.disclosure_expected_duration),
                whatHappens = context.getString(R.string.disclosure_what_happens),
                enabledFunctions = context.getString(R.string.disclosure_enabled_functions),
                enabledFunctionsBody = context.getString(R.string.disclosure_enabled_functions_body),
                risks = context.getString(R.string.disclosure_risks),
                benefits = context.getString(R.string.disclosure_benefits),
                useAndSharing = context.getString(R.string.disclosure_use_sharing),
                retention = context.getString(R.string.disclosure_retention),
                version = { context.getString(R.string.disclosure_version, it) },
                nonChoiceDisclosure = { moduleId ->
                    nonChoiceModuleDisclosureRes(moduleId)?.let(context::getString)
                },
            )
        }
    }

    companion object {
        fun from(preview: EnrollmentPreviewResponse, labels: Labels = Labels()): StudyDisclosureCopy {
            val manifest = preview.manifest
            val policy = manifest.participantPolicy
            return StudyDisclosureCopy(
                title = manifest.studyTitle,
                body = buildString {
                    appendLine(labels.responsibleInstitution(policy.responsibleInstitution))
                    appendLine(labels.serverOperator(policy.serverOperator))
                    appendLine(labels.researchContact(policy.researchContact))
                    appendLine(labels.dataDestination(URI(manifest.serverOrigin).host))
                    appendLine()
                    appendLine(labels.purpose)
                    appendLine(policy.purpose)
                    appendLine()
                    appendLine(labels.expectedDuration)
                    appendLine(policy.expectedDuration)
                    appendLine()
                    appendLine(labels.whatHappens)
                    appendLine(policy.procedures)
                    appendLine()
                    val nonChoiceDisclosures =
                        nonChoiceModuleDisclosureLines(preview, labels.nonChoiceDisclosure)
                    if (nonChoiceDisclosures.isNotEmpty()) {
                        appendLine(labels.enabledFunctions)
                        appendLine(labels.enabledFunctionsBody)
                        nonChoiceDisclosures.forEach { disclosure -> appendLine("• $disclosure") }
                        appendLine()
                    }
                    appendLine(labels.risks)
                    appendLine(policy.foreseeableRisks)
                    appendLine()
                    appendLine(labels.benefits)
                    appendLine(policy.expectedBenefits)
                    appendLine()
                    appendLine(labels.useAndSharing)
                    appendLine(policy.dataUseAndSharing)
                    appendLine()
                    appendLine(labels.retention)
                    appendLine(policy.retentionAndDeletion)
                    appendLine()
                    append(labels.version(policy.version))
                },
                privacyPolicyUrl = policy.privacyPolicyUrl,
                consentDocumentUrl = policy.consentDocumentUrl,
            )
        }
    }
}

/** Prominent, affirmative study disclosure shown before any module-specific choice or enrollment. */
class StudyDisclosureActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_study_disclosure)

        val title = intent.getStringExtra(EXTRA_TITLE)
        val body = intent.getStringExtra(EXTRA_BODY)
        val privacyUrl = intent.getStringExtra(EXTRA_PRIVACY_URL)
        if (title.isNullOrBlank() || body.isNullOrBlank() || privacyUrl.isNullOrBlank()) {
            setResult(RESULT_CANCELED)
            finish()
            return
        }

        findViewById<TextView>(R.id.studyDisclosureTitle).text = title
        findViewById<TextView>(R.id.studyDisclosureBody).text = body
        findViewById<MaterialButton>(R.id.studyPrivacyButton).setOnClickListener { openHttps(privacyUrl) }

        val consentUrl = intent.getStringExtra(EXTRA_CONSENT_URL)
        findViewById<MaterialButton>(R.id.studyConsentDocumentButton).apply {
            visibility = if (consentUrl == null) View.GONE else View.VISIBLE
            setOnClickListener { consentUrl?.let(::openHttps) }
        }
        findViewById<MaterialButton>(R.id.acceptStudyDisclosureButton).setOnClickListener {
            setResult(RESULT_OK)
            finish()
        }
        findViewById<MaterialButton>(R.id.declineStudyDisclosureButton).setOnClickListener {
            setResult(RESULT_CANCELED)
            finish()
        }
    }

    private fun openHttps(url: String) {
        val uri = Uri.parse(url)
        if (uri.scheme == "https") startActivity(Intent(Intent.ACTION_VIEW, uri))
    }

    companion object {
        private const val EXTRA_TITLE = "study_disclosure_title"
        private const val EXTRA_BODY = "study_disclosure_body"
        private const val EXTRA_PRIVACY_URL = "study_privacy_url"
        private const val EXTRA_CONSENT_URL = "study_consent_url"

        fun intent(context: Context, preview: EnrollmentPreviewResponse): Intent {
            val copy = StudyDisclosureCopy.from(preview, StudyDisclosureCopy.Labels.fromResources(context))
            return Intent(context, StudyDisclosureActivity::class.java).apply {
                putExtra(EXTRA_TITLE, copy.title)
                putExtra(EXTRA_BODY, copy.body)
                putExtra(EXTRA_PRIVACY_URL, copy.privacyPolicyUrl)
                copy.consentDocumentUrl?.let { putExtra(EXTRA_CONSENT_URL, it) }
            }
        }
    }
}
