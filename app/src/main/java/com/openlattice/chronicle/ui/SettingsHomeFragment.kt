package com.openlattice.chronicle.ui

import android.Manifest
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.util.Log
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.core.app.NotificationManagerCompat
import androidx.activity.result.contract.ActivityResultContracts
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.appcompat.app.AlertDialog
import com.google.android.material.button.MaterialButton
import com.google.android.material.button.MaterialButtonToggleGroup
import com.google.android.material.switchmaterial.SwitchMaterial
import com.openlattice.chronicle.R
import com.openlattice.chronicle.BuildConfig
import com.openlattice.chronicle.LocalStoreRecoveryActivity
import com.openlattice.chronicle.ServerEnrollmentActivity
import com.openlattice.chronicle.collection.CollectionModuleId
import com.openlattice.chronicle.collection.core.ModuleResult
import com.openlattice.chronicle.collection.identification.TargetUserRouter
import com.openlattice.chronicle.collection.permissions.ModulePermissions
import com.openlattice.chronicle.collection.permissions.PermissionKind
import com.openlattice.chronicle.collection.state.CollectionLoopStore
import com.openlattice.chronicle.collection.state.CollectionGate
import com.openlattice.chronicle.collection.state.CollectionModuleState
import com.openlattice.chronicle.collection.state.ResearchPersistenceGate
import com.openlattice.chronicle.padForSystemBars
import com.openlattice.chronicle.preferences.EncryptedPrefsHelper
import com.openlattice.chronicle.preferences.EnrollmentSettings
import com.openlattice.chronicle.services.notifications.DeviceUnlockMonitoringService
import com.openlattice.chronicle.services.notifications.UnlockMonitoringRuntimeStatus
import com.openlattice.chronicle.services.notifications.hasNotificationPermission
import com.openlattice.chronicle.services.notifications.hasPostNotificationsRuntimePermission
import com.openlattice.chronicle.services.sync.scheduleChronicleSyncWork
import com.openlattice.chronicle.services.withdrawal.WithdrawalState
import com.openlattice.chronicle.services.withdrawal.WithdrawalStateStore
import com.openlattice.chronicle.storage.ChronicleDb
import com.openlattice.chronicle.storage.LocalStoreRecoveryRequiredException
import com.openlattice.chronicle.utils.DeviceSettingsNavigator
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.net.URI

private const val TAG = "SettingsHomeFragment"

internal fun notificationAccessControlVisible(restrictedResearchPermissions: Boolean): Boolean =
    restrictedResearchPermissions

class SettingsHomeFragment : Fragment(R.layout.fragment_settings_home) {
    private lateinit var settings: EnrollmentSettings
    private var activeStudyPrivacyUrl: String? = null
    private var refreshingControls: Boolean = false
    private val notificationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        val toggle = view?.findViewById<SwitchMaterial>(R.id.identifyUserSwitch) ?: return@registerForActivityResult
        toggle.isChecked = false
        if (granted) {
            showIdentifyUserDisclosure(toggle)
        } else {
            showNotificationPermissionRecovery()
        }
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        view.findViewById<View>(R.id.settingsHomeContent).padForSystemBars()
        settings = EnrollmentSettings(requireContext())
        if (BuildConfig.DISTRIBUTION_CHANNEL == "PLAY") {
            // Play participants can see the one active study/server identity below, but cannot
            // add, edit, pause, or redirect it from an app-settings control.
            listOf(
                R.id.settingsServerList,
                R.id.openServerSettingsButton,
                R.id.addServerSettingsButton,
            ).forEach { view.findViewById<View>(it).visibility = View.GONE }
        }
        if (!notificationAccessControlVisible(BuildConfig.ALLOW_RESTRICTED_RESEARCH_PERMISSIONS)) {
            // Notification-listener access belongs only to the excluded research notification/
            // audio modules. Do not show a dead, high-risk system-permission control in Play.
            view.findViewById<View>(R.id.notificationAccessSwitch).visibility = View.GONE
        }
        bindControls(view)
    }

    override fun onResume() {
        super.onResume()
        view?.let { refresh(it) }
    }

    private fun bindControls(view: View) {
        view.findViewById<SwitchMaterial>(R.id.identifyUserSwitch).setOnClickListener { clicked ->
            if (refreshingControls) return@setOnClickListener
            val toggle = clicked as SwitchMaterial
            if (!toggle.isChecked) {
                setIdentifyUserEnabled(false)
                return@setOnClickListener
            }
            toggle.isChecked = false
            when (
                identifyUserNotificationAction(
                    sdkInt = Build.VERSION.SDK_INT,
                    runtimePermissionGranted = hasPostNotificationsRuntimePermission(requireContext()),
                    notificationsEnabled = hasNotificationPermission(requireContext()),
                )
            ) {
                IdentifyUserNotificationAction.PROCEED -> showIdentifyUserDisclosure(toggle)
                IdentifyUserNotificationAction.REQUEST_RUNTIME ->
                    notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                IdentifyUserNotificationAction.OPEN_SETTINGS -> showNotificationPermissionRecovery()
            }
        }

        view.findViewById<MaterialButtonToggleGroup>(R.id.deviceUserGroup)
            .addOnButtonCheckedListener { _, checkedId, isChecked ->
                if (!isChecked || refreshingControls) return@addOnButtonCheckedListener
                val user = when (checkedId) {
                    R.id.deviceUserChild -> getString(R.string.user_target_child)
                    R.id.deviceUserOther -> getString(R.string.user_other)
                    else -> getString(R.string.user_unassigned)
                }
                persistTargetUser(user)
            }

        if (notificationAccessControlVisible(BuildConfig.ALLOW_RESTRICTED_RESEARCH_PERMISSIONS)) {
            view.findViewById<SwitchMaterial>(R.id.notificationAccessSwitch)
                .setOnClickListener {
                    if (!refreshingControls) confirmNotificationAccess()
                }
        }

        view.findViewById<MaterialButton>(R.id.openServerSettingsButton).setOnClickListener {
            viewLifecycleOwner.lifecycleScope.launch {
                val snapshot = DashboardDataRepository.load(requireContext())
                val intent = Intent(requireContext(), ServerEnrollmentActivity::class.java)
                snapshot.servers.firstOrNull()?.let {
                    intent.putExtra(ServerEnrollmentActivity.EXTRA_SERVER_ID, it.id)
                }
                startActivity(intent)
            }
        }
        view.findViewById<MaterialButton>(R.id.addServerSettingsButton).setOnClickListener {
            startActivity(Intent(requireContext(), ServerEnrollmentActivity::class.java))
        }
        view.findViewById<MaterialButton>(R.id.privacyPolicyButton).setOnClickListener {
            val privacy = Intent(
                Intent.ACTION_VIEW,
                Uri.parse(getString(R.string.platform_privacy_policy_url)),
            )
            if (privacy.resolveActivity(requireContext().packageManager) != null) startActivity(privacy)
        }
        view.findViewById<MaterialButton>(R.id.studyPrivacyPolicyButton).setOnClickListener {
            activeStudyPrivacyUrl?.let { url ->
                val privacy = Intent(Intent.ACTION_VIEW, Uri.parse(url))
                if (privacy.resolveActivity(requireContext().packageManager) != null) startActivity(privacy)
            }
        }
    }

    private fun setIdentifyUserEnabled(enabled: Boolean) {
        val context = requireContext()
        val appContext = context.applicationContext
        if (enabled && !hasNotificationPermission(appContext)) {
            view?.findViewById<SwitchMaterial>(R.id.identifyUserSwitch)?.isChecked = false
            showNotificationPermissionRecovery()
            return
        }
        viewLifecycleOwner.lifecycleScope.launch {
            val (persisted, targetResult) = withContext(Dispatchers.IO) {
                var targetResult: ModuleResult? = null
                var studyAuthorized = false
                val persisted = ResearchPersistenceGate.persistIfActive(appContext) {
                    // Re-check the authenticated manifest after the disclosure dialog and under
                    // the same withdrawal barrier as the preference/write transition.
                    studyAuthorized = settings.isUserIdentificationStudyAuthorized() &&
                        CollectionGate.collects(appContext, CollectionModuleId.USER_IDENTIFICATION)
                    if (!studyAuthorized) return@persistIfActive
                    check(
                        EncryptedPrefsHelper.getEncryptedPrefs(appContext)
                            .edit()
                            .putBoolean(appContext.getString(R.string.identify_user), enabled)
                            .commit(),
                    ) { "Failed to persist identify-user preference" }
                    if (enabled) {
                        DeviceUnlockMonitoringService.startAuthorizedService(appContext)
                    } else {
                        targetResult = TargetUserRouter.setTargetUser(
                            appContext,
                            appContext.getString(R.string.user_unassigned),
                            settings,
                        )
                        DeviceUnlockMonitoringService.stopService(appContext)
                    }
                }
                (persisted && studyAuthorized) to targetResult
            }
            if (!persisted) {
                Toast.makeText(
                    requireContext(),
                    getString(R.string.settings_control_needs_enrollment),
                    Toast.LENGTH_LONG,
                ).show()
            } else if (targetResult != null && targetResult !is ModuleResult.Ok) {
                Toast.makeText(
                    requireContext(),
                    getString(R.string.settings_identification_off_save_failed),
                    Toast.LENGTH_LONG,
                ).show()
            } else if (targetResult is ModuleResult.Ok) {
                scheduleChronicleSyncWork(appContext)
            }
            view?.let(::refresh)
        }
    }

    private fun showIdentifyUserDisclosure(toggle: SwitchMaterial) {
        AlertDialog.Builder(requireContext())
            .setTitle(R.string.identify_user_disclosure_title)
            .setMessage(R.string.identify_user_disclosure_body)
            .setNegativeButton(android.R.string.cancel) { _, _ -> toggle.isChecked = false }
            .setPositiveButton(R.string.continue_action) { _, _ -> setIdentifyUserEnabled(true) }
            .show()
    }

    private fun showNotificationPermissionRecovery() {
        AlertDialog.Builder(requireContext())
            .setTitle(R.string.settings_notifications_title)
            .setMessage(R.string.settings_notifications_body)
            .setNegativeButton(android.R.string.cancel) { _, _ -> view?.let(::refresh) }
            .setPositiveButton(R.string.settings_open_notification_settings) { _, _ ->
                DeviceSettingsNavigator.open(
                    requireContext(),
                    Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).apply {
                        putExtra(Settings.EXTRA_APP_PACKAGE, requireContext().packageName)
                    },
                )
            }
            .show()
    }

    private fun persistTargetUser(user: String) {
        val appContext = requireContext().applicationContext
        viewLifecycleOwner.lifecycleScope.launch {
            val result = try {
                withContext(Dispatchers.IO) {
                    TargetUserRouter.setTargetUser(appContext, user, settings)
                }
            } catch (error: LocalStoreRecoveryRequiredException) {
                startActivity(LocalStoreRecoveryActivity.intent(requireContext(), error.recoveryReason))
                return@launch
            }
            if (result is ModuleResult.Ok) {
                scheduleChronicleSyncWork(appContext)
            } else {
                Log.w(TAG, "Target-user update failed: ${result.label}")
                Toast.makeText(
                    requireContext(),
                    getString(R.string.user_save_failed),
                    Toast.LENGTH_LONG,
                ).show()
            }
        }
    }

    private fun refresh(view: View) {
        val appContext = requireContext().applicationContext
        // Lock participant controls until the Room-backed active-enrollment proof is read off-main.
        view.findViewById<SwitchMaterial>(R.id.identifyUserSwitch).apply {
            visibility = View.GONE
            isEnabled = false
        }
        view.findViewById<TextView>(R.id.identifyUserStatus).visibility = View.GONE
        view.findViewById<MaterialButtonToggleGroup>(R.id.deviceUserGroup).apply {
            visibility = View.GONE
            isEnabled = false
            for (index in 0 until childCount) getChildAt(index).isEnabled = false
        }
        view.findViewById<SwitchMaterial>(R.id.notificationAccessSwitch).apply {
            visibility = if (notificationAccessControlVisible(BuildConfig.ALLOW_RESTRICTED_RESEARCH_PERMISSIONS)) {
                View.VISIBLE
            } else {
                View.GONE
            }
            isEnabled = false
        }
        viewLifecycleOwner.lifecycleScope.launch {
            val state = withContext(Dispatchers.IO) {
                val activeEnrollment = ResearchPersistenceGate.isActiveEnrollment(appContext)
                val userIdentificationAuthorized =
                    activeEnrollment &&
                        settings.isUserIdentificationStudyAuthorized() &&
                        CollectionGate.collects(appContext, CollectionModuleId.USER_IDENTIFICATION)
                val userIdentificationPreferenceEnabled =
                    userIdentificationAuthorized && settings.isUserIdentificationEnabled()
                SettingsRefreshState(
                    withdrawal = WithdrawalStateStore(appContext).state(),
                    activeEnrollment = activeEnrollment,
                    userIdentificationAuthorized = userIdentificationAuthorized,
                    userIdentificationPreferenceEnabled = userIdentificationPreferenceEnabled,
                    notificationPermissionGranted = hasNotificationPermission(appContext),
                    runtimeStartDeferred = UnlockMonitoringRuntimeStatus.isDeferred(appContext),
                    snapshot = DashboardDataRepository.load(appContext),
                )
            }
            if (this@SettingsHomeFragment.view !== view) return@launch
            renderRefresh(view, state)
        }
    }

    private fun renderRefresh(view: View, state: SettingsRefreshState) {
        val withdrawalState = state.withdrawal
        val activeEnrollment = state.activeEnrollment
        val userIdentificationRuntime = userIdentificationRuntimeState(
            authorized = state.userIdentificationAuthorized,
            preferenceEnabled = state.userIdentificationPreferenceEnabled,
            notificationPermissionGranted = state.notificationPermissionGranted,
            runtimeStartDeferred = state.runtimeStartDeferred,
        )
        val userIdentificationControls = userIdentificationControlState(
            activeEnrollment = activeEnrollment,
            studyAuthorized = state.userIdentificationAuthorized,
            participantEnabled = userIdentificationRuntime.effective,
        )
        refreshingControls = true
        try {
            view.findViewById<SwitchMaterial>(R.id.identifyUserSwitch).apply {
                visibility = if (userIdentificationControls.visible) View.VISIBLE else View.GONE
                isChecked = userIdentificationRuntime.effective
                isEnabled = userIdentificationControls.switchEnabled
            }
            view.findViewById<TextView>(R.id.identifyUserStatus).apply {
                visibility = if (userIdentificationControls.visible) View.VISIBLE else View.GONE
                text = when {
                    userIdentificationRuntime.needsNotificationRecovery ->
                        getString(R.string.identify_user_status_notification_recovery)
                    userIdentificationRuntime.startDeferred ->
                        getString(R.string.identify_user_status_start_deferred)
                    userIdentificationRuntime.effective ->
                        getString(R.string.identify_user_status_active)
                    else -> getString(R.string.identify_user_status_off)
                }
            }
            val currentUser = settings.getCurrentUser()
            view.findViewById<MaterialButtonToggleGroup>(R.id.deviceUserGroup).apply {
                visibility = if (userIdentificationControls.visible) View.VISIBLE else View.GONE
                check(
                    when (currentUser) {
                        getString(R.string.user_target_child) -> R.id.deviceUserChild
                        getString(R.string.user_other) -> R.id.deviceUserOther
                        else -> R.id.deviceUserUnset
                    },
                )
                isEnabled = userIdentificationControls.targetChoicesEnabled
                for (index in 0 until childCount) {
                    getChildAt(index).isEnabled = userIdentificationControls.targetChoicesEnabled
                }
            }
            view.findViewById<SwitchMaterial>(R.id.notificationAccessSwitch).apply {
                visibility = if (notificationAccessControlVisible(BuildConfig.ALLOW_RESTRICTED_RESEARCH_PERMISSIONS)) {
                    View.VISIBLE
                } else {
                    View.GONE
                }
                if (!notificationAccessControlVisible(BuildConfig.ALLOW_RESTRICTED_RESEARCH_PERMISSIONS)) {
                    return@apply
                }
                isChecked = NotificationManagerCompat.getEnabledListenerPackages(requireContext())
                    .contains(requireContext().packageName)
                isEnabled = notificationAccessMayBeRequested(
                    activeEnrollment = activeEnrollment,
                    withdrawalState = withdrawalState,
                    moduleStates = state.snapshot.collectionModules,
                )
            }
        } finally {
            refreshingControls = false
        }
        if (userIdentificationRuntime.effective) {
            if (!DeviceUnlockMonitoringService.startAuthorizedService(requireContext().applicationContext)) {
                refresh(view)
            }
        } else {
            DeviceUnlockMonitoringService.stopService(requireContext().applicationContext)
        }
        val snapshot = state.snapshot
        view.findViewById<TextView>(R.id.settingsServerSummary).text =
            activeEnrollmentSummary(
                studyId = snapshot.studyId,
                participantId = snapshot.participantId,
                server = snapshot.servers.firstOrNull(),
                copy = requireContext().copyResolver(),
            )
        activeStudyPrivacyUrl = snapshot.servers.firstOrNull()?.privacyPolicyUrl
        view.findViewById<MaterialButton>(R.id.studyPrivacyPolicyButton).apply {
            visibility = if (activeStudyPrivacyUrl == null) View.GONE else View.VISIBLE
            isEnabled = activeStudyPrivacyUrl != null
        }
        bindServerList(view, snapshot.servers)
        view.findViewById<MaterialButton>(R.id.openServerSettingsButton).isEnabled =
            snapshot.servers.isNotEmpty()
    }

    private fun confirmNotificationAccess() {
        if (!BuildConfig.ALLOW_RESTRICTED_RESEARCH_PERMISSIONS) return
        withNotificationAccessAllowed { activeModules ->
            val disclosure = notificationAccessDisclosure(activeModules, requireContext().copyResolver())
            AlertDialog.Builder(requireContext())
                .setTitle(disclosure.title)
                .setMessage(disclosure.body)
                .setNegativeButton(android.R.string.cancel) { _, _ -> view?.let(::refresh) }
                .setPositiveButton(R.string.continue_action) { _, _ ->
                    // Consent or enrollment may have changed while the disclosure was open.
                    withNotificationAccessAllowed {
                        DeviceSettingsNavigator.open(
                            requireContext(),
                            Intent("android.settings.ACTION_NOTIFICATION_LISTENER_SETTINGS"),
                        )
                    }
                }
                .show()
        }
    }

    private fun withNotificationAccessAllowed(onAllowed: (Set<CollectionModuleId>) -> Unit) {
        val appContext = requireContext().applicationContext
        viewLifecycleOwner.lifecycleScope.launch {
            val activeModules = withContext(Dispatchers.IO) {
                val moduleStates = CollectionLoopStore.of(appContext).loadAll().values
                if (notificationAccessMayBeRequested(
                    activeEnrollment = ResearchPersistenceGate.isActiveEnrollment(appContext),
                    withdrawalState = WithdrawalStateStore(appContext).state(),
                    moduleStates = moduleStates,
                )) {
                    activeNotificationAccessModules(moduleStates)
                } else {
                    emptySet()
                }
            }
            if (!isAdded || view == null) return@launch
            if (activeModules.isNotEmpty()) {
                onAllowed(activeModules)
            } else {
                Toast.makeText(
                    requireContext(),
                    getString(R.string.settings_notification_access_needs_module),
                    Toast.LENGTH_LONG,
                ).show()
                view?.let(::refresh)
            }
        }
    }

    private fun bindServerList(view: View, servers: List<UploadServerSummary>) {
        val container = view.findViewById<LinearLayout>(R.id.settingsServerList)
        container.removeAllViews()

        servers.forEach { server ->
            val row = LinearLayout(requireContext()).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(16, 16, 16, 16)
                setBackgroundResource(R.drawable.bg_panel)
            }
            row.addView(TextView(requireContext()).apply {
                setTextAppearance(android.R.style.TextAppearance_Material_Body1)
                text = getString(
                    R.string.settings_server_row,
                    server.name,
                    server.healthLabel,
                    server.url,
                    getString(if (server.enabled) R.string.settings_uploads_enabled else R.string.settings_uploads_paused),
                )
                setOnClickListener { openServer(server.id) }
            })
            row.addView(MaterialButton(requireContext()).apply {
                text = getString(if (server.enabled) R.string.settings_pause_uploads else R.string.settings_enable_uploads)
                setOnClickListener { setServerEnabled(server.id, !server.enabled) }
            })
            container.addView(row, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                bottomMargin = 12
            })
        }
    }

    private fun openServer(serverId: Long) {
        val intent = Intent(requireContext(), ServerEnrollmentActivity::class.java)
            .putExtra(ServerEnrollmentActivity.EXTRA_SERVER_ID, serverId)
        startActivity(intent)
    }

    private fun setServerEnabled(serverId: Long, enabled: Boolean) {
        viewLifecycleOwner.lifecycleScope.launch {
            withContext(Dispatchers.IO) {
                ChronicleDb.getInstance(requireContext().applicationContext)
                    .uploadServerDao()
                    .setEnabled(serverId, enabled)
            }
            view?.let { refresh(it) }
        }
    }
}

internal enum class IdentifyUserNotificationAction {
    PROCEED,
    REQUEST_RUNTIME,
    OPEN_SETTINGS,
}

internal fun identifyUserNotificationAction(
    sdkInt: Int,
    runtimePermissionGranted: Boolean,
    notificationsEnabled: Boolean,
): IdentifyUserNotificationAction = when {
    notificationsEnabled -> IdentifyUserNotificationAction.PROCEED
    sdkInt >= Build.VERSION_CODES.TIRAMISU && !runtimePermissionGranted ->
        IdentifyUserNotificationAction.REQUEST_RUNTIME
    else -> IdentifyUserNotificationAction.OPEN_SETTINGS
}

private data class SettingsRefreshState(
    val withdrawal: WithdrawalState,
    val activeEnrollment: Boolean,
    val userIdentificationAuthorized: Boolean,
    val userIdentificationPreferenceEnabled: Boolean,
    val notificationPermissionGranted: Boolean,
    val runtimeStartDeferred: Boolean,
    val snapshot: DashboardSnapshot,
)

internal data class UserIdentificationRuntimeState(
    val effective: Boolean,
    val needsNotificationRecovery: Boolean,
    val startDeferred: Boolean,
)

internal fun userIdentificationRuntimeState(
    authorized: Boolean,
    preferenceEnabled: Boolean,
    notificationPermissionGranted: Boolean,
    runtimeStartDeferred: Boolean = false,
): UserIdentificationRuntimeState = UserIdentificationRuntimeState(
    effective = authorized && preferenceEnabled && notificationPermissionGranted && !runtimeStartDeferred,
    needsNotificationRecovery = authorized && preferenceEnabled && !notificationPermissionGranted,
    startDeferred = authorized && preferenceEnabled && notificationPermissionGranted && runtimeStartDeferred,
)

internal data class UserIdentificationControlState(
    val visible: Boolean,
    val switchEnabled: Boolean,
    val targetChoicesEnabled: Boolean,
)

/** The local toggle narrows authenticated study scope; it can never make the controls authoritative. */
internal fun userIdentificationControlState(
    activeEnrollment: Boolean,
    studyAuthorized: Boolean,
    participantEnabled: Boolean,
): UserIdentificationControlState {
    val available = activeEnrollment && studyAuthorized
    return UserIdentificationControlState(
        visible = available,
        switchEnabled = available,
        targetChoicesEnabled = available && participantEnabled,
    )
}

private val ENGLISH_SUMMARY: CopyResolver = englishCopy(
    mapOf(
        R.string.summary_no_enrollment to "No active study enrollment is configured on this device.",
        R.string.summary_unavailable to "Unavailable",
        R.string.summary_header to "Active study enrollment",
        R.string.summary_study_id to "Study ID: %s",
        R.string.summary_participant to "Participant reference: %s",
        R.string.summary_server to "Study server: %s",
        R.string.summary_host to "Server host: %s",
        R.string.summary_origin to "Server origin: %s",
        R.string.summary_institution to "Responsible institution: %s",
        R.string.summary_contact to "Research contact: %s",
        R.string.summary_disclosure_version to "Disclosure version: %s",
        R.string.summary_uploads to "Uploads: %s",
        R.string.summary_uploads_active to "active",
        R.string.summary_uploads_stopped to "stopped",
        R.string.summary_connection to "Connection: %s",
    ),
)

internal fun activeEnrollmentSummary(
    studyId: String,
    participantId: String,
    server: UploadServerSummary?,
    copy: CopyResolver = ENGLISH_SUMMARY,
): String {
    fun s(id: Int, vararg args: Any) = copy(id, args)
    if (server == null) return s(R.string.summary_no_enrollment)
    val unavailable = s(R.string.summary_unavailable)
    val host = runCatching { URI(server.url).host }
        .getOrNull()
        ?.takeIf(String::isNotBlank)
        ?: unavailable
    val displayStudyId = studyId.ifBlank { unavailable }
    val displayParticipantId = participantId.ifBlank { unavailable }
    val displayServerName = server.name.ifBlank { host }
    return buildList {
        add(s(R.string.summary_header))
        add(s(R.string.summary_study_id, displayStudyId))
        add(s(R.string.summary_participant, displayParticipantId))
        add(s(R.string.summary_server, displayServerName))
        add(s(R.string.summary_host, host))
        add(s(R.string.summary_origin, server.url))
        server.responsibleInstitution?.takeIf(String::isNotBlank)?.let { add(s(R.string.summary_institution, it)) }
        server.researchContact?.takeIf(String::isNotBlank)?.let { add(s(R.string.summary_contact, it)) }
        server.disclosureVersion?.takeIf(String::isNotBlank)?.let { add(s(R.string.summary_disclosure_version, it)) }
        add(s(R.string.summary_uploads, s(if (server.enabled) R.string.summary_uploads_active else R.string.summary_uploads_stopped)))
        add(s(R.string.summary_connection, server.healthLabel))
    }.joinToString("\n")
}

internal fun notificationAccessMayBeRequested(
    activeEnrollment: Boolean,
    withdrawalState: WithdrawalState,
    moduleStates: Collection<CollectionModuleState>,
): Boolean =
    activeEnrollment &&
        withdrawalState == WithdrawalState.NONE &&
        !CollectionModuleState.collectionHalted(moduleStates) &&
        activeNotificationAccessModules(moduleStates).isNotEmpty()

internal fun activeNotificationAccessModules(
    moduleStates: Collection<CollectionModuleState>,
): Set<CollectionModuleId> = moduleStates
    .asSequence()
    .filter(CollectionModuleState::collectsWhenEnrolled)
    .map(CollectionModuleState::moduleId)
    .filter { ModulePermissions.needsKind(listOf(it), PermissionKind.NOTIFICATION_LISTENER) }
    .toSet()
