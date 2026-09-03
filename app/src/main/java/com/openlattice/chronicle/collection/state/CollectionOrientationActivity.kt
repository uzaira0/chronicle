package com.openlattice.chronicle.collection.state

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.TextView
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.button.MaterialButton
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.openlattice.chronicle.R
import com.openlattice.chronicle.collection.CollectionModuleId
import com.openlattice.chronicle.collection.HealthConnectRecordType
import com.openlattice.chronicle.padViewForSystemBars

/**
 * The enrollment orientation wizard (per-module consent design §6). One screen per
 * study-enabled consent-gated module, shown **required-first then optional**. The
 * participant Allows or declines each module, learning what it does and does NOT collect.
 *
 *  - Declining a **required** module → "you can't take part without this" confirm → if still
 *    declined, the wizard returns [RESULT_CANCELED] so enrollment is aborted (nothing enrolled).
 *  - Declining an **optional** module → mistap confirm → proceeds (recorded as declined =
 *    not collected).
 *
 * On completing every step it returns [Activity.RESULT_OK] with the accepted + declined module
 * id sets; the caller ([com.openlattice.chronicle.Enrollment]) then performs the server enroll
 * (consent-before-enroll). This activity itself NEVER enrolls or persists collection state.
 *
 * Copy is app-canonical ([CollectionConsentCopy]); final consent wording remains the
 * study/IRB's responsibility.
 */
class CollectionOrientationActivity : AppCompatActivity() {

    private data class Step(val moduleId: CollectionModuleId, val required: Boolean)

    private lateinit var plan: ConsentPlan
    private lateinit var steps: List<Step>
    private val accepted = linkedSetOf<CollectionModuleId>()
    private val declined = linkedSetOf<CollectionModuleId>()
    private var current = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_collection_orientation)
        padViewForSystemBars(R.id.orientationActions)
        supportActionBar?.setTitle(R.string.orientation_action_title)

        val required = intent.idList(EXTRA_REQUIRED_IDS)
        val optional = intent.idList(EXTRA_OPTIONAL_IDS)
        val healthConnectRecordTypes = intent.healthConnectRecordTypes()
        if (
            CollectionModuleId.HEALTH_CONNECT in (required + optional) &&
            healthConnectRecordTypes.isEmpty()
        ) {
            // Fail closed if an internal caller or a restored/tampered intent omitted the exact
            // study-approved scope. Never present generic health consent as if it were sufficient.
            abort()
            return
        }
        plan = ConsentPlan(
            required = required,
            optional = optional,
            healthConnectRecordTypes = healthConnectRecordTypes,
        )
        steps = plan.required.map { Step(it, required = true) } +
            plan.optional.map { Step(it, required = false) }
        if (steps.isEmpty()) {
            // Nothing to consent to — settle immediately (the caller skips the wizard for an
            // empty plan, but guard against a direct launch with no modules).
            finishWithResult()
            return
        }

        findViewById<MaterialButton>(R.id.orientationAccept).setOnClickListener { onAllow() }
        findViewById<MaterialButton>(R.id.orientationDecline).setOnClickListener { onDecline() }

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() = goBack()
        })

        bindStep()
    }

    private fun bindStep() {
        val step = steps[current]
        val template = CollectionConsentCopy.localizedConsentTemplate(
            this,
            step.moduleId,
            plan.healthConnectRecordTypes,
        )

        val requirement = StepRequirementCopy.forRequired(step.required, this)
        val planCopy = ConsentPlanCopy.fromResources(this)
        findViewById<TextView>(R.id.orientationStep).text =
            getString(R.string.orientation_step, current + 1, steps.size, requirement.displayLabel, template.label)
        findViewById<TextView>(R.id.orientationBadge).text = requirement.badge
        findViewById<TextView>(R.id.orientationRequirementDetail).text = requirement.detail
        findViewById<TextView>(R.id.orientationRequirementSummaryHeader).text = plan.requirementPlanHeader(planCopy)
        findViewById<TextView>(R.id.orientationRequirementSummary).text =
            plan.requirementSummaryLines(currentModule = step.moduleId, copy = planCopy).joinToString("\n")
        findViewById<TextView>(R.id.orientationTitle).text = getString(R.string.orientation_title_format, template.label, requirement.displayLabel)
        findViewById<TextView>(R.id.orientationCollects).text = bullets(template.whatItCollects)
        findViewById<TextView>(R.id.orientationNotCollect).text = bullets(template.whatItDoesNotCollect)
        findViewById<TextView>(R.id.orientationCaveat).apply {
            if (template.caveats.isEmpty()) {
                visibility = View.GONE
            } else {
                visibility = View.VISIBLE
                text = template.caveats.joinToString("\n\n") { "⚠  $it" }
            }
        }
        findViewById<TextView>(R.id.orientationPrivacy).text = getString(R.string.orientation_privacy, template.privacyClass)

        // Required modules can't proceed declined, so the decline button reads as a study-exit.
        findViewById<MaterialButton>(R.id.orientationAccept).text = requirement.acceptLabel
        findViewById<MaterialButton>(R.id.orientationDecline).text = requirement.declineLabel
    }

    private fun onAllow() {
        val moduleId = steps[current].moduleId
        declined.remove(moduleId)
        accepted.add(moduleId)
        advance()
    }

    private fun onDecline() {
        val step = steps[current]
        val label = CollectionConsentCopy.localizedLabel(this, step.moduleId)
        if (step.required) {
            MaterialAlertDialogBuilder(this)
                .setTitle(R.string.orientation_required_title)
                .setMessage(getString(R.string.orientation_required_body, label))
                .setPositiveButton(R.string.orientation_stop_enrolling) { _, _ -> abort() }
                .setNegativeButton(R.string.orientation_go_back, null)
                .show()
        } else {
            MaterialAlertDialogBuilder(this)
                .setTitle(getString(R.string.orientation_decline_title, label))
                .setMessage(R.string.orientation_decline_body)
                .setPositiveButton(R.string.dont_allow) { _, _ ->
                    accepted.remove(step.moduleId)
                    declined.add(step.moduleId)
                    advance()
                }
                .setNegativeButton(android.R.string.cancel, null)
                .show()
        }
    }

    private fun advance() {
        current += 1
        if (current >= steps.size) finishWithResult() else bindStep()
    }

    private fun goBack() {
        if (current == 0) {
            abort()
            return
        }
        current -= 1
        // Clear the prior step's decision so the participant can freely re-decide it.
        val moduleId = steps[current].moduleId
        accepted.remove(moduleId)
        declined.remove(moduleId)
        bindStep()
    }

    private fun finishWithResult() {
        setResult(
            Activity.RESULT_OK,
            Intent()
                .putStringArrayListExtra(EXTRA_ACCEPTED_IDS, ArrayList(accepted.map { it.id }))
                .putStringArrayListExtra(EXTRA_DECLINED_IDS, ArrayList(declined.map { it.id })),
        )
        finish()
    }

    private fun abort() {
        setResult(Activity.RESULT_CANCELED)
        finish()
    }

    private fun bullets(items: List<String>): String =
        if (items.isEmpty()) getString(R.string.orientation_none) else items.joinToString("\n") { "•  $it" }

    private fun Intent.idList(key: String): List<CollectionModuleId> =
        getStringArrayListExtra(key).orEmpty().mapNotNull { CollectionModuleId.fromIdOrNull(it) }

    private fun Intent.healthConnectRecordTypes(): Set<HealthConnectRecordType> =
        getStringArrayListExtra(EXTRA_HEALTH_CONNECT_RECORD_TYPES).orEmpty()
            .mapNotNullTo(linkedSetOf()) { id ->
                runCatching { HealthConnectRecordType.fromId(id) }.getOrNull()
            }

    companion object {
        private const val EXTRA_REQUIRED_IDS = "extra_required_ids"
        private const val EXTRA_OPTIONAL_IDS = "extra_optional_ids"
        private const val EXTRA_HEALTH_CONNECT_RECORD_TYPES = "extra_health_connect_record_types"
        const val EXTRA_ACCEPTED_IDS = "extra_accepted_ids"
        const val EXTRA_DECLINED_IDS = "extra_declined_ids"

        /** Builds the launch intent for [plan] (required-first ordering preserved). */
        fun intent(context: Context, plan: ConsentPlan): Intent =
            Intent(context, CollectionOrientationActivity::class.java)
                .putStringArrayListExtra(EXTRA_REQUIRED_IDS, ArrayList(plan.required.map { it.id }))
                .putStringArrayListExtra(EXTRA_OPTIONAL_IDS, ArrayList(plan.optional.map { it.id }))
                .putStringArrayListExtra(
                    EXTRA_HEALTH_CONNECT_RECORD_TYPES,
                    ArrayList(
                        HealthConnectRecordType.entries
                            .filter(plan.healthConnectRecordTypes::contains)
                            .map { it.id },
                    ),
                )

        /** Parses the accepted module-id set from a successful result intent. */
        fun acceptedFrom(data: Intent?): Set<CollectionModuleId> =
            data?.getStringArrayListExtra(EXTRA_ACCEPTED_IDS).orEmpty()
                .mapNotNull { CollectionModuleId.fromIdOrNull(it) }.toSet()

        /** Parses the declined module-id set from a successful result intent. */
        fun declinedFrom(data: Intent?): Set<CollectionModuleId> =
            data?.getStringArrayListExtra(EXTRA_DECLINED_IDS).orEmpty()
                .mapNotNull { CollectionModuleId.fromIdOrNull(it) }.toSet()
    }
}

data class StepRequirementCopy(
    val displayLabel: String,
    val shortLabel: String,
    val badge: String,
    val detail: String,
    val acceptLabel: String,
    val declineLabel: String,
) {
    companion object {
        /** Resource-backed copy for the live screen; the English table below is the tested baseline. */
        fun forRequired(required: Boolean, context: Context): StepRequirementCopy {
            val label = context.getString(
                if (required) R.string.orientation_required_label else R.string.orientation_optional_label,
            )
            return StepRequirementCopy(
                displayLabel = label,
                shortLabel = label,
                badge = label,
                detail = context.getString(
                    if (required) R.string.orientation_required_detail else R.string.orientation_optional_detail,
                ),
                acceptLabel = context.getString(
                    if (required) R.string.orientation_allow_required else R.string.orientation_allow_optional,
                ),
                declineLabel = context.getString(R.string.dont_allow),
            )
        }

        fun forRequired(required: Boolean): StepRequirementCopy =
            if (required) {
                StepRequirementCopy(
                    displayLabel = "Required by source study",
                    shortLabel = "Required by source study",
                    badge = "Required by source study",
                    detail = "The source study marks this data type as required. Enrollment can continue only if you allow it.",
                    acceptLabel = "Allow required data",
                    declineLabel = "Don't allow",
                )
            } else {
                StepRequirementCopy(
                    displayLabel = "Optional for source study",
                    shortLabel = "Optional for source study",
                    badge = "Optional for source study",
                    detail = "The source study marks this data type as optional. You can skip it and still enroll.",
                    acceptLabel = "Allow optional data",
                    declineLabel = "Don't allow",
                )
            }
    }
}

/**
 * Presentation hooks for the step plan. The default is the tested English rendering; the
 * orientation screen supplies a resource-backed instance so the plan follows the device locale.
 */
class ConsentPlanCopy(
    val moduleLabel: (CollectionModuleId) -> String,
    val requirementLabel: (Boolean) -> String,
    val stepLine: (index: Int, requirement: String, label: String, current: Boolean) -> String,
    val planHeader: (requiredCount: Int, optionalCount: Int) -> String,
) {
    companion object {
        val ENGLISH = ConsentPlanCopy(
            moduleLabel = { CollectionConsentCopy.template(it).label },
            requirementLabel = { StepRequirementCopy.forRequired(it).displayLabel },
            stepLine = { index, requirement, label, current ->
                "Step ${index + 1}: [$requirement] $label${if (current) " (current)" else ""}"
            },
            planHeader = ::sourceStudyRequirementPlanHeader,
        )

        fun fromResources(context: Context) = ConsentPlanCopy(
            moduleLabel = { CollectionConsentCopy.localizedLabel(context, it) },
            requirementLabel = { StepRequirementCopy.forRequired(it, context).displayLabel },
            stepLine = { index, requirement, label, current ->
                context.getString(
                    R.string.orientation_plan_line,
                    index + 1,
                    requirement,
                    label,
                    if (current) context.getString(R.string.orientation_plan_current) else "",
                )
            },
            planHeader = { requiredCount, optionalCount ->
                context.getString(
                    R.string.orientation_plan_header,
                    context.resources.getQuantityString(R.plurals.orientation_required_steps, requiredCount, requiredCount),
                    context.resources.getQuantityString(R.plurals.orientation_optional_steps, optionalCount, optionalCount),
                )
            },
        )
    }
}

fun ConsentPlan.requirementSummaryLines(
    currentModule: CollectionModuleId? = null,
    copy: ConsentPlanCopy = ConsentPlanCopy.ENGLISH,
): List<String> {
    val requiredModules = required.toSet()
    return orderedModules.mapIndexed { index, moduleId ->
        val label = copy.moduleLabel(moduleId)
        val requirement = copy.requirementLabel(requiredModules.contains(moduleId))
        copy.stepLine(index, requirement, label, moduleId == currentModule)
    }
}

fun ConsentPlan.requirementPlanHeader(copy: ConsentPlanCopy = ConsentPlanCopy.ENGLISH): String =
    copy.planHeader(required.size, optional.size)

private fun sourceStudyRequirementPlanHeader(requiredCount: Int, optionalCount: Int): String {
    fun plural(count: Int, singular: String): String = "$count $singular${if (count == 1) "" else "s"}"
    return "Source study step plan: ${plural(requiredCount, "required step")}, " +
        "${plural(optionalCount, "optional step")}"
}
