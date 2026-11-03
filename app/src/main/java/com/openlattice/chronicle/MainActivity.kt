package com.openlattice.chronicle

import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.annotation.RequiresApi
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import com.openlattice.chronicle.services.notifications.hasNotificationListenerAccess
import androidx.fragment.app.Fragment
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.withStarted
import com.google.android.material.bottomnavigation.BottomNavigationView
import com.openlattice.chronicle.collection.state.CollectionAckRetryQueue
import com.openlattice.chronicle.collection.state.CollectionLoopCoordinator
import com.openlattice.chronicle.collection.state.ResearchPersistenceGate
import com.openlattice.chronicle.preferences.EnrollmentSettings
import com.openlattice.chronicle.services.notifications.DeviceUnlockMonitoringService
import com.openlattice.chronicle.services.notifications.hasPostNotificationsRuntimePermission
import com.openlattice.chronicle.services.sync.triggerImmediateChronicleSync
import com.openlattice.chronicle.services.withdrawal.ParticipantWithdrawalManager
import com.openlattice.chronicle.services.withdrawal.WithdrawalState
import com.openlattice.chronicle.services.withdrawal.WithdrawalStateStore
import com.openlattice.chronicle.ui.OverviewFragment
import com.openlattice.chronicle.collection.capability.activeModuleAccessMissing
import com.openlattice.chronicle.ui.DataSharingFragment
import com.openlattice.chronicle.ui.SettingsHomeFragment
import com.openlattice.chronicle.ui.UploadsFragment
import com.openlattice.chronicle.ui.openStoreRecovery
import com.openlattice.chronicle.ui.storageFailureHandler
import com.openlattice.chronicle.storage.LocalStoreRecoveryRequiredException
import com.openlattice.chronicle.storage.LocalStoreState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private const val FOREGROUND_SYNC_INTERVAL = 60_000L
private val EXEMPTION_DIALOG_TAGS =
    listOf("batteryExemption", "hibernationExemption", "backgroundDataRestricted", "oemBackgroundGuidance")

class MainActivity(private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO) : AppCompatActivity() {

    private lateinit var enrollmentSettings: EnrollmentSettings
    private lateinit var bottomNav: BottomNavigationView

    private var routingJob: Job? = null
    private var enrolledServicesStarted = false
    private var ackRouteAttempted = false
    private var accessRouteAttempted = false
    private var lastForegroundSyncEnqueuedAt = 0L

    // Optional, one-time notification ask (Android 13+). Notifications are NOT required to
    // collect data — this is the single place the app asks, on first dashboard land after a
    // consent-first enrollment. The result is never gated; collection runs either way.
    private val notificationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { /* optional — collection proceeds regardless of the choice */ }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        lifecycleScope.launch(storageFailureHandler()) {
            enrollmentSettings = withContext(ioDispatcher) { EnrollmentSettings(applicationContext) }
            WithdrawalStateStore(this@MainActivity).states.observe(this@MainActivity) { state ->
                if (state == WithdrawalState.COMPLETE || state == WithdrawalState.CANCELLED) routeFromLocalEnrollment()
            }
            routeFromLocalEnrollment()
        }

        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) {
            onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
                override fun handleOnBackPressed() {
                    startActivity(Intent(Intent.ACTION_MAIN).apply {
                        addCategory(Intent.CATEGORY_HOME)
                        flags = Intent.FLAG_ACTIVITY_NEW_TASK
                    })
                }
            })
        }

    }

    /** Creation, resume and completed withdrawal share the authoritative off-main Room read. */
    private fun routeFromLocalEnrollment() {
        if (!::enrollmentSettings.isInitialized || isFinishing) return
        routingJob?.cancel()
        routingJob = lifecycleScope.launch(storageFailureHandler()) {
            val enrolled = try {
                withContext(ioDispatcher) {
                    // Unreadable retained acknowledgments need the recovery screen, like an unreadable store.
                    CollectionAckRetryQueue.of(applicationContext).load()
                    // Refresh the cached identity fields as well as Room after withdrawal.
                    EnrollmentSettings(applicationContext).hasConfiguredEnrollmentOrThrow()
                }
            } catch (error: LocalStoreRecoveryRequiredException) {
                // A full disk keeps the appropriate participant screen reachable; the next resume retries the read.
                ResearchPersistenceGate.closeForPersistenceFailure()
                val identity = if (LocalStoreState.of(error) == LocalStoreState.WriteRefused) {
                    withContext(ioDispatcher) { EnrollmentSettings(applicationContext).hasEnrollmentIdentity() }
                } else false
                navigateWhenStarted {
                    if (openStoreRecovery(error)) finish()
                    else {
                        if (!routeToEnrollmentOrWithdrawal(identity) && !::bottomNav.isInitialized) {
                            setupNavigation()
                            handleSelectTabExtra(intent)
                        }
                    }
                }
                return@launch
            } catch (error: CancellationException) {
                throw error
            } catch (error: RuntimeException) {
                Log.e("MainActivity", "Enrollment read failed", error)
                false
            }
            navigateWhenStarted { applyStartupRoute(enrolled) }
        }
    }

    /** Runs inside [navigateWhenStarted]: the route the startup read decided. */
    private fun applyStartupRoute(enrolled: Boolean) {
        if (routeToEnrollmentOrWithdrawal(enrolled)) return
        // Navigation may already exist without the services: a withdrawal or a full disk set it up first.
        if (!enrolledServicesStarted) {
            enrolledServicesStarted = true
            startEnrolledServices()
        }
        if (!::bottomNav.isInitialized) {
            setupNavigation()
            handleSelectTabExtra(intent)
        }
        if (lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) {
            showNextExemptionDialog()
            resumeEnrolled()
        }
    }

    private fun routeToEnrollmentOrWithdrawal(enrolled: Boolean): Boolean {
        val withdrawal = WithdrawalStateStore(this).state()
        if (withdrawal.requiresResolution) {
            ParticipantWithdrawalManager.resumePending(this)
            if (!::bottomNav.isInitialized) {
                setupNavigation()
                selectTab(R.id.nav_settings)
            }
            return true
        }
        if (!enrolled) {
            DeviceUnlockMonitoringService.stopService(applicationContext)
            startActivity(Intent(this, Enrollment::class.java).apply {
                data = intent.data
                action = intent.action
            })
            finish()
            return true
        }
        return false
    }

    /**
     * The one boundary for navigation an asynchronous read decides. Its job survives STOPPED, so the result waits for
     * STARTED: a fragment transaction after onSaveInstanceState would crash (H-STALE-NAV, AND-V01). ast-grep rule
     * android-main-activity-async-navigation keeps every such tab change inside it.
     */
    private suspend fun navigateWhenStarted(route: () -> Unit) = lifecycle.withStarted { if (!isFinishing) route() }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleSelectTabExtra(intent)
    }

    /** Honors a deep-link (e.g. the collection-review notification) asking to open a given tab. */
    private fun handleSelectTabExtra(intent: Intent?) {
        val tab = intent?.getIntExtra(EXTRA_SELECT_TAB, 0) ?: 0
        if (tab != 0) selectTab(tab)
    }

    private fun setupNavigation() {
        bottomNav = findViewById(R.id.mainBottomNav)
        bottomNav.setOnItemSelectedListener { item ->
            val fragment = when (item.itemId) {
                R.id.nav_uploads -> UploadsFragment()
                R.id.nav_data_sharing -> DataSharingFragment()
                R.id.nav_settings -> SettingsHomeFragment()
                else -> OverviewFragment()
            }
            showFragment(fragment)
            true
        }
        // Not keyed on savedInstanceState: a recreation can arrive before any tab was committed.
        if (supportFragmentManager.findFragmentById(R.id.mainFragmentContainer) == null) {
            bottomNav.selectedItemId = R.id.nav_overview
        }
    }

    private fun showFragment(fragment: Fragment) {
        if (supportFragmentManager.findFragmentById(R.id.mainFragmentContainer)?.javaClass == fragment.javaClass) return
        supportFragmentManager
            .beginTransaction()
            .replace(R.id.mainFragmentContainer, fragment)
            .commitNow()
    }

    /** Switches the bottom nav to [itemId] (used by in-page links, e.g. the Overview review card). */
    fun selectTab(itemId: Int) {
        if (::bottomNav.isInitialized) bottomNav.selectedItemId = itemId
    }

    private fun startEnrolledServices() {
        lifecycleScope.launch(storageFailureHandler()) {
            withContext(ioDispatcher) {
                // Notification permission is NOT a gate. Data collection (foreground-service based,
                // poll-driven) runs whether or not notifications are granted; a denied permission only
                // hides study reminders / new-data-option alerts. The optional ask lives on the
                // acknowledgment screen after the participant agrees.
                EnrolledWork.reconcile(applicationContext)
                CollectionLoopCoordinator(applicationContext).updateSensorService()
                DeviceUnlockMonitoringService.reconcile(applicationContext)
            }
        }.invokeOnCompletion { if (it != null) enrolledServicesStarted = false }
    }

    /**
     * True when Chronicle is exempt from app hibernation / permission auto-reset
     * ("Pause app activity if unused"). Fails open — if the platform query throws,
     * we skip the prompt rather than nag on a state we can't read.
     *
     * An enabled notification listener also counts: Android auto-exempts such apps
     * from hibernation and greys out the "Manage app if unused" toggle, but
     * isAutoRevokeWhitelisted still reports false for them — without this check the
     * dialog re-prompts forever on a device where the participant can't act on it.
     */
    @RequiresApi(Build.VERSION_CODES.R)
    private fun isExemptFromAppHibernation(): Boolean =
        runCatching {
            packageManager.isAutoRevokeWhitelisted ||
                hasNotificationListenerAccess(this)
        }.getOrDefault(true)

    /** Background data off, or Data Saver on without an exemption. Fails open, like the check above. */
    private fun isBackgroundDataRestricted(): Boolean =
        runCatching {
            val connectivity = applicationContext.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            connectivity.restrictBackgroundStatus == ConnectivityManager.RESTRICT_BACKGROUND_STATUS_ENABLED
        }.getOrDefault(false)

    override fun onResume() {
        super.onResume()
        routeFromLocalEnrollment()
    }

    /**
     * Shows the first background-collection prompt after the routing read confirms enrollment.
     * Every resume rechecks local enrollment before showing enrolled-only prompts.
     */
    private fun showNextExemptionDialog() {
        if (!::enrollmentSettings.isInitialized) return
        // A permission prompt or settings page pauses and resumes this activity; without the tag
        // check each return stacked another copy of a dialog that was still open. One dialog at a
        // time; the next one in the chain shows after this one is dismissed.
        if (EXEMPTION_DIALOG_TAGS.any { supportFragmentManager.findFragmentByTag(it) != null }) return
        if (!hasIgnoreBatteryOptimization(this) &&
            enrollmentSettings.isBatteryOptimizationDialogEnabled()
        ) {
            BatteryOptimizationExemptionDialog().show(supportFragmentManager, "batteryExemption")
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R &&
            !isExemptFromAppHibernation() &&
            enrollmentSettings.isHibernationExemptionDialogEnabled()
        ) {
            // App hibernation force-stops Chronicle and auto-revokes its permissions after
            // months without UI opens — the normal state for a passive collection device.
            // One dialog at a time: this chains behind the battery prompt.
            AppHibernationExemptionDialog().show(supportFragmentManager, "hibernationExemption")
        } else if (isBackgroundDataRestricted() && enrollmentSettings.isBackgroundDataDialogEnabled()) {
            BackgroundDataRestrictionDialog().show(supportFragmentManager, "backgroundDataRestricted")
        } else if (OemBackgroundGuidance.matches(Build.MANUFACTURER) &&
            enrollmentSettings.isOemGuidanceDialogEnabled()
        ) {
            OemBackgroundGuidanceDialog().show(supportFragmentManager, "oemBackgroundGuidance")
        }
    }

    private fun resumeEnrolled() {
        maybeTriggerForegroundSync()
        routeToAcknowledgmentIfPending()
        maybeAskNotificationsOnce()
    }

    /**
     * Asks for notification permission once, ever (Android 13+), the first time an enrolled
     * participant lands on the dashboard. This is the app's single, optional notification ask —
     * it replaces the old enrollment-gating "OPEN SETTINGS" screen. Denying it does not affect
     * data collection; it suppresses study reminders, "a new data option needs your review"
     * alerts, and the optional Identify User device-unlock prompt. Settings provides an explicit
     * request/system-settings recovery path if Identify User is enabled later. A persisted flag
     * keeps this general dashboard prompt to a single ask across resumes / process death.
     */
    private fun maybeAskNotificationsOnce() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        if (hasPostNotificationsRuntimePermission(this)) return
        val prefs = getSharedPreferences("main_activity_prefs", Context.MODE_PRIVATE)
        if (prefs.getBoolean("notification_asked", false)) return
        prefs.edit().putBoolean("notification_asked", true).apply()
        NotificationRationaleDialog().show(supportFragmentManager, NotificationRationaleDialog.TAG)
    }

    /** Called by [NotificationRationaleDialog] once the participant has read why notifications are asked. */
    internal fun requestNotificationPermission() {
        notificationPermissionLauncher.launch(android.Manifest.permission.POST_NOTIFICATIONS)
    }

    private fun maybeTriggerForegroundSync() {
        val now = System.currentTimeMillis()
        if (now - lastForegroundSyncEnqueuedAt < FOREGROUND_SYNC_INTERVAL) return
        lastForegroundSyncEnqueuedAt = now
        triggerImmediateChronicleSync(applicationContext)
    }

    private fun routeToAcknowledgmentIfPending() {
        if (ackRouteAttempted) return
        lifecycleScope.launch {
            val pending = withContext(ioDispatcher) {
                try {
                    CollectionLoopCoordinator(applicationContext).pendingAcknowledgmentSnapshot()
                } catch (e: Exception) {
                    Log.e("MainActivity", "Pending acknowledgment status is unavailable")
                    null
                }
            }
            if (pending == null) {
                Toast.makeText(this@MainActivity, R.string.ds_capability_failed, Toast.LENGTH_LONG).show()
                return@launch
            }
            val decisionPending = pending.modules.isNotEmpty()
            navigateWhenStarted {
                if (decisionPending && !ackRouteAttempted) {
                    ackRouteAttempted = true
                    accessRouteAttempted = true // Data Sharing also shows the missing-access rows.
                    // A module is awaiting a per-module decision — land the participant on the Data
                    // Sharing tab to review it. No bulk "I agree": each module is decided individually
                    // there (the old all-at-once acknowledgment screen is retired).
                    selectTab(R.id.nav_data_sharing)
                }
            }
            if (decisionPending || accessRouteAttempted) return@launch
            val accessMissing = withContext(ioDispatcher) {
                try {
                    activeModuleAccessMissing(applicationContext)
                } catch (e: Exception) {
                    Log.w("MainActivity", "Failed to read module access status", e)
                    false
                }
            }
            navigateWhenStarted {
                if (accessMissing && !accessRouteAttempted) {
                    accessRouteAttempted = true
                    // An accepted module is inert until its OS access (e.g. Usage Access, which
                    // enrollment defers to Data Sharing) is granted — land there so it is requested.
                    selectTab(R.id.nav_data_sharing)
                }
            }
        }
    }

    companion object {
        /** Intent extra: a bottom-nav item id to switch to on launch (e.g. from a notification). */
        const val EXTRA_SELECT_TAB = "extra_select_tab"
    }
}
