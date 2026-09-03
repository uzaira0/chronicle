package com.openlattice.chronicle

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.TextView
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.work.WorkManager
import com.google.android.material.button.MaterialButton
import com.openlattice.chronicle.collection.DistributionRestrictedRuntime
import com.openlattice.chronicle.preferences.EnrollmentSettings
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

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_local_store_recovery)
        padViewForSystemBars(R.id.localStoreRecoveryRoot, top = true)

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
    }

    private fun showPreservationConfirmation() {
        AlertDialog.Builder(this)
            .setTitle(R.string.recovery_preserve_title)
            .setMessage(R.string.recovery_preserve_body)
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(R.string.continue_action) { _, _ -> showReenrollmentConfirmation() }
            .show()
    }

    private fun showReenrollmentConfirmation() {
        AlertDialog.Builder(this)
            .setTitle(R.string.recovery_reset_title)
            .setMessage(R.string.recovery_reset_body)
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(R.string.recovery_reset_confirm) { _, _ -> performRecoveryReset() }
            .show()
    }

    private fun performRecoveryReset() {
        val button = findViewById<MaterialButton>(R.id.localStoreRecoveryReset)
        val status = findViewById<TextView>(R.id.localStoreRecoveryStatus)
        val progress = findViewById<View>(R.id.localStoreRecoveryProgress)
        button.isEnabled = false
        progress.visibility = View.VISIBLE
        status.text = getString(R.string.recovery_in_progress)

        DeviceUnlockMonitoringService.stopService(applicationContext)
        if (BuildConfig.ALLOW_RESTRICTED_RESEARCH_PERMISSIONS) {
            DistributionRestrictedRuntime.stopHardwareSensors(applicationContext)
        }

        lifecycleScope.launch {
            val outcome = runCatching {
                withContext(Dispatchers.IO) {
                    // Do not copy or remove the store while a worker could still be writing it.
                    WorkManager.getInstance(applicationContext).cancelAllWork().result.get()
                    LocalStoreRecoveryManager.preserveAndReset(
                        applicationContext,
                        reason,
                        LocalStoreResetConfirmation(
                            preserveEncryptedRecoveryBundle = true,
                            understandsReenrollmentRequired = true,
                        ),
                    )
                    EnrollmentSettings.clearForLocalStoreRecovery(applicationContext)
                    WithdrawalStateStore(applicationContext).resetForReenrollment()
                }
            }
            progress.visibility = View.GONE
            outcome.onSuccess {
                startActivity(Intent(this@LocalStoreRecoveryActivity, Enrollment::class.java).apply {
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
                })
                finish()
            }.onFailure {
                status.text = getString(R.string.recovery_failed)
                button.isEnabled = true
            }
        }
    }

    private fun LocalStoreRecoveryReason.userMessage(): String = getString(
        when (this) {
            LocalStoreRecoveryReason.MISSING_KEY_MATERIAL -> R.string.recovery_reason_missing_key
            LocalStoreRecoveryReason.INVALID_KEY_MATERIAL -> R.string.recovery_reason_invalid_key
            LocalStoreRecoveryReason.KEY_PERSISTENCE_FAILED -> R.string.recovery_reason_key_persist
            LocalStoreRecoveryReason.DATABASE_OPEN_FAILED -> R.string.recovery_reason_db_open
            LocalStoreRecoveryReason.DATABASE_MIGRATION_FAILED -> R.string.recovery_reason_db_migrate
        },
    )

    companion object {
        private const val EXTRA_REASON = "local_store_recovery_reason"

        fun intent(context: Context, reason: LocalStoreRecoveryReason): Intent =
            Intent(context, LocalStoreRecoveryActivity::class.java)
                .putExtra(EXTRA_REASON, reason.name)
    }
}
