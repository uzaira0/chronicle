package com.openlattice.chronicle

import android.app.AlarmManager
import android.app.PendingIntent
import android.os.Process
import android.os.SystemClock
import com.openlattice.chronicle.collection.state.CollectionLoopCoordinator
import com.openlattice.chronicle.collection.state.ResearchPersistenceGate

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.TextView
import androidx.activity.OnBackPressedCallback
import androidx.activity.viewModels
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.work.WorkManager
import com.google.android.material.button.MaterialButton
import com.openlattice.chronicle.collection.DistributionRestrictedRuntime
import com.openlattice.chronicle.preferences.EnrollmentSettings
import com.openlattice.chronicle.preferences.EncryptedPrefsHelper
import com.openlattice.chronicle.preferences.STUDY_ID
import com.openlattice.chronicle.preferences.PARTICIPANT_ID
import com.openlattice.chronicle.services.notifications.DeviceUnlockMonitoringService
import com.openlattice.chronicle.services.withdrawal.WithdrawalStateStore
import com.openlattice.chronicle.storage.LocalStoreRecoveryManager
import com.openlattice.chronicle.storage.LocalStoreRecoveryReason
import com.openlattice.chronicle.storage.LocalStoreResetConfirmation
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Human-confirmed recovery only; never launched by a worker or automatic retry path. */
class LocalStoreRecoveryActivity : AppCompatActivity() {
    private lateinit var reason: LocalStoreRecoveryReason
    private val reset: ResetState by viewModels()

    internal class ResetState : ViewModel() {
        var running = false
            private set
        val outcome = MutableLiveData<Result<Unit>?>()

        fun start(context: Context, reason: LocalStoreRecoveryReason) {
            if (running) return
            running = true
            val appContext = context.applicationContext
            DeviceUnlockMonitoringService.stopService(appContext)
            if (BuildConfig.ALLOW_RESTRICTED_RESEARCH_PERMISSIONS) {
                DistributionRestrictedRuntime.stopHardwareSensors(appContext)
            }
            viewModelScope.launch {
                val result = withContext(Dispatchers.IO) { runCatching { resetStore(appContext, reason) } }
                running = false
                outcome.value = result
            }
        }

        private fun resetStore(context: Context, reason: LocalStoreRecoveryReason) {
            WorkManager.getInstance(context).cancelAllWork().result.get()
            // An unreadable store, or one whose reads fail, binds the bundle to no participant; it never blocks the reset.
            val ownerScope = runCatching {
                val prefs = EncryptedPrefsHelper.getEncryptedPrefs(context)
                prefs.getString(STUDY_ID, null)?.let { study ->
                    prefs.getString(PARTICIPANT_ID, null)?.let { participant ->
                        LocalStoreRecoveryManager.scopeDigest(study, participant)
                    }
                }
            }.getOrNull()
            ResearchPersistenceGate.stop {
                CollectionLoopCoordinator(context).prepareRecoveryErasures(ownerScope)
                LocalStoreRecoveryManager.preserveAndReset(
                    context, reason,
                    LocalStoreResetConfirmation(
                        preserveEncryptedRecoveryBundle = true,
                        understandsReenrollmentRequired = true,
                    ),
                    ownerScope = ownerScope,
                )
                CollectionLoopCoordinator(context).reconcileVerifiedRecoveryErasures(ownerScope)
                EnrollmentSettings.clearForLocalStoreRecovery(context)
                WithdrawalStateStore(context).resetForReenrollment()
                ResearchPersistenceGate.resetAfterLocalStoreRecovery()
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_local_store_recovery)

        reason = intent.getStringExtra(EXTRA_REASON)
            ?.let { runCatching { LocalStoreRecoveryReason.valueOf(it) }.getOrNull() }
            ?: run {
                finish()
                return
            }
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                // Recovery cannot be bypassed into collection. The participant can leave the app
                // and return later, or complete the explicit reset flow.
                moveTaskToBack(true)
            }
        })
        findViewById<TextView>(R.id.localStoreRecoveryReason).text = reason.userMessage()
        findViewById<MaterialButton>(R.id.localStoreRecoveryReset).setOnClickListener {
            showPreservationConfirmation()
        }
        reset.outcome.observe(this) { outcome ->
            renderResetState()
            if (outcome?.isSuccess == true) {
                reset.outcome.value = null
                restartAfterVerifiedRecovery(this)
            }
        }
        renderResetState()
    }

    private fun showPreservationConfirmation() {
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.recovery_preserve_title)
            .setMessage(R.string.recovery_preserve_body)
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(R.string.continue_action) { _, _ -> showReenrollmentConfirmation() }
            .show()
    }

    private fun showReenrollmentConfirmation() {
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.recovery_reset_title)
            .setMessage(R.string.recovery_reset_body)
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(R.string.recovery_reset_confirm) { _, _ -> performRecoveryReset() }
            .show()
    }

    private fun performRecoveryReset() {
        reset.start(applicationContext, reason)
        renderResetState()
    }

    private fun renderResetState() {
        findViewById<MaterialButton>(R.id.localStoreRecoveryReset).isEnabled = !reset.running
        findViewById<View>(R.id.localStoreRecoveryProgress).visibility = if (reset.running) View.VISIBLE else View.GONE
        findViewById<TextView>(R.id.localStoreRecoveryStatus).text = when {
            reset.running -> getString(R.string.recovery_in_progress)
            reset.outcome.value?.isFailure == true -> getString(R.string.recovery_failed)
            else -> ""
        }
    }

    private fun LocalStoreRecoveryReason.userMessage(): String = getString(
        when (this) {
            LocalStoreRecoveryReason.MISSING_KEY_MATERIAL -> R.string.recovery_reason_missing_key
            LocalStoreRecoveryReason.INVALID_KEY_MATERIAL -> R.string.recovery_reason_invalid_key
            LocalStoreRecoveryReason.KEY_PERSISTENCE_FAILED -> R.string.recovery_reason_key_persist
            LocalStoreRecoveryReason.DATABASE_OPEN_FAILED -> R.string.recovery_reason_db_open
            LocalStoreRecoveryReason.LOCAL_SCHEMA_REPLACED -> R.string.recovery_reason_schema_replaced
            LocalStoreRecoveryReason.INVALID_PERSISTED_STATE -> R.string.recovery_reason_state_corrupt
        },
    )

    companion object {
        private const val EXTRA_REASON = "local_store_recovery_reason"

        fun intent(context: Context, reason: LocalStoreRecoveryReason): Intent =
            Intent(context, LocalStoreRecoveryActivity::class.java)
                .putExtra(EXTRA_REASON, reason.name)
    }
}

private const val RECOVERY_RESTART_DELAY_MS = 2_000L

internal fun restartAfterVerifiedRecovery(context: Context,
                                         terminate: () -> Unit = { Process.killProcess(Process.myPid()) }) {
    try {
        val intent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
        }
        val pending = PendingIntent.getActivity(context, 0, intent,
            PendingIntent.FLAG_CANCEL_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val alarms = checkNotNull(context.getSystemService(AlarmManager::class.java))
        alarms.set(AlarmManager.ELAPSED_REALTIME, SystemClock.elapsedRealtime() + RECOVERY_RESTART_DELAY_MS, pending)
    } finally {
        terminate()
    }
}
