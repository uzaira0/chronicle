package com.openlattice.chronicle

import android.app.AlarmManager
import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.os.Build
import android.os.Bundle
import android.util.Log
import androidx.activity.OnBackPressedCallback
import androidx.annotation.RequiresApi
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.NotificationManagerCompat
import androidx.fragment.app.Fragment
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.withStarted
import com.google.android.material.bottomnavigation.BottomNavigationView
import com.openlattice.chronicle.collection.state.CollectionLoopCoordinator
import com.openlattice.chronicle.collection.DistributionRestrictedRuntime
import com.openlattice.chronicle.preferences.EnrollmentSettings
import com.openlattice.chronicle.preferences.SensorSettings
import com.openlattice.chronicle.services.enrollment.scheduleEnrollmentMonitoringWork
import com.openlattice.chronicle.services.notifications.DeviceUnlockMonitoringService
import com.openlattice.chronicle.services.notifications.hasPostNotificationsRuntimePermission
import com.openlattice.chronicle.services.notifications.userIdentificationMayRun
import com.openlattice.chronicle.services.notifications.scheduleNotificationsWorker
import com.openlattice.chronicle.services.sync.scheduleChronicleSyncWork
import com.openlattice.chronicle.services.sync.triggerImmediateChronicleSync
import com.openlattice.chronicle.services.withdrawal.ParticipantWithdrawalManager
import com.openlattice.chronicle.ui.OverviewFragment
import com.openlattice.chronicle.ui.activeModuleAccessMissing
import com.openlattice.chronicle.ui.DataSharingFragment
import com.openlattice.chronicle.ui.SettingsHomeFragment
import com.openlattice.chronicle.utils.DeviceSettingsNavigator
import com.openlattice.chronicle.ui.UploadsFragment
import com.openlattice.chronicle.storage.LocalStoreRecoveryRequiredException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private const val FOREGROUND_SYNC_INTERVAL = 60_000L
private val EXEMPTION_DIALOG_TAGS =
    listOf("batteryExemption", "hibernationExemption", "backgroundDataRestricted", "oemBackgroundGuidance")

class MainActivity : AppCompatActivity() {

    private lateinit var enrollmentSettings: EnrollmentSettings
    private lateinit var bottomNav: BottomNavigationView

    /** Set once the authoritative off-main enrollment read admitted this dashboard. */
    private var enrollmentConfirmed = false
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

        try {
            enrollmentSettings = EnrollmentSettings(this)
        } catch (error: LocalStoreRecoveryRequiredException) {
            startActivity(LocalStoreRecoveryActivity.intent(this, error.recoveryReason))
            finish()
            return
        }

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                startActivity(Intent(Intent.ACTION_MAIN).apply {
                    addCategory(Intent.CATEGORY_HOME)
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK
                })
            }
        })

        if (ParticipantWithdrawalManager.collectionMustRemainStopped(this)) {
            ParticipantWithdrawalManager.resumePending(this)
            setupNavigation()
            selectTab(R.id.nav_settings)
            return
        }

        // On MAIN, isEnrolled() answers from the gate's published snapshot, which is false until
        // hydration after a cold start (e.g. an OEM process kill). Route from the authoritative
        // Room-backed read instead, off the main thread.
        lifecycleScope.launch {
            val enrolled = try {
                withContext(Dispatchers.IO) { enrollmentSettings.isEnrolledOrThrow() }
            } catch (error: LocalStoreRecoveryRequiredException) {
                startActivity(LocalStoreRecoveryActivity.intent(this@MainActivity, error.recoveryReason))
                finish()
                return@launch
            } catch (error: CancellationException) {
                throw error // Destroyed mid-read (e.g. rotation): the new instance decides.
            } catch (error: RuntimeException) {
                Log.e("MainActivity", "Enrollment read failed", error)
                false
            }
            if (!enrolled) {
                DeviceUnlockMonitoringService.stopService(applicationContext)
                startActivity(Intent(this@MainActivity, Enrollment::class.java).apply {
                    data = intent.data
                    action = intent.action
                })
                return@launch
            }
            enrollmentConfirmed = true

            // The read may finish after onSaveInstanceState (Home pressed mid-read); fragment
            // commits must wait until the Activity is started again.
            lifecycle.withStarted {
                requestExactAlarmPermissionIfNeeded()

                startEnrolledServices()
                setupNavigation()
                handleSelectTabExtra(intent)
                if (lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) {
                    showNextExemptionDialog()
                    resumeEnrolled()
                }
            }
        }
    }

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
        supportFragmentManager
            .beginTransaction()
            .replace(R.id.mainFragmentContainer, fragment)
            .commit()
    }

    /** Switches the bottom nav to [itemId] (used by in-page links, e.g. the Overview review card). */
    fun selectTab(itemId: Int) {
        if (::bottomNav.isInitialized) bottomNav.selectedItemId = itemId
    }

    private fun startEnrolledServices() {
        lifecycleScope.launch(Dispatchers.IO) {
            if (userIdentificationMayRun(applicationContext)) {
                DeviceUnlockMonitoringService.startAuthorizedService(applicationContext)
            } else {
                DeviceUnlockMonitoringService.stopService(applicationContext)
            }
        }
        // Notification permission is NOT a gate. Data collection (foreground-service based,
        // poll-driven) runs whether or not notifications are granted; a denied permission only
        // hides study reminders / new-data-option alerts. The optional ask now lives on the
        // acknowledgment screen after the participant agrees. Previously this finish()'d the
        // dashboard and bounced to NotificationPermissionActivity, which (a) blocked enrollment
        // on an unnecessary permission and (b) raced/stacked with the consent screen.
        scheduleChronicleSyncWork(this)
        scheduleNotificationsWorker(this)
        scheduleEnrollmentMonitoringWork(this)

        if (BuildConfig.ALLOW_RESTRICTED_RESEARCH_PERMISSIONS) {
            val sensorSettings = SensorSettings(this)
            if (sensorSettings.isEnabled()) {
                DistributionRestrictedRuntime.startHardwareSensors(this)
            }
            DistributionRestrictedRuntime.enqueueSensorSettingsRefresh(this)
            DistributionRestrictedRuntime.scheduleSensorSettingsRefresh(this)
        } else {
            // A Play update must erase stale research-only sensor configuration instead of
            // attempting to start a collector that is deliberately absent from the artifact.
            SensorSettings(this).clear()
        }
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
                NotificationManagerCompat.getEnabledListenerPackages(this).contains(packageName)
        }.getOrDefault(true)

    /** Background data off, or Data Saver on without an exemption. Fails open, like the check above. */
    private fun isBackgroundDataRestricted(): Boolean =
        runCatching {
            val connectivity = applicationContext.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            connectivity.restrictBackgroundStatus == ConnectivityManager.RESTRICT_BACKGROUND_STATUS_ENABLED
        }.getOrDefault(false)

    private fun requestExactAlarmPermissionIfNeeded() {
        if (!BuildConfig.ALLOW_RESTRICTED_RESEARCH_PERMISSIONS) return
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return
        val alarmManager = applicationContext.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        // Asked once, ever: reminders fall back to inexact alarms, and opening this settings page on
        // every launch kept the participant from reaching the app.
        val prefs = getSharedPreferences("main_activity_prefs", Context.MODE_PRIVATE)
        if (!alarmManager.canScheduleExactAlarms() && !prefs.getBoolean("exact_alarm_asked", false)) {
            prefs.edit().putBoolean("exact_alarm_asked", true).apply()
            Log.e(javaClass.name, "Exact alarm permission not granted")
            DeviceSettingsNavigator.open(this, Intent(android.provider.Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            })
        }
    }

    override fun onResume() {
        super.onResume()
        if (::enrollmentSettings.isInitialized &&
            ParticipantWithdrawalManager.collectionMustRemainStopped(this)
        ) {
            ParticipantWithdrawalManager.resumePending(this)
            return
        }
        showNextExemptionDialog()
        if (::enrollmentSettings.isInitialized && (enrollmentConfirmed || enrollmentSettings.isEnrolled())) {
            resumeEnrolled()
        }
    }

    /**
     * Shows the first background-collection prompt that applies. Runs on every resume and again once
     * the enrollment read confirms: on a cold start the first resume comes before it, so the
     * enrolled-only prompts would otherwise wait for the next resume.
     */
    private fun showNextExemptionDialog() {
        if (!::enrollmentSettings.isInitialized) return
        // A permission prompt or settings page pauses and resumes this activity; without the tag
        // check each return stacked another copy of a dialog that was still open. One dialog at a
        // time; the next one in the chain shows after this one is dismissed.
        if (EXEMPTION_DIALOG_TAGS.any { supportFragmentManager.findFragmentByTag(it) != null }) return
        val enrolled = enrollmentConfirmed || enrollmentSettings.isEnrolled()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M &&
            !hasIgnoreBatteryOptimization(this) &&
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
        } else if (enrolled && isBackgroundDataRestricted() && enrollmentSettings.isBackgroundDataDialogEnabled()) {
            BackgroundDataRestrictionDialog().show(supportFragmentManager, "backgroundDataRestricted")
        } else if (enrolled &&
            OemBackgroundGuidance.matches(Build.MANUFACTURER) &&
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
        notificationPermissionLauncher.launch(android.Manifest.permission.POST_NOTIFICATIONS)
    }

    private fun maybeTriggerForegroundSync() {
        val now = System.currentTimeMillis()
        if (now - lastForegroundSyncEnqueuedAt < FOREGROUND_SYNC_INTERVAL) return
        lastForegroundSyncEnqueuedAt = now
        triggerImmediateChronicleSync(applicationContext)
    }

    private fun routeToAcknowledgmentIfPending() {
        if (ackRouteAttempted || !(enrollmentConfirmed || enrollmentSettings.isEnrolled())) return
        lifecycleScope.launch {
            val pending = withContext(Dispatchers.IO) {
                try {
                    CollectionLoopCoordinator(applicationContext).pendingAcknowledgmentSnapshot()
                } catch (e: Exception) {
                    Log.w("MainActivity", "Failed to read pending acknowledgments", e)
                    com.openlattice.chronicle.collection.state.PendingAcknowledgmentSnapshot(emptySet(), "")
                }
            }
            if (pending.modules.isNotEmpty() && !ackRouteAttempted) {
                ackRouteAttempted = true
                accessRouteAttempted = true // Data Sharing also shows the missing-access rows.
                // A module is awaiting a per-module decision — land the participant on the Data
                // Sharing tab to review it. No bulk "I agree": each module is decided individually
                // there (the old all-at-once acknowledgment screen is retired).
                selectTab(R.id.nav_data_sharing)
                return@launch
            }
            if (accessRouteAttempted) return@launch
            val accessMissing = withContext(Dispatchers.IO) {
                try {
                    activeModuleAccessMissing(applicationContext)
                } catch (e: Exception) {
                    Log.w("MainActivity", "Failed to read module access status", e)
                    false
                }
            }
            if (accessMissing && !accessRouteAttempted) {
                accessRouteAttempted = true
                // An accepted module is inert until its OS access (e.g. Usage Access, which
                // enrollment defers to Data Sharing) is granted — land there so it is requested.
                selectTab(R.id.nav_data_sharing)
            }
        }
    }

    companion object {
        /** Intent extra: a bottom-nav item id to switch to on launch (e.g. from a notification). */
        const val EXTRA_SELECT_TAB = "extra_select_tab"
    }
}
