package com.openlattice.chronicle.collection.state

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.TextView
import androidx.activity.OnBackPressedCallback
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.ViewModel
import androidx.lifecycle.SavedStateHandle
import com.google.android.material.button.MaterialButton
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.openlattice.chronicle.R
import com.openlattice.chronicle.collection.CollectionModuleId
import com.openlattice.chronicle.collection.HealthConnectRecordType
import android.view.accessibility.AccessibilityEvent
import android.widget.ScrollView

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

    /** Only module ids and the current step are saved; no enrollment credential is stored here. */
    internal class Progress(private val state: SavedStateHandle) : ViewModel() {
        val accepted get() = ids(EXTRA_ACCEPTED_IDS)
        val declined get() = ids(EXTRA_DECLINED_IDS)
        var current: Int
            get() = state.get<Int>(CURRENT_STEP) ?: 0
            set(value) { state[CURRENT_STEP] = value }

        fun decide(moduleId: CollectionModuleId, allow: Boolean?) {
            update(EXTRA_ACCEPTED_IDS, moduleId, allow == true)
            update(EXTRA_DECLINED_IDS, moduleId, allow == false)
        }

        private fun ids(key: String): Set<CollectionModuleId> =
            state.get<ArrayList<String>>(key).orEmpty().mapNotNull { CollectionModuleId.fromIdOrNull(it) }.toSet()

        private fun update(key: String, moduleId: CollectionModuleId, selected: Boolean) {
            val values = ids(key).toMutableSet()
            if (selected) values.add(moduleId) else values.remove(moduleId)
            state[key] = ArrayList(values.map { it.id })
        }

        private companion object { const val CURRENT_STEP = "orientation_current_step" }
    }
    private val progress: Progress by viewModels()
    private val accepted get() = progress.accepted
    private val declined get() = progress.declined
    private var current: Int
        get() = progress.current
        set(value) { progress.current = value }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_collection_orientation)
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
        val template = CollectionConsentCopy.consentTemplate(
            this,
            step.moduleId,
            plan.healthConnectRecordTypes,
        )

        // Each fact once: "1 of 2 · Optional" above the data type's name, one line on what declining
        // means just above the buttons, and the collects / never-collects lists between them.
        val requirement = getString(if (step.required) R.string.orientation_required else R.string.orientation_optional)
        findViewById<TextView>(R.id.orientationStep).text =
            getString(R.string.orientation_step, current + 1, steps.size, requirement)
        findViewById<TextView>(R.id.orientationTitle).text = template.label
        findViewById<TextView>(R.id.orientationRequirementDetail).setText(
            if (step.required) R.string.orientation_required_detail else R.string.orientation_optional_detail,
        )
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
        findViewById<TextView>(R.id.orientationTitle).apply {
            isFocusableInTouchMode = true
            requestFocus()
            sendAccessibilityEvent(AccessibilityEvent.TYPE_VIEW_FOCUSED)
        }
        findViewById<ScrollView>(R.id.orientationScrollView).scrollTo(0, 0)
    }

    private fun onAllow() {
        val moduleId = steps[current].moduleId
        progress.decide(moduleId, true)
        advance()
    }

    private fun onDecline() {
        val step = steps[current]
        val label = CollectionConsentCopy.label(this, step.moduleId)
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
                    progress.decide(step.moduleId, false)
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
        progress.decide(moduleId, null)
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
