package com.openlattice.chronicle.ui

import com.openlattice.chronicle.preferences.IDENTIFY_USER
import com.openlattice.chronicle.EnrolledWork
import android.Manifest
import android.content.Context
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
import androidx.activity.result.contract.ActivityResultContracts
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import com.google.android.material.button.MaterialButton
import com.google.android.material.button.MaterialButtonToggleGroup
import com.google.android.material.materialswitch.MaterialSwitch
import com.openlattice.chronicle.BatteryOptimizationExemptionDialog
import com.openlattice.chronicle.OemBackgroundGuidance
import com.openlattice.chronicle.OemBackgroundGuidanceDialog
import com.openlattice.chronicle.hasIgnoreBatteryOptimization
import com.openlattice.chronicle.services.notifications.CHANNEL_ID
import com.openlattice.chronicle.services.notifications.deviceMonitoringMayRun
import com.openlattice.chronicle.BuildConfig
import com.openlattice.chronicle.LicensesActivity
import com.openlattice.chronicle.PlatformPolicyActivity
import com.openlattice.chronicle.R
import com.openlattice.chronicle.ServerEnrollmentActivity
import com.openlattice.chronicle.collection.CollectionModuleId
import com.openlattice.chronicle.collection.capability.DistributionChannel
import com.openlattice.chronicle.collection.core.ModuleResult
import com.openlattice.chronicle.collection.identification.DeviceUser
import com.openlattice.chronicle.collection.identification.TargetUserRouter
import com.openlattice.chronicle.collection.permissions.ModulePermissions
import com.openlattice.chronicle.collection.permissions.PermissionKind
import com.openlattice.chronicle.collection.state.CollectionGate
import com.openlattice.chronicle.collection.state.CollectionLoopStore
import com.openlattice.chronicle.collection.state.CollectionModuleState
import com.openlattice.chronicle.collection.state.ResearchPersistenceGate
import com.openlattice.chronicle.collection.state.StorageAdmission
import com.openlattice.chronicle.data.ParticipationStatus
import com.openlattice.chronicle.preferences.EncryptedPrefsHelper
import com.openlattice.chronicle.preferences.EnrollmentSettings
import com.openlattice.chronicle.services.notifications.DeviceUnlockMonitoringService
import com.openlattice.chronicle.services.notifications.IDENTIFY_USER_CHANNEL_ID
import com.openlattice.chronicle.services.notifications.UnlockMonitoringRuntimeStatus
import com.openlattice.chronicle.services.notifications.hasNotificationListenerAccess
import com.openlattice.chronicle.services.notifications.hasNotificationPermission
import com.openlattice.chronicle.services.notifications.hasPostNotificationsRuntimePermission
import com.openlattice.chronicle.services.notifications.notificationPopsUp
import com.openlattice.chronicle.services.notifications.notificationSettingsIntent
import com.openlattice.chronicle.services.withdrawal.WithdrawalState
import com.openlattice.chronicle.services.withdrawal.WithdrawalStateStore
import com.openlattice.chronicle.storage.LocalStoreRecoveryRequiredException
import com.openlattice.chronicle.utils.DeviceSettingsNavigator
import com.openlattice.chronicle.utils.ExternalLinks
import java.net.URI
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private const val TAG = "SettingsHomeFragment"

internal fun notificationAccessControlVisible(restrictedResearchPermissions: Boolean): Boolean =
    restrictedResearchPermissions

class SettingsHomeFragment : Fragment(R.layout.fragment_settings_home) {
    private var activeStudyPrivacyUrl: String? = null
    private var activeStudyWithdrawalUrl: String? = null
    private var refreshingControls: Boolean = false
    private val notificationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted -> if (granted) (activity as? com.openlattice.chronicle.MainActivity)?.onNotificationsAllowed()
        val toggle = view?.findViewById<MaterialSwitch>(R.id.identifyUserSwitch) ?: return@registerForActivityResult
        toggle.isChecked = false
        if (granted) {
            showIdentifyUserDisclosure(toggle)
        } else {
            showNotificationPermissionRecovery()
        }
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        if (!BuildConfig.ALLOW_RESTRICTED_RESEARCH_PERMISSIONS) {
            // Minimal distributions expose the enrolled identity in the study summary; study server
            // details stays visible because it holds Withdraw from study.
            listOf(
                R.id.settingsServerList,
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
        view.findViewById<MaterialButton>(R.id.surveyNotificationSettingsButton).setOnClickListener {
            DeviceSettingsNavigator.open(requireContext(), notificationSettingsIntent(requireContext(), CHANNEL_ID))
        }
        view.findViewById<MaterialButton>(R.id.batterySettingsButton).setOnClickListener {
            BatteryOptimizationExemptionDialog().show(parentFragmentManager, "batteryExemption")
        }
        view.findViewById<MaterialButton>(R.id.oemSettingsButton).setOnClickListener {
            OemBackgroundGuidanceDialog().show(parentFragmentManager, "oemBackgroundGuidance")
        }
        view.findViewById<MaterialSwitch>(R.id.identifyUserSwitch).setOnClickListener { clicked ->
            if (refreshingControls) return@setOnClickListener
            val toggle = clicked as MaterialSwitch
            if (!toggle.isChecked) {
                setIdentifyUserEnabled(false)
                return@setOnClickListener
            }
            toggle.isChecked = false
            when (
                identifyUserNotificationAction(
                    sdkInt = Build.VERSION.SDK_INT,
                    runtimePermissionGranted = hasPostNotificationsRuntimePermission(requireContext()),
                    notificationsEnabled = hasNotificationPermission(requireContext(), IDENTIFY_USER_CHANNEL_ID),
                )
            ) {
                IdentifyUserNotificationAction.PROCEED -> showIdentifyUserDisclosure(toggle)
                IdentifyUserNotificationAction.REQUEST_RUNTIME ->
                    notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                IdentifyUserNotificationAction.OPEN_SETTINGS -> showNotificationPermissionRecovery()
            }
        }

        view.findViewById<MaterialButtonToggleGroup>(R.id.deviceUserGroup).apply {
            // The toggle group forces each child to one ellipsized line when it is added; the
            // stacked full-width choices wrap instead, so long translations and large fonts fit.
            for (index in 0 until childCount) (getChildAt(index) as TextView).maxLines = Int.MAX_VALUE
        }
            .addOnButtonCheckedListener { _, checkedId, isChecked ->
                if (!isChecked || refreshingControls) return@addOnButtonCheckedListener
                val user = when (checkedId) {
                    R.id.deviceUserParticipant -> DeviceUser.STUDY_PARTICIPANT
                    R.id.deviceUserSomeoneElse -> DeviceUser.SOMEONE_ELSE
                    else -> DeviceUser.NOT_SET
                }
                persistTargetUser(user)
            }

        view.findViewById<MaterialButton>(R.id.identifyUserNotificationSettingsButton).setOnClickListener {
            openIdentifyUserNotificationSettings()
        }

        if (notificationAccessControlVisible(BuildConfig.ALLOW_RESTRICTED_RESEARCH_PERMISSIONS)) {
            view.findViewById<MaterialSwitch>(R.id.notificationAccessSwitch)
                .setOnClickListener {
                    if (!refreshingControls) confirmNotificationAccess()
                }
        }

        view.findViewById<MaterialButton>(R.id.openServerSettingsButton).setOnClickListener {
            viewLifecycleOwner.lifecycleScope.launch(storageFailureHandler()) {
                val snapshot = DashboardDataRepository.load(requireContext())
                val intent = Intent(requireContext(), ServerEnrollmentActivity::class.java)
                snapshot.servers.firstOrNull()?.let {
                    intent.putExtra(ServerEnrollmentActivity.EXTRA_SERVER_ID, it.id)
                }
                startActivity(intent)
            }
        }
        view.findViewById<MaterialButton>(R.id.openSourceLicensesButton).setOnClickListener {
            startActivity(Intent(requireContext(), LicensesActivity::class.java))
        }
        view.findViewById<MaterialButton>(R.id.privacyPolicyButton).setOnClickListener {
            startActivity(Intent(requireContext(), PlatformPolicyActivity::class.java))
        }
        view.findViewById<MaterialButton>(R.id.studyWithdrawalSettingsButton).setOnClickListener {
            activeStudyWithdrawalUrl?.let { url -> ExternalLinks.openHttps(requireContext(), url) }
        }
        view.findViewById<MaterialButton>(R.id.reminderTimingButton).apply {
            visibility = if (BuildConfig.ALLOW_RESTRICTED_RESEARCH_PERMISSIONS && Build.VERSION.SDK_INT >= 31)
                View.VISIBLE else View.GONE
            setOnClickListener { showReminderTimingOptions() }
        }
        view.findViewById<MaterialButton>(R.id.studyPrivacyPolicyButton).setOnClickListener {
            activeStudyPrivacyUrl?.let { url -> ExternalLinks.openHttps(requireContext(), url) }
        }
    }

    private fun showReminderTimingOptions() {
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.reminder_timing_title)
            .setMessage(R.string.reminder_timing_optional)
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(R.string.reminder_timing_review) { _, _ ->
                DeviceSettingsNavigator.open(requireContext(), Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM)
                    .setData(Uri.parse("package:${requireContext().packageName}")))
            }.show()
    }

    private data class IdentificationUpdateResult(val failureMessage: Int?, val targetResult: ModuleResult?)

    private fun setIdentifyUserEnabled(enabled: Boolean) {
        val context = requireContext()
        val appContext = context.applicationContext
        if (enabled && !hasNotificationPermission(appContext, IDENTIFY_USER_CHANNEL_ID)) {
            view?.findViewById<MaterialSwitch>(R.id.identifyUserSwitch)?.isChecked = false
            showNotificationPermissionRecovery()
            return
        }
        viewLifecycleOwner.lifecycleScope.launch(storageFailureHandler()) {
            val (failureMessage, targetResult) = withContext(Dispatchers.IO) {
                val settings = EnrollmentSettings(appContext)
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
                            .putBoolean(IDENTIFY_USER, enabled)
                            .commit(),
                    ) { "Failed to persist identify-user preference" }
                    if (enabled) {
                        DeviceUnlockMonitoringService.startAuthorizedService(appContext)
                    } else {
                        targetResult = TargetUserRouter.setTargetUser(
                            appContext,
                            DeviceUser.NOT_SET,
                            settings,
                        )
                        DeviceUnlockMonitoringService.reconcile(appContext)
                    }
                    // The survey reminder is armed only on a shared phone.
                    EnrolledWork.rescheduleNotifications(appContext)
                }
                val failureMessage = if (persisted && studyAuthorized) null else identificationFailureMessage(appContext, settings)
                IdentificationUpdateResult(failureMessage, targetResult)
            }
            reportIdentificationResult(appContext, failureMessage, targetResult)
            view?.let(::refresh)
        }
    }

    /** Names why an identify-user change was not saved, checked in the order a participant can act on it. */

    private fun reportIdentificationResult(appContext: Context, failureMessage: Int?, targetResult: ModuleResult?) {
        val message = when {
            failureMessage != null -> failureMessage
            targetResult is ModuleResult.Skipped -> TargetUserRouter.failureMessage(targetResult)
            targetResult != null && targetResult !is ModuleResult.Ok -> R.string.settings_identification_off_save_failed
            else -> null
        }
        if (message != null) {
            Toast.makeText(requireContext(), getString(message), Toast.LENGTH_LONG).show()
        } else if (targetResult is ModuleResult.Ok) {
            EnrolledWork.rescheduleSync(appContext)
        }
    }

    private fun showIdentifyUserDisclosure(toggle: MaterialSwitch) {
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.identify_user_disclosure_title)
            .setMessage(R.string.identify_user_disclosure_body)
            .setNegativeButton(android.R.string.cancel) { _, _ -> toggle.isChecked = false }
            .setPositiveButton(R.string.continue_action) { _, _ -> setIdentifyUserEnabled(true) }
            .show()
    }

    private fun showNotificationPermissionRecovery() {
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.settings_notifications_title)
            .setMessage(R.string.settings_notifications_body)
            .setNegativeButton(android.R.string.cancel) { _, _ -> view?.let(::refresh) }
            .setPositiveButton(R.string.settings_open_notification_settings) { _, _ ->
                openIdentifyUserNotificationSettings()
            }
            .show()
    }

    private fun openIdentifyUserNotificationSettings() {
        DeviceSettingsNavigator.open(
            requireContext(),
            notificationSettingsIntent(requireContext(), IDENTIFY_USER_CHANNEL_ID),
        )
    }

    private fun persistTargetUser(user: DeviceUser) {
        val appContext = requireContext().applicationContext
        viewLifecycleOwner.lifecycleScope.launch(storageFailureHandler()) {
            val result = try {
                withContext(Dispatchers.IO) {
                    TargetUserRouter.setTargetUser(appContext, user)
                }
            } catch (error: LocalStoreRecoveryRequiredException) {
                requireActivity().openStoreRecovery(error)
                return@launch
            }
            if (result is ModuleResult.Ok) {
                EnrolledWork.rescheduleSync(appContext)
            } else {
                Log.w(TAG, "Target-user update failed: ${result.label}")
                Toast.makeText(
                    requireContext(),
                    getString(TargetUserRouter.failureMessage(result)),
                    Toast.LENGTH_LONG,
                ).show()
            }
        }
    }

    private fun refresh(view: View) {
        val appContext = requireContext().applicationContext
        // Lock participant controls until the Room-backed active-enrollment proof is read off-main.
        view.findViewById<MaterialSwitch>(R.id.identifyUserSwitch).apply {
            visibility = View.GONE
            isEnabled = false
        }
        view.findViewById<TextView>(R.id.identifyUserStatus).visibility = View.GONE
        view.findViewById<MaterialButton>(R.id.identifyUserNotificationSettingsButton).visibility = View.GONE
        view.findViewById<MaterialButtonToggleGroup>(R.id.deviceUserGroup).apply {
            visibility = View.GONE
            isEnabled = false
            for (index in 0 until childCount) getChildAt(index).isEnabled = false
        }
        view.findViewById<MaterialSwitch>(R.id.notificationAccessSwitch).apply {
            visibility = if (notificationAccessControlVisible(BuildConfig.ALLOW_RESTRICTED_RESEARCH_PERMISSIONS)) {
                View.VISIBLE
            } else {
                View.GONE
            }
            isEnabled = false
        }
        viewLifecycleOwner.lifecycleScope.launch(storageFailureHandler()) {
            val state = withContext(Dispatchers.IO) {
                val settings = EnrollmentSettings(appContext)
                val activeEnrollment = ResearchPersistenceGate.isActiveEnrollment(appContext)
                val userIdentificationAuthorized =
                    activeEnrollment &&
                        settings.isUserIdentificationStudyAuthorized() &&
                        CollectionGate.collects(appContext, CollectionModuleId.USER_IDENTIFICATION)
                val userIdentificationPreferenceEnabled =
                    userIdentificationAuthorized && settings.isUserIdentificationEnabled()
                SettingsRefreshState(
                    withdrawal = WithdrawalStateStore(appContext).state(),
                    access = SettingsAccessState(
                        surveyReminderEnabled = BuildConfig.ALLOW_PARTICIPANT_FORM_REMINDERS &&
                            (settings.getAwarenessNotificationsEnabled() || settings.getMobileReminderRequestCodes().isNotEmpty()),
                        surveyNotificationGranted = hasNotificationPermission(appContext, CHANNEL_ID),
                        batteryExempt = hasIgnoreBatteryOptimization(appContext),
                        monitoringAuthorized = deviceMonitoringMayRun(appContext),
                    ),
                    activeEnrollment = activeEnrollment,
                    userIdentificationAuthorized = userIdentificationAuthorized,
                    userIdentificationPreferenceEnabled = userIdentificationPreferenceEnabled,
                    notificationPermissionGranted = hasNotificationPermission(appContext, IDENTIFY_USER_CHANNEL_ID),
                    promptPopsUp = notificationPopsUp(appContext, IDENTIFY_USER_CHANNEL_ID),
                    runtimeStartDeferred = UnlockMonitoringRuntimeStatus.isDeferred(appContext),
                    currentUser = settings.getCurrentUser(),
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
            promptPopsUp = state.promptPopsUp,
        )
        val userIdentificationControls = userIdentificationControlState(
            activeEnrollment = activeEnrollment,
            studyAuthorized = state.userIdentificationAuthorized,
            participantEnabled = userIdentificationRuntime.effective,
        )
        renderPlatformAccess(view, state.access, activeEnrollment)
        refreshingControls = true
        try {
            view.findViewById<MaterialSwitch>(R.id.identifyUserSwitch).apply {
                visibility = if (userIdentificationControls.visible) View.VISIBLE else View.GONE
                isChecked = userIdentificationRuntime.effective
                isEnabled = userIdentificationControls.switchEnabled
            }
            view.findViewById<TextView>(R.id.identifyUserStatus).apply {
                visibility = if (userIdentificationControls.visible) View.VISIBLE else View.GONE
                text = getString(
                    when (userIdentificationRuntime) {
                        UserIdentificationRuntimeState.NEEDS_NOTIFICATION_RECOVERY ->
                            R.string.identify_user_status_notification_recovery
                        UserIdentificationRuntimeState.START_DEFERRED -> R.string.identify_user_status_start_deferred
                        UserIdentificationRuntimeState.RUNNING_SILENT -> R.string.identify_user_status_silent
                        UserIdentificationRuntimeState.RUNNING -> R.string.identify_user_status_active
                        UserIdentificationRuntimeState.OFF -> R.string.identify_user_status_off
                    },
                )
            }
            view.findViewById<MaterialButton>(R.id.identifyUserNotificationSettingsButton).visibility =
                if (userIdentificationControls.visible &&
                    (userIdentificationRuntime == UserIdentificationRuntimeState.NEEDS_NOTIFICATION_RECOVERY ||
                        userIdentificationRuntime == UserIdentificationRuntimeState.RUNNING_SILENT)
                ) View.VISIBLE else View.GONE
            val currentUser = state.currentUser
            view.findViewById<MaterialButtonToggleGroup>(R.id.deviceUserGroup).apply {
                visibility = if (userIdentificationControls.visible) View.VISIBLE else View.GONE
                check(
                    when (DeviceUser.fromStored(currentUser)) {
                        DeviceUser.STUDY_PARTICIPANT -> R.id.deviceUserParticipant
                        DeviceUser.SOMEONE_ELSE -> R.id.deviceUserSomeoneElse
                        DeviceUser.NOT_SET, null -> R.id.deviceUserUnset
                    },
                )
                isEnabled = userIdentificationControls.targetChoicesEnabled
                for (index in 0 until childCount) {
                    getChildAt(index).isEnabled = userIdentificationControls.targetChoicesEnabled
                }
            }
            view.findViewById<MaterialSwitch>(R.id.notificationAccessSwitch).apply {
                visibility = if (notificationAccessControlVisible(BuildConfig.ALLOW_RESTRICTED_RESEARCH_PERMISSIONS)) {
                    View.VISIBLE
                } else {
                    View.GONE
                }
                if (!notificationAccessControlVisible(BuildConfig.ALLOW_RESTRICTED_RESEARCH_PERMISSIONS)) {
                    return@apply
                }
                isChecked = hasNotificationListenerAccess(requireContext())
                isEnabled = notificationAccessMayBeRequested(
                    activeEnrollment = activeEnrollment,
                    withdrawalState = withdrawalState,
                    moduleStates = state.snapshot.collectionModules,
                )
            }
        } finally {
            refreshingControls = false
        }
        if (state.access.monitoringAuthorized) {
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
        activeStudyWithdrawalUrl = snapshot.servers.firstOrNull()?.withdrawalUrl
        view.findViewById<MaterialButton>(R.id.studyWithdrawalSettingsButton).apply {
            visibility = if (activeStudyWithdrawalUrl == null) View.GONE else View.VISIBLE
            isEnabled = activeStudyWithdrawalUrl != null
        }
        view.findViewById<TextView>(R.id.studyPolicyEffectiveAt).apply {
            val effective = snapshot.servers.firstOrNull()?.policyEffectiveAt
            visibility = if (effective == null) View.GONE else View.VISIBLE
            text = effective?.let { getString(R.string.study_policy_effective_at, it) }
        }
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
            MaterialAlertDialogBuilder(requireContext())
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
        viewLifecycleOwner.lifecycleScope.launch(storageFailureHandler()) {
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
                val spacing = resources.getDimensionPixelSize(R.dimen.eq_space_4)
            setPadding(spacing, spacing, spacing, spacing)
                setBackgroundResource(R.drawable.bg_panel)
            }
            row.addView(TextView(requireContext()).apply {
                setTextAppearance(R.style.Chronicle_Body)
                text = getString(
                    R.string.settings_server_row,
                    server.name,
                    server.healthLabel,
                    server.url,
                    getString(server.uploadsLabel),
                )
                setOnClickListener { openServer(server.id) }
            })
            if (DistributionChannel.current() == DistributionChannel.RESEARCH) row.addView(MaterialButton(requireContext()).apply {
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
        viewLifecycleOwner.lifecycleScope.launch(storageFailureHandler()) {
            withContext(Dispatchers.IO) { participantStorageWrite {
                ResearchPersistenceGate
                    .setServerEnabled(requireContext().applicationContext, serverId, enabled)
            }.getOrThrow() }
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

private fun renderPlatformAccess(view: View, access: SettingsAccessState, activeEnrollment: Boolean) {
    val remindersVisible = activeEnrollment && access.surveyReminderEnabled
    view.findViewById<TextView>(R.id.surveyNotificationStatus).apply {
        visibility = if (remindersVisible) View.VISIBLE else View.GONE
        setText(if (access.surveyNotificationGranted) R.string.survey_notifications_granted else R.string.survey_notifications_denied)
    }
    view.findViewById<View>(R.id.surveyNotificationSettingsButton).visibility =
        if (remindersVisible) View.VISIBLE else View.GONE
    view.findViewById<View>(R.id.batterySettingsButton).visibility = if (activeEnrollment) View.VISIBLE else View.GONE
    val oem = activeEnrollment && OemBackgroundGuidance.matches(Build.MANUFACTURER)
    view.findViewById<View>(R.id.oemSettingsButton).visibility = if (oem) View.VISIBLE else View.GONE
    view.findViewById<TextView>(R.id.backgroundAccessStatus).apply {
        visibility = if (activeEnrollment) View.VISIBLE else View.GONE
        text = listOfNotNull(
            view.context.getString(if (access.batteryExempt) R.string.background_access_granted else R.string.background_access_missing),
            view.context.getString(R.string.oem_background_needs_review).takeIf { oem },
        ).joinToString("\n")
    }
}

private data class SettingsRefreshState(
    val withdrawal: WithdrawalState,
    val access: SettingsAccessState,
    val activeEnrollment: Boolean,
    val userIdentificationAuthorized: Boolean,
    val userIdentificationPreferenceEnabled: Boolean,
    val notificationPermissionGranted: Boolean,
    val promptPopsUp: Boolean,
    val runtimeStartDeferred: Boolean,
    val currentUser: String?,
    val snapshot: DashboardSnapshot,
)

private data class SettingsAccessState(
    val surveyReminderEnabled: Boolean,
    val surveyNotificationGranted: Boolean,
    val batteryExempt: Boolean,
    val monitoringAuthorized: Boolean,
)

/** Exactly one user-identification runtime mode; [effective] means the prompt service runs. */
internal enum class UserIdentificationRuntimeState(val effective: Boolean) {
    OFF(false),
    NEEDS_NOTIFICATION_RECOVERY(false),
    START_DEFERRED(false),
    /** Running, but the prompt channel is Silent or not "Pop on screen": prompts land unseen in the shade. */
    RUNNING_SILENT(true),
    RUNNING(true),
}

internal fun userIdentificationRuntimeState(
    authorized: Boolean,
    preferenceEnabled: Boolean,
    notificationPermissionGranted: Boolean,
    runtimeStartDeferred: Boolean = false,
    promptPopsUp: Boolean = true,
): UserIdentificationRuntimeState = when {
    !authorized || !preferenceEnabled -> UserIdentificationRuntimeState.OFF
    !notificationPermissionGranted -> UserIdentificationRuntimeState.NEEDS_NOTIFICATION_RECOVERY
    runtimeStartDeferred -> UserIdentificationRuntimeState.START_DEFERRED
    !promptPopsUp -> UserIdentificationRuntimeState.RUNNING_SILENT
    else -> UserIdentificationRuntimeState.RUNNING
}

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

internal fun activeEnrollmentSummary(
    studyId: UUID?,
    participantId: String,
    server: UploadServerSummary?,
    copy: CopyResolver,
): String {
    fun s(id: Int, vararg args: Any) = copy(id, args)
    if (server == null) return s(R.string.summary_no_enrollment)
    val unavailable = s(R.string.summary_unavailable)
    val host = runCatching { URI(server.url).host }
        .getOrNull()
        ?.takeIf(String::isNotBlank)
        ?: unavailable
    val displayStudyId = studyId?.toString() ?: s(R.string.overview_no_study)
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
        add(s(R.string.summary_uploads, s(server.uploadsLabel)))
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

private fun identificationFailureMessage(appContext: Context, settings: EnrollmentSettings): Int {
    val enrolled = settings.isEnrolledOrThrow() &&
        settings.getParticipationStatus() == ParticipationStatus.ENROLLED &&
        WithdrawalStateStore(appContext).stateOrThrow() == WithdrawalState.NONE
    return when {
        !enrolled -> R.string.settings_control_needs_enrollment
        StorageAdmission.isPaused(appContext) -> R.string.settings_control_storage_paused
        !settings.isUserIdentificationStudyAuthorized() ||
            !CollectionGate.collects(appContext, CollectionModuleId.USER_IDENTIFICATION) ->
            R.string.settings_identification_unavailable
        else -> R.string.settings_identification_save_failed
    }
}
