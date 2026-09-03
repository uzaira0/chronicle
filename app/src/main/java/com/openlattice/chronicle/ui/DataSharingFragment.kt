package com.openlattice.chronicle.ui

import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import android.util.Log
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.appcompat.app.AlertDialog
import com.google.android.material.switchmaterial.SwitchMaterial
import com.openlattice.chronicle.BuildConfig
import com.openlattice.chronicle.R
import com.openlattice.chronicle.android.AndroidSensorType
import com.openlattice.chronicle.collection.CollectionModuleId
import com.openlattice.chronicle.collection.HealthConnectRecordType
import com.openlattice.chronicle.collection.capability.CollectionCapability
import com.openlattice.chronicle.collection.capability.CapabilityEnvironment
import com.openlattice.chronicle.collection.capability.CollectionCapabilityResolver
import com.openlattice.chronicle.collection.ConsentTrigger
import com.openlattice.chronicle.collection.DistributionRestrictedRuntime
import com.openlattice.chronicle.collection.SensorCollectionModules
import com.openlattice.chronicle.collection.device.HealthConnectScopeStore
import com.openlattice.chronicle.collection.permissions.ModulePermissions
import com.openlattice.chronicle.collection.permissions.PermissionKind
import com.openlattice.chronicle.collection.state.CollectionConsentCopy
import com.openlattice.chronicle.collection.state.localizedLabel
import com.openlattice.chronicle.collection.state.localizedConsentTemplate
import com.openlattice.chronicle.collection.state.CollectionLoopCoordinator
import com.openlattice.chronicle.collection.state.CollectionStateMachine
import com.openlattice.chronicle.collection.state.CollectionModulePhase
import com.openlattice.chronicle.collection.state.CollectionModuleState
import com.openlattice.chronicle.padForSystemBars
import com.openlattice.chronicle.utils.DeviceSettingsNavigator
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * The "Data Sharing" tab (per-module consent design §7) — the persistent management surface
 * for consent-gated collection. Two sections:
 *  - **App & Device Usage**: the non-sensor gated modules (usage, device lifecycle, battery).
 *    Optional modules toggle freely (ACCEPT ↔ DECLINE, reported as a PARTICIPANT_TOGGLE);
 *    required modules are locked while enrolled; uninstall is the participant-facing withdrawal path that
 *    stops all collection and requests deletion. `user_identification` is **not** here: it is not a data-collection
 *    consent choice but a shared-device setting (Settings → Identify user), which routes
 *    notifications/surveys to the right person; it is controlled by the `identify_user`
 *    preference, independent of collection consent.
 *  - **Sensors**: the former Sensors tab, folded in as a section (local per-sensor toggles).
 */
class DataSharingFragment : Fragment(R.layout.fragment_data_sharing) {
    private var refreshJob: Job? = null

    /**
     * Missing-permission status for the currently ACTIVE modules, computed off the main thread (the
     * Health Connect grant query suspends). Drives the "Grant access" affordance and the
     * request-on-toggle path. Defaults to "nothing missing" until the first snapshot computes it.
     */
    private var permissionStatus = PermissionStatus(emptyList(), needHealthConnect = false)
    private var capabilities: Map<CollectionModuleId, CollectionCapability> = emptyMap()
    private var capabilityEnvironment: CapabilityEnvironment? = null

    /** Set when a permission request needs Health Connect after the runtime request resolves. */
    private var pendingHealthConnectRequest = false

    // Runtime (dangerous) permission request — e.g. ACTIVITY_RECOGNITION, needed by the
    // activity_recognition / sleep modules and the step-counter / significant-motion sensors. The
    // result is not gated; the refresh loop re-reads grants and updates the affordance. If a Health
    // Connect grant is also pending, it is launched once this resolves (system shows one screen at a time).
    private val runtimePermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) {
        if (BuildConfig.HAS_HEALTH_CONNECT && pendingHealthConnectRequest) {
            launchHealthConnect()
        }
    }

    // Health Connect grant flow (health_connect module). NOT the normal runtime dialog — this opens
    // Health Connect's own permission screen. The Play compiler folds this branch away, so the
    // no-op flavor compatibility type is not retained in the minimized artifact.
    private val healthConnectPermissionLauncher: ActivityResultLauncher<Set<String>>? =
        if (BuildConfig.HAS_HEALTH_CONNECT) {
            registerForActivityResult(
                DistributionRestrictedRuntime.healthPermissionContract(),
            ) { /* re-bind on resume reflects the new grants; nothing gated */ }
        } else {
            null
        }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        view.findViewById<View>(R.id.dataSharingContent).padForSystemBars()
    }

    override fun onResume() {
        super.onResume()
        refreshJob = viewLifecycleOwner.lifecycleScope.launch {
            val environment = withContext(Dispatchers.IO) {
                CollectionCapabilityResolver.snapshot(requireContext().applicationContext)
            }
            capabilityEnvironment = environment
            while (true) {
                val snapshot = DashboardDataRepository.load(requireContext())
                capabilities = computeCapabilities(snapshot.collectionModules, environment)
                permissionStatus = computePermissionStatus(snapshot.collectionModules, environment)
                view?.let { bindAll(it, snapshot) }
                delay(DASHBOARD_REFRESH_MS)
            }
        }
    }

    override fun onPause() {
        refreshJob?.cancel()
        refreshJob = null
        capabilityEnvironment = null
        super.onPause()
    }

    /** Rebinds every section from one snapshot (kept in one place so no surface goes stale). */
    private fun bindAll(view: View, snapshot: DashboardSnapshot) {
        bindAppUsage(view, snapshot)
        bindSensors(view, snapshot)
    }

    // ----- App & Device Usage section -----

    private fun bindAppUsage(view: View, snapshot: DashboardSnapshot) {
        val list = view.findViewById<LinearLayout>(R.id.appUsageModuleList)
        list.removeAllViews()
        val byId = snapshot.collectionModules.associateBy { it.moduleId }
        // A consent-gated module is inert until the OS grants its permission too — surface a
        // "Grant access" affordance at the top whenever an active module is still missing one
        // (ACTIVITY_RECOGNITION for activity/sleep/step-counter; the Health Connect grant for health;
        // notification access for notifications; the accessibility service for interaction).
        addPermissionAffordances(list)
        appUsageModulesToShow(byId).forEach { moduleId ->
            list.addView(moduleRow(moduleId, byId[moduleId]))
        }
        bindPausedBanner(view, snapshot.collectionModules)
    }

    /**
     * Off-main-thread snapshot of which permissions the currently ACTIVE modules still need. Runtime
     * grant checks are cheap but the Health Connect query suspends, so this runs on IO. A module is
     * only counted once it is ACTIVE (accepted + study-enabled) — we never prompt for data the
     * participant hasn't agreed to share.
     */
    private fun computePermissionStatus(
        states: List<CollectionModuleState>,
        environment: CapabilityEnvironment,
    ): PermissionStatus {
        val active = states.filter { it.phase == CollectionModulePhase.ACTIVE }.map { it.moduleId }
        val missingRuntime = ModulePermissions.runtimePermissionsFor(active, environment.sdkInt)
            .filterNot(environment.grantedRuntimePermissions::contains)
        val needUsageAccess = ModulePermissions.needsKind(active, PermissionKind.USAGE_ACCESS) &&
            !environment.usageAccessGranted
        val needHealthConnect = BuildConfig.HAS_HEALTH_CONNECT &&
            ModulePermissions.needsHealthConnect(active) &&
            environment.healthConnectAvailable && !environment.healthConnectGranted
        val notificationAccessModules = active
            .filter { ModulePermissions.needsKind(listOf(it), PermissionKind.NOTIFICATION_LISTENER) }
            .toSet()
        val needNotificationListener = notificationAccessModules.isNotEmpty() &&
            !environment.notificationListenerEnabled
        val needAccessibility = BuildConfig.ALLOW_RESTRICTED_RESEARCH_PERMISSIONS &&
            ModulePermissions.needsKind(active, PermissionKind.ACCESSIBILITY) &&
            !environment.accessibilityEnabled
        return PermissionStatus(
            missingRuntime,
            needHealthConnect,
            needUsageAccess,
            needNotificationListener,
            needAccessibility,
            notificationAccessModules,
        )
    }

    /**
     * Adds a "Grant access" affordance for each kind of permission an ACTIVE module is still missing.
     * The runtime + Health Connect grants share one row (they chain through one tap); the two
     * Settings-based special accesses (notification listener, accessibility) get their own rows
     * because each deep-links to a different system Settings screen and returns no result.
     * No rows are added when every active module already holds its permission.
     */
    private fun addPermissionAffordances(list: LinearLayout) {
        if (permissionStatus.needUsageAccess) {
            list.addView(
                affordanceRow(
                    getString(R.string.ds_usage_access_needed),
                    getString(R.string.ds_review_access),
                    getString(R.string.ds_review_access_cd),
                ) { confirmUsageAccessDisclosure() },
            )
        }
        if (permissionStatus.missingRuntime.isNotEmpty() || permissionStatus.needHealthConnect) {
            list.addView(
                affordanceRow(
                    getString(R.string.ds_permissions_needed),
                    getString(R.string.ds_grant_access),
                    getString(R.string.ds_grant_access_cd),
                ) { requestMissingPermissions() },
            )
        }
        if (BuildConfig.ALLOW_RESTRICTED_RESEARCH_PERMISSIONS && permissionStatus.needNotificationListener) {
            val disclosure = notificationAccessDisclosure(permissionStatus.notificationAccessModules, requireContext().copyResolver())
            list.addView(
                affordanceRow(
                    disclosure.affordanceMessage,
                    getString(R.string.ds_open_settings),
                    getString(R.string.ds_open_notification_settings_cd),
                ) { openNotificationListenerSettings() },
            )
        }
        if (BuildConfig.ALLOW_RESTRICTED_RESEARCH_PERMISSIONS && permissionStatus.needAccessibility) {
            list.addView(
                affordanceRow(
                    getString(R.string.ds_accessibility_needed),
                    getString(R.string.ds_open_settings),
                    getString(R.string.ds_open_accessibility_cd),
                ) { DistributionRestrictedRuntime.showInteractionAccessibilityDisclosure(this) },
            )
        }
    }

    /** A panel with a message and one action button — the shape every permission affordance uses. */
    private fun affordanceRow(message: String, buttonText: String, buttonCd: String, onClick: () -> Unit): View {
        val row = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.VERTICAL
            val pad = resources.getDimensionPixelSize(R.dimen.eq_space_4)
            setPadding(pad, pad, pad, pad)
            setBackgroundResource(R.drawable.bg_panel)
            val topMargin = resources.getDimensionPixelSize(R.dimen.eq_space_2)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            ).apply { setMargins(0, topMargin, 0, topMargin) }
        }
        row.addView(
            TextView(requireContext()).apply {
                text = message
                setTextColor(resources.getColor(R.color.chronicle_text_primary, null))
                textSize = 16f
            },
        )
        row.addView(
            Button(requireContext()).apply {
                text = buttonText
                minHeight = resources.getDimensionPixelSize(R.dimen.chronicle_touch_target)
                contentDescription = buttonCd
                setOnClickListener { onClick() }
            },
        )
        return row
    }

    /**
     * Requests the runtime / Health Connect permissions the active modules are still missing. The
     * system shows one permission UI at a time, so a Health Connect grant (if also needed) is
     * launched from the runtime request's callback; if only Health Connect is missing it launches
     * directly. The Settings-based special accesses are handled by their own affordance rows.
     */
    private fun requestMissingPermissions() {
        if (permissionStatus.needUsageAccess) {
            confirmUsageAccessDisclosure()
            return
        }
        val runtime = permissionStatus.missingRuntime
        pendingHealthConnectRequest = permissionStatus.needHealthConnect
        when {
            runtime.isNotEmpty() -> runtimePermissionLauncher.launch(runtime.toTypedArray())
            pendingHealthConnectRequest -> launchHealthConnect()
        }
    }

    private fun confirmUsageAccessDisclosure() {
        AlertDialog.Builder(requireContext())
            .setTitle(R.string.usage_access_disclosure_title)
            .setMessage(R.string.usage_access_disclosure_body)
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(R.string.continue_action) { _, _ ->
                DeviceSettingsNavigator.open(
                    requireContext(),
                    Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS),
                )
            }
            .show()
    }

    private fun launchHealthConnect() {
        pendingHealthConnectRequest = false
        if (!BuildConfig.HAS_HEALTH_CONNECT) return
        val configured = runCatching {
            HealthConnectScopeStore.of(requireContext()).read()
        }.getOrDefault(emptySet())
        if (configured.isEmpty()) {
            Toast.makeText(
                requireContext(),
                getString(R.string.ds_hc_none_configured),
                Toast.LENGTH_LONG,
            ).show()
            return
        }
        val recordTypeList = configured
            .sortedBy { it.ordinal }
            .joinToString(", ") { healthConnectRecordTypeLabel(it) }
        AlertDialog.Builder(requireContext())
            .setTitle(R.string.health_connect_disclosure_title)
            .setMessage(getString(R.string.health_connect_disclosure_body, recordTypeList))
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(R.string.continue_action) { _, _ ->
                // Health Connect can be uninstalled between the capability check and this tap.
                runCatching {
                    healthConnectPermissionLauncher?.launch(
                        DistributionRestrictedRuntime.healthPermissionsToRequest(requireContext(), configured),
                    )
                }.onFailure { error ->
                    Log.w(TAG, "Unable to open Health Connect permission screen", error)
                    Toast.makeText(
                        requireContext(),
                        getString(R.string.ds_hc_open_failed),
                        Toast.LENGTH_LONG,
                    ).show()
                }
            }
            .show()
    }

    private fun healthConnectRecordTypeLabel(type: HealthConnectRecordType): String = getString(
        when (type) {
            HealthConnectRecordType.STEPS -> R.string.hc_record_steps
            HealthConnectRecordType.DISTANCE -> R.string.hc_record_distance
            HealthConnectRecordType.HEART_RATE -> R.string.hc_record_heart_rate
            HealthConnectRecordType.TOTAL_CALORIES_BURNED -> R.string.hc_record_total_calories_burned
            HealthConnectRecordType.ACTIVE_CALORIES_BURNED -> R.string.hc_record_active_calories_burned
            HealthConnectRecordType.FLOORS_CLIMBED -> R.string.hc_record_floors_climbed
            HealthConnectRecordType.RESTING_HEART_RATE -> R.string.hc_record_resting_heart_rate
            HealthConnectRecordType.OXYGEN_SATURATION -> R.string.hc_record_oxygen_saturation
            HealthConnectRecordType.RESPIRATORY_RATE -> R.string.hc_record_respiratory_rate
            HealthConnectRecordType.SLEEP -> R.string.hc_record_sleep
            HealthConnectRecordType.EXERCISE -> R.string.hc_record_exercise
            HealthConnectRecordType.HEART_RATE_VARIABILITY -> R.string.hc_record_heart_rate_variability
            HealthConnectRecordType.BODY_TEMPERATURE -> R.string.hc_record_body_temperature
            HealthConnectRecordType.SKIN_TEMPERATURE -> R.string.hc_record_skin_temperature
        },
    )

    private fun openNotificationListenerSettings() {
        if (!BuildConfig.ALLOW_RESTRICTED_RESEARCH_PERMISSIONS) return
        val disclosure = notificationAccessDisclosure(permissionStatus.notificationAccessModules, requireContext().copyResolver())
        AlertDialog.Builder(requireContext())
            .setTitle(disclosure.title)
            .setMessage(disclosure.body)
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(R.string.continue_action) { _, _ ->
                DeviceSettingsNavigator.open(
                    requireContext(),
                    Intent("android.settings.ACTION_NOTIFICATION_LISTENER_SETTINGS"),
                )
            }
            .show()
    }

    /**
     * The non-sensor consent-gated modules to show, derived from
     * [CollectionStateMachine.ACK_GATED_MODULES] (NOT a hardcoded list — a stale hardcoded list
     * is exactly what hid the sensing-expansion modules). [APP_USAGE_BASE] always shows; every
     * other gated non-sensor module shows only when the study enables it (so unused data types add
     * no clutter). Ordered by [APP_USAGE_OPTIONAL_ORDER]; any future gated module not listed there
     * still appears (appended last) rather than silently vanishing.
     */
    private fun appUsageModulesToShow(
        byId: Map<CollectionModuleId, CollectionModuleState>,
    ): List<CollectionModuleId> {
        val sensors = SensorCollectionModules.sensorModuleIds.toSet()
        val optional = CollectionStateMachine.ACK_GATED_MODULES
            .filterNot { it in sensors || it in APP_USAGE_BASE }
            .filter { byId[it]?.serverEnabled == true }
            .sortedBy { APP_USAGE_OPTIONAL_ORDER.indexOf(it).let { i -> if (i < 0) Int.MAX_VALUE else i } }
        return APP_USAGE_BASE + optional
    }

    /**
     * Surfaces the study-required modules the participant has not accepted (per-module consent
     * design §5). Two cases:
     *  - **Grace window** (UNDECIDED): the study just required these; already-accepted modules
     *    keep collecting. Each row offers **Accept** (keep sharing) and **Decline**.
     *  - **Halted** (DECLINED): the participant declined a required module, so ALL collection
     *    is paused. The banner says so; each declined row offers **Accept** to resume.
     * Hidden entirely when no required module needs attention.
     */
    private fun bindPausedBanner(view: View, states: List<CollectionModuleState>) {
        val banner = view.findViewById<TextView>(R.id.dataSharingPausedBanner)
        val pendingList = view.findViewById<LinearLayout>(R.id.pendingRequiredList)
        val attention = states.filter { it.requiredButNotAccepted }
        val anyDeclined = attention.any { it.requiredAndDeclined }
        pendingList.removeAllViews()
        if (attention.isEmpty()) {
            banner.visibility = View.GONE
            pendingList.visibility = View.GONE
            return
        }
        banner.text = if (anyDeclined) {
            val names = moduleNames(attention.filter { it.requiredAndDeclined })
            getString(R.string.ds_paused_declined, names)
        } else {
            getString(R.string.ds_now_requires, moduleNames(attention))
        }
        banner.visibility = View.VISIBLE
        attention.forEach { pendingList.addView(attentionRow(it)) }
        pendingList.visibility = View.VISIBLE
    }

    private fun moduleNames(states: List<CollectionModuleState>): String =
        states.joinToString(", ") { CollectionConsentCopy.localizedLabel(requireContext(), it.moduleId) }

    /**
     * A required-module attention row in the consolidated list. A still-undecided module
     * (grace window) offers Accept + Decline; an already-declined module (halt active) offers
     * only Accept (to resume — it is already declined).
     */
    private fun attentionRow(state: CollectionModuleState): View {
        val moduleId = state.moduleId
        val moduleLabel = CollectionConsentCopy.localizedLabel(requireContext(), moduleId)
        val isDeclined = state.requiredAndDeclined
        val row = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            val pad = resources.getDimensionPixelSize(R.dimen.eq_space_4)
            setPadding(pad, pad, pad, pad)
            minimumHeight = resources.getDimensionPixelSize(R.dimen.chronicle_sensor_row_min_height)
            setBackgroundResource(R.drawable.bg_panel)
            val topMargin = resources.getDimensionPixelSize(R.dimen.eq_space_2)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            ).apply { setMargins(0, topMargin, 0, 0) }
        }
        val label = TextView(requireContext()).apply {
            text = getString(
                R.string.ds_module_row,
                moduleLabel,
                getString(if (isDeclined) R.string.ds_declined_paused else R.string.ds_required_by_study),
            )
            setTextColor(resources.getColor(R.color.chronicle_text_primary, null))
            textSize = 16f
            minLines = 2
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        row.addView(label)
        if (!isDeclined) {
            val decline = Button(requireContext()).apply {
                text = getString(R.string.ds_decline)
                minHeight = resources.getDimensionPixelSize(R.dimen.chronicle_touch_target)
                contentDescription = getString(R.string.ds_decline_cd, moduleLabel)
                setOnClickListener { onRejectRequired(moduleId) }
            }
            row.addView(decline)
        }
        val accept = Button(requireContext()).apply {
            text = getString(R.string.ds_accept)
            minHeight = resources.getDimensionPixelSize(R.dimen.chronicle_touch_target)
            contentDescription = getString(R.string.ds_accept_cd, moduleLabel)
            setOnClickListener { onAcceptRequired(moduleId) }
        }
        row.addView(accept)
        return row
    }

    private fun onAcceptRequired(moduleId: CollectionModuleId) {
        if (BuildConfig.HAS_HEALTH_CONNECT && moduleId == CollectionModuleId.HEALTH_CONNECT) {
            confirmExactHealthConnectScope(
                onConfirmed = { reviewedScope ->
                    applyRequiredDecision(
                        accepted = setOf(moduleId),
                        declined = emptySet(),
                        reviewedHealthConnectScope = reviewedScope,
                    )
                },
            )
        } else {
            applyRequiredDecision(accepted = setOf(moduleId), declined = emptySet())
        }
    }

    private fun onRejectRequired(moduleId: CollectionModuleId) =
        applyRequiredDecision(accepted = emptySet(), declined = setOf(moduleId))

    private fun applyRequiredDecision(
        accepted: Set<CollectionModuleId>,
        declined: Set<CollectionModuleId>,
        reviewedHealthConnectScope: Set<HealthConnectRecordType>? = null,
    ) {
        viewLifecycleOwner.lifecycleScope.launch {
            val locallyApplied = withContext(Dispatchers.IO) {
                // A re-decision driven by a study setting change (per-module consent design
                // §3.3 — SETTINGS_CHANGE). Accepting resumes collection; declining trips the
                // global halt. Either way the gate re-evaluates, so refresh both sections.
                val coordinator = CollectionLoopCoordinator(requireContext().applicationContext)
                if (reviewedHealthConnectScope != null) {
                    coordinator.applyReviewedHealthConnectAcceptance(
                        reviewedHealthConnectScope,
                        ConsentTrigger.SETTINGS_CHANGE,
                    )
                } else {
                    coordinator.applyDecisions(
                        accepted = accepted,
                        declined = declined,
                        trigger = ConsentTrigger.SETTINGS_CHANGE,
                    )
                    true
                }
            }
            val snapshot = DashboardDataRepository.load(requireContext())
            val environment = loadCapabilityEnvironment()
            capabilities = computeCapabilities(snapshot.collectionModules, environment)
            permissionStatus = computePermissionStatus(snapshot.collectionModules, environment)
            view?.let { bindAll(it, snapshot) }
            // Accepting a required module may newly need an OS permission — ask right away.
            if (accepted.isNotEmpty() && locallyApplied) {
                requestMissingPermissions()
            } else if (!locallyApplied) {
                Toast.makeText(
                    requireContext(),
                    getString(R.string.ds_policy_changed),
                    Toast.LENGTH_LONG,
                ).show()
            }
        }
    }

    private fun moduleRow(moduleId: CollectionModuleId, state: CollectionModuleState?): View {
        val moduleLabel = CollectionConsentCopy.localizedLabel(requireContext(), moduleId)
        // `collectedByStudy` true smart-casts `state` non-null, so the inner reads need no ?..
        val collectedByStudy = state?.serverEnabled == true
        // A required module reads as ON+locked only once accepted; a required-but-not-yet-
        // accepted module is paused (Accept lives in the consolidated list above), so its
        // switch shows OFF and non-interactive here.
        val requiredAccepted = collectedByStudy && state.requiredApplied && state.accepted
        val optional = collectedByStudy && !state.requiredApplied
        val isActive = state?.phase == CollectionModulePhase.ACTIVE

        val row = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            val pad = resources.getDimensionPixelSize(R.dimen.eq_space_4)
            setPadding(pad, pad, pad, pad)
            minimumHeight = resources.getDimensionPixelSize(R.dimen.chronicle_sensor_row_min_height)
            setBackgroundResource(R.drawable.bg_panel)
            val topMargin = resources.getDimensionPixelSize(R.dimen.eq_space_2)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            ).apply { setMargins(0, topMargin, 0, 0) }
        }

        val label = TextView(requireContext()).apply {
            text = getString(R.string.ds_module_row, moduleLabel, statusText(moduleId, state))
            setTextColor(resources.getColor(R.color.chronicle_text_primary, null))
            textSize = 16f
            minLines = 2
            includeFontPadding = true
            alpha = if (collectedByStudy) 1f else 0.6f
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        row.addView(label)

        // Required + not-collected modules show a read-only switch reflecting state; only an
        // optional, study-enabled module is interactive (the participant may turn it on/off).
        val switch = SwitchMaterial(requireContext()).apply {
            text = ""
            contentDescription = getString(R.string.ds_module_toggle_cd, moduleLabel)
            minHeight = resources.getDimensionPixelSize(R.dimen.chronicle_touch_target)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            ).apply { gravity = Gravity.CENTER_VERTICAL }
            isEnabled = optional && capabilities[moduleId].let {
                it == null || it.canCollectNow || it.canRequestAccess
            }
            isChecked = when {
                requiredAccepted -> true
                optional -> isActive
                else -> false
            }
        }
        if (optional) {
            // Order matters: set isChecked above BEFORE attaching the listener so binding does
            // not fire a spurious decision.
            bindOptionalToggle(switch, moduleId)
        }
        row.addView(switch)
        return row
    }

    private fun bindOptionalToggle(switch: SwitchMaterial, moduleId: CollectionModuleId) {
        switch.setOnCheckedChangeListener { _, on ->
            if (BuildConfig.HAS_HEALTH_CONNECT && on && moduleId == CollectionModuleId.HEALTH_CONNECT) {
                switch.isEnabled = false
                confirmExactHealthConnectScope(
                    onConfirmed = { reviewedScope ->
                        onToggleOptional(
                            moduleId,
                            on = true,
                            switch,
                            reviewedHealthConnectScope = reviewedScope,
                        )
                    },
                    onCancelled = {
                        switch.setOnCheckedChangeListener(null)
                        switch.isChecked = false
                        switch.isEnabled = true
                        bindOptionalToggle(switch, moduleId)
                    },
                )
            } else {
                onToggleOptional(moduleId, on, switch)
            }
        }
    }

    /** Re-consent must name the exact persisted study scope before reopening the module gate. */
    private fun confirmExactHealthConnectScope(
        onConfirmed: (Set<HealthConnectRecordType>) -> Unit,
        onCancelled: () -> Unit = {},
    ) {
        val scope = runCatching { HealthConnectScopeStore.of(requireContext()).read() }
            .getOrElse { emptySet() }
        if (scope.isEmpty()) {
            Toast.makeText(
                requireContext(),
                getString(R.string.ds_hc_scope_unavailable),
                Toast.LENGTH_LONG,
            ).show()
            onCancelled()
            return
        }
        AlertDialog.Builder(requireContext())
            .setTitle(R.string.ds_hc_review_title)
            .setMessage(
                healthConnectReconsentMessage(
                    scope,
                    readsOnlyHeader = getString(R.string.ds_hc_reads_only),
                    willNotReadHeader = getString(R.string.ds_hc_will_not_read),
                    template = CollectionConsentCopy.localizedConsentTemplate(
                        requireContext(),
                        CollectionModuleId.HEALTH_CONNECT,
                        scope,
                    ),
                ),
            )
            .setNegativeButton(android.R.string.cancel) { _, _ -> onCancelled() }
            .setOnCancelListener { onCancelled() }
            .setPositiveButton(R.string.ds_hc_accept_scope) { _, _ -> onConfirmed(scope) }
            .show()
    }

    private fun onToggleOptional(
        moduleId: CollectionModuleId,
        on: Boolean,
        switch: SwitchMaterial,
        reviewedHealthConnectScope: Set<HealthConnectRecordType>? = null,
    ) {
        switch.isEnabled = false
        viewLifecycleOwner.lifecycleScope.launch {
            val locallyApplied = withContext(Dispatchers.IO) {
                val coordinator = CollectionLoopCoordinator(requireContext().applicationContext)
                if (reviewedHealthConnectScope != null) {
                    coordinator.applyReviewedHealthConnectAcceptance(
                        reviewedHealthConnectScope,
                        ConsentTrigger.PARTICIPANT_TOGGLE,
                    )
                } else {
                    coordinator.applyDecisions(
                        accepted = if (on) setOf(moduleId) else emptySet(),
                        declined = if (on) emptySet() else setOf(moduleId),
                        trigger = ConsentTrigger.PARTICIPANT_TOGGLE,
                    )
                    true
                }
            }
            // Rebind from fresh state; the loop will also refresh, but this is immediate.
            val snapshot = DashboardDataRepository.load(requireContext())
            val environment = loadCapabilityEnvironment()
            capabilities = computeCapabilities(snapshot.collectionModules, environment)
            permissionStatus = computePermissionStatus(snapshot.collectionModules, environment)
            view?.let { bindAll(it, snapshot) }
            // Turning a module on may need an OS permission — ask now rather than make the
            // participant hunt for the affordance.
            if (on && locallyApplied) {
                requestMissingPermissions()
            } else if (!locallyApplied) {
                Toast.makeText(
                    requireContext(),
                    getString(R.string.ds_policy_changed),
                    Toast.LENGTH_LONG,
                ).show()
            }
        }
    }

    /** Missing-permission status for the active modules (see [computePermissionStatus]). */
    private data class PermissionStatus(
        val missingRuntime: List<String>,
        val needHealthConnect: Boolean,
        val needUsageAccess: Boolean = false,
        val needNotificationListener: Boolean = false,
        val needAccessibility: Boolean = false,
        val notificationAccessModules: Set<CollectionModuleId> = emptySet(),
    ) {
        val hasMissing: Boolean
            get() = missingRuntime.isNotEmpty() || needHealthConnect || needUsageAccess ||
                needNotificationListener || needAccessibility
    }

    private fun computeCapabilities(
        states: List<CollectionModuleState>,
        environment: CapabilityEnvironment,
    ): Map<CollectionModuleId, CollectionCapability> {
        // The one-time Play boundary deliberately removes restricted sensor-state rows. Resolve
        // the canonical sensor IDs as well as persisted state so their absence cannot be mistaken
        // for support and rendered as configurable controls in the minimal artifact.
        val ids = (states.map { it.moduleId } + SensorCollectionModules.sensorDisplayOrder).distinct()
        return runCatching {
            CollectionCapabilityResolver.resolveAll(
                ids,
                environment,
                requireContext().copyResolver(),
            )
        }.getOrElse { error ->
            ids.associateWith {
                CollectionCapability.TemporaryFailure(
                    getString(R.string.ds_capability_failed, error.javaClass.simpleName),
                )
            }
        }
    }

    private suspend fun loadCapabilityEnvironment(): CapabilityEnvironment {
        capabilityEnvironment?.let { return it }
        return withContext(Dispatchers.IO) {
            CollectionCapabilityResolver.snapshot(requireContext().applicationContext)
        }.also { capabilityEnvironment = it }
    }

    private fun statusText(moduleId: CollectionModuleId, state: CollectionModuleState?): String = when {
        state?.serverEnabled != true -> getString(R.string.ds_status_not_collected)
        capabilities[moduleId]?.canCollectNow == false -> capabilities.getValue(moduleId).message
        state.requiredApplied && state.accepted -> getString(R.string.ds_status_required_collecting)
        state.requiredAndDeclined -> getString(R.string.ds_status_declined_paused)
        state.requiredApplied -> getString(R.string.ds_status_required_accept)
        else -> getString(
            when (state.phase) {
                CollectionModulePhase.ACTIVE -> R.string.ds_status_on
                CollectionModulePhase.DECLINED -> R.string.ds_status_off_declined
                else -> R.string.ds_status_off
            },
        )
    }

    // ----- Sensors section -----
    //
    // Each sensor is its OWN consent-gated module (per-sensor consent redesign, 2026-06-11) — the
    // study marks each sensor required / optional / unavailable, and the participant toggles each
    // one individually, exactly like an App & Device Usage module. There is NO grouped "Hardware
    // Sensors" toggle. Each sensor's sampling rate + duty cycle are set by the study and shown
    // READ-ONLY here. A sensor the device lacks shows "Not available on this tablet"; one the study
    // does not collect shows "Not collected by this study". The section barrier is purely visual.

    private fun bindSensors(view: View, snapshot: DashboardSnapshot) {
        val byId = snapshot.collectionModules.associateBy { it.moduleId }
        val available = snapshot.sensors.available.toSet()
        val supportedSensorModules = SensorCollectionModules.sensorDisplayOrder.filterNot { moduleId ->
            capabilities[moduleId] is CollectionCapability.PolicyDisabled
        }
        val sectionTitle = view.findViewById<TextView>(R.id.sensorsSectionTitle)
        val summary = view.findViewById<TextView>(R.id.sensorsSummary)
        val list = view.findViewById<LinearLayout>(R.id.sensorToggleList)
        if (supportedSensorModules.isEmpty()) {
            sectionTitle.visibility = View.GONE
            summary.visibility = View.GONE
            list.visibility = View.GONE
            list.removeAllViews()
            return
        }
        sectionTitle.visibility = View.VISIBLE
        list.visibility = View.VISIBLE
        val studyCollectsAnySensor = supportedSensorModules.any { byId[it]?.serverEnabled == true }

        summary.apply {
            if (studyCollectsAnySensor) {
                visibility = View.GONE
            } else {
                text = getString(R.string.ds_no_sensor_collection)
                visibility = View.VISIBLE
            }
        }

        list.removeAllViews()
        // Canonical relevance order (SensorCollectionModules.sensorDisplayOrder) — the same order
        // the enrollment wizard and the web study form present, not an alphabetical scramble.
        supportedSensorModules.forEach { moduleId ->
            val sensor = SensorCollectionModules.sensorTypeOf(moduleId) ?: return@forEach
            list.addView(sensorModuleRow(sensor, moduleId, byId[moduleId], sensor in available, snapshot.sensors))
        }
    }

    /**
     * One sensor module's Data Sharing row — the per-sensor analogue of [moduleRow]. The toggle
     * accepts / declines that single sensor module (the same [onToggleOptional] path the usage
     * modules use). The sensor's study-set sampling rate + duty cycle are shown READ-ONLY; the
     * participant cannot change them here.
     */
    private fun sensorModuleRow(
        sensor: AndroidSensorType,
        moduleId: CollectionModuleId,
        state: CollectionModuleState?,
        isAvailable: Boolean,
        sensors: SensorDashboardSummary,
    ): View {
        val template = CollectionConsentCopy.template(moduleId)
        val collectedByStudy = state?.serverEnabled == true
        val requiredAccepted = collectedByStudy && state.requiredApplied && state.accepted
        val optional = collectedByStudy && !state.requiredApplied
        val isActive = state?.phase == CollectionModulePhase.ACTIVE
        val canToggle = optional && isAvailable

        val row = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            val pad = resources.getDimensionPixelSize(R.dimen.eq_space_4)
            setPadding(pad, pad, pad, pad)
            minimumHeight = resources.getDimensionPixelSize(R.dimen.chronicle_sensor_row_min_height)
            setBackgroundResource(R.drawable.bg_panel)
            val topMargin = resources.getDimensionPixelSize(R.dimen.eq_space_2)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            ).apply { setMargins(0, topMargin, 0, 0) }
        }

        val label = TextView(requireContext()).apply {
            text = getString(
                R.string.ds_module_row,
                CollectionConsentCopy.localizedLabel(requireContext(), moduleId),
                sensorStatusText(moduleId, state, isAvailable) +
                    rateDutyLine(sensor, collectedByStudy && isAvailable, sensors),
            )
            setTextColor(resources.getColor(R.color.chronicle_text_primary, null))
            textSize = 16f
            minLines = 2
            includeFontPadding = true
            alpha = if (collectedByStudy && isAvailable) 1f else 0.6f
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        row.addView(label)

        val switch = SwitchMaterial(requireContext()).apply {
            text = ""
            contentDescription = getString(R.string.ds_sensor_toggle_cd, CollectionConsentCopy.localizedLabel(requireContext(), moduleId))
            minHeight = resources.getDimensionPixelSize(R.dimen.chronicle_touch_target)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            ).apply { gravity = Gravity.CENTER_VERTICAL }
            isEnabled = canToggle
            isChecked = when {
                requiredAccepted -> true
                optional -> isActive
                else -> false
            }
        }
        if (canToggle) {
            // Order matters: set isChecked above BEFORE attaching the listener so binding does not
            // fire a spurious decision. Reuses the usage-module accept/decline path on this sensor.
            switch.setOnCheckedChangeListener { _, on -> onToggleOptional(moduleId, on, switch) }
        }
        row.addView(switch)
        return row
    }

    private fun sensorStatusText(
        moduleId: CollectionModuleId,
        state: CollectionModuleState?,
        isAvailable: Boolean,
    ): String = sensorCollectionStatusText(state, isAvailable, capabilities[moduleId], ::getString)

    /** The read-only, study-set sampling rate + duty cycle line for a sensor the study collects. */
    private fun rateDutyLine(sensor: AndroidSensorType, show: Boolean, sensors: SensorDashboardSummary): String {
        if (!show) return ""
        val rate = sensors.rateHzByType[sensor] ?: return ""
        val active = sensors.activeSecondsByType[sensor] ?: 0
        val period = sensors.periodSecondsByType[sensor] ?: 0
        val idle = (period - active).coerceAtLeast(0)
        return "\n" + getString(R.string.ds_sensor_study_setting, rate.toString(), active.toString(), idle.toString())
    }

    private companion object {
        private const val TAG = "DataSharingFragment"

        /**
         * Always-shown non-sensor consent-gated modules, in display order, for the App & Device
         * Usage section. `user_identification` is intentionally excluded — it is a shared-device
         * setting (Settings → Identify user), not a data-collection consent choice.
         */
        private val APP_USAGE_BASE = listOf(
            CollectionModuleId.USAGE_EVENTS,
            CollectionModuleId.DEVICE_LIFECYCLE,
            CollectionModuleId.BATTERY_TELEMETRY,
        )

        /**
         * Display order for the other non-sensor gated modules (shown only when the study enables
         * them). Mirrors the web study-form grouping. A gated module missing from this list still
         * renders (appended last) — see [appUsageModulesToShow].
         */
        private val APP_USAGE_OPTIONAL_ORDER = buildList {
            add(CollectionModuleId.IN_APP_ACTIVITY_CLASS)
            if (BuildConfig.ALLOW_RESTRICTED_RESEARCH_PERMISSIONS) {
                add(CollectionModuleId.INTERACTION_EVENTS)
                add(CollectionModuleId.AUDIO_ACTIVITY)
                add(CollectionModuleId.AUDIO_CONTENT)
                add(CollectionModuleId.NOTIFICATION_ACTIVITY)
                add(CollectionModuleId.SLEEP)
                add(CollectionModuleId.ACTIVITY_RECOGNITION)
            }
            if (BuildConfig.HAS_HEALTH_CONNECT) add(CollectionModuleId.HEALTH_CONNECT)
            add(CollectionModuleId.CONNECTIVITY_STATE)
            if (BuildConfig.HAS_APP_NETWORK_USAGE) add(CollectionModuleId.APP_NETWORK_USAGE)
            add(CollectionModuleId.DEVICE_SETTINGS)
        }
    }
}

internal fun healthConnectReconsentMessage(
    recordTypes: Set<HealthConnectRecordType>,
    readsOnlyHeader: String = "This study will read only these approved Health Connect record types:",
    willNotReadHeader: String = "It will not read:",
    template: CollectionConsentCopy.ModuleTemplate = CollectionConsentCopy.consentTemplate(
        CollectionModuleId.HEALTH_CONNECT,
        recordTypes,
    ),
): String {
    return buildString {
        append("$readsOnlyHeader\n\n")
        append(template.whatItCollects.joinToString("\n") { "• $it" })
        append("\n\n$willNotReadHeader\n")
        append(template.whatItDoesNotCollect.joinToString("\n") { "• $it" })
        if (template.caveats.isNotEmpty()) {
            append("\n\n")
            append(template.caveats.joinToString("\n\n"))
        }
    }
}

internal data class NotificationAccessDisclosure(
    val title: String,
    val affordanceMessage: String,
    val body: String,
)

/**
 * Prominent disclosure for Android's broad Notification access privilege. Audio Status and Audio
 * Metadata use that privilege only to observe media-session state; Notification Activity is the
 * separate consent choice that permits notification records to be retained.
 */
private const val ENGLISH_AUDIO_LIMIT = "Although Android grants broad notification visibility, Chronicle does not store notification content or notification metadata because Notification Activity was not separately accepted."

private val ENGLISH_NOTIFICATION_ACCESS: CopyResolver = englishCopy(
    mapOf(
        R.string.nad_unavailable_title to "Access unavailable",
        R.string.nad_unavailable_affordance to "This access is not included in the Play release.",
        R.string.nad_unavailable_body to "The Play release does not request Android Notification access.",
        R.string.nad_audio_status to "Audio Status",
        R.string.nad_audio_metadata to "Audio Metadata",
        R.string.nad_notification_activity to "Notification Activity",
        R.string.nad_accepted_data_type to "An accepted study data type",
        R.string.nad_needs to "needs",
        R.string.nad_need to "need",
        R.string.nad_title_both to "Allow audio and notification access?",
        R.string.nad_title_audio to "Allow audio media-session access?",
        R.string.nad_title_notification to "Allow notification activity access?",
        R.string.nad_purpose_both to "Chronicle uses Android Notification access for media-session status and for the " +
            "notification activity you separately accepted.",
        R.string.nad_purpose_audio to "Chronicle uses Android Notification access to observe active media-session app, " +
            "playback status, and the media metadata covered by the audio data types you accepted.",
        R.string.nad_purpose_notification to "Chronicle uses Android Notification access to record the notification source app, " +
            "category, timing, and events covered by Notification Activity.",
        R.string.nad_limit_notification to "It does not collect notification text, sender names, or message content.",
        R.string.nad_limit_audio to ENGLISH_AUDIO_LIMIT,
        R.string.nad_affordance to "%s %s Android Notification access. Tap to review why and open settings.",
        R.string.nad_body to "%s %s Research records are encrypted on this device and uploaded " +
            "only to the enrolled study server for its authorized research team. Chronicle does not sell " +
            "or use this data for advertising. You can cancel now or revoke access later. Android will " +
            "show the system access screen next.",
    ),
)

internal fun notificationAccessDisclosure(
    activeModules: Collection<CollectionModuleId>,
    copy: CopyResolver = ENGLISH_NOTIFICATION_ACCESS,
): NotificationAccessDisclosure {
    fun s(id: Int, vararg args: Any) = copy(id, args)
    if (!BuildConfig.ALLOW_RESTRICTED_RESEARCH_PERMISSIONS) {
        return NotificationAccessDisclosure(
            title = s(R.string.nad_unavailable_title),
            affordanceMessage = s(R.string.nad_unavailable_affordance),
            body = s(R.string.nad_unavailable_body),
        )
    }
    val audioLabels = buildList {
        if (CollectionModuleId.AUDIO_ACTIVITY in activeModules) add(s(R.string.nad_audio_status))
        if (CollectionModuleId.AUDIO_CONTENT in activeModules) add(s(R.string.nad_audio_metadata))
    }
    val notificationActivity = CollectionModuleId.NOTIFICATION_ACTIVITY in activeModules
    val names = (audioLabels + if (notificationActivity) listOf(s(R.string.nad_notification_activity)) else emptyList())
        .joinToString(", ")
        .ifBlank { s(R.string.nad_accepted_data_type) }
    val namedModuleCount = audioLabels.size + if (notificationActivity) 1 else 0
    val verb = s(if (namedModuleCount <= 1) R.string.nad_needs else R.string.nad_need)

    val title = s(
        when {
            audioLabels.isNotEmpty() && notificationActivity -> R.string.nad_title_both
            audioLabels.isNotEmpty() -> R.string.nad_title_audio
            else -> R.string.nad_title_notification
        },
    )
    val purpose = s(
        when {
            audioLabels.isNotEmpty() && notificationActivity -> R.string.nad_purpose_both
            audioLabels.isNotEmpty() -> R.string.nad_purpose_audio
            else -> R.string.nad_purpose_notification
        },
    )
    val notificationLimit = if (notificationActivity) {
        s(R.string.nad_limit_notification)
    } else {
        s(R.string.nad_limit_audio)
    }
    return NotificationAccessDisclosure(
        title = title,
        affordanceMessage = s(R.string.nad_affordance, names, verb),
        body = s(R.string.nad_body, purpose, notificationLimit),
    )
}

/**
 * Pure sensor-row presenter kept outside the Fragment so capability precedence is JVM-testable.
 * Hardware-absent modules are intentionally filtered before state reconciliation, which means
 * `state == null` even when a study enables them; availability must therefore be evaluated first.
 */
internal fun sensorCollectionStatusText(
    state: CollectionModuleState?,
    isAvailable: Boolean,
    capability: CollectionCapability?,
    resolve: (Int) -> String = ::englishSensorStatus,
): String = when {
    !isAvailable -> resolve(R.string.ds_sensor_unavailable)
    state?.serverEnabled != true -> resolve(R.string.ds_status_not_collected)
    capability?.canCollectNow == false -> capability.message
    state.requiredApplied && state.accepted -> resolve(R.string.ds_status_required_collecting)
    state.requiredAndDeclined -> resolve(R.string.ds_status_declined_paused)
    state.requiredApplied -> resolve(R.string.ds_status_required_accept)
    else -> when (state.phase) {
        CollectionModulePhase.ACTIVE -> resolve(R.string.ds_status_on)
        CollectionModulePhase.DECLINED -> resolve(R.string.ds_status_off_declined)
        else -> resolve(R.string.ds_status_off_share)
    }
}

/** JVM-testable English rendering of the sensor status ids; the fragment resolves via resources. */
private fun englishSensorStatus(id: Int): String = when (id) {
    R.string.ds_sensor_unavailable -> "Sensor is not available on this device"
    R.string.ds_status_not_collected -> "Not collected by this study"
    R.string.ds_status_required_collecting -> "Required — collecting"
    R.string.ds_status_declined_paused -> "Declined — all collection paused (accept above to resume)."
    R.string.ds_status_required_accept -> "Required — accept above to start sharing."
    R.string.ds_status_on -> "On — collecting"
    R.string.ds_status_off_declined -> "Off — you turned this off"
    else -> "Off — turn on to share"
}
