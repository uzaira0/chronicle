package com.openlattice.chronicle


import android.os.Bundle
import android.util.Log
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.openlattice.chronicle.databinding.ActivityServerEnrollmentBinding
import com.openlattice.chronicle.services.withdrawal.ParticipantWithdrawalManager
import com.openlattice.chronicle.storage.ChronicleDb
import com.openlattice.chronicle.storage.LocalStoreRecoveryRequiredException
import com.openlattice.chronicle.storage.UploadServerEntity
import com.openlattice.chronicle.ui.openStoreRecovery
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException
import java.time.format.FormatStyle
import java.util.Locale
import java.util.concurrent.Executors

class ServerEnrollmentActivity : AppCompatActivity() {
    private val executor = Executors.newSingleThreadExecutor()
    private lateinit var binding: ActivityServerEnrollmentBinding

    private fun postIfCurrent(action: () -> Unit) {
        runOnUiThread { if (!isFinishing && !isDestroyed) action() }
    }

    override fun onDestroy() {
        executor.shutdown()
        super.onDestroy()
    }

    private fun readServer(id: Long): UploadServerEntity? =
        ChronicleDb.getInstance(applicationContext).uploadServerDao().getById(id)

    private fun reportStorageFailure(error: Exception, showFailure: () -> Unit = {
        binding.serverStatusMessage.setText(R.string.server_load_failed)
        binding.serverStatusMessage.visibility = View.VISIBLE
    }) {
        Log.e(TAG, "Server storage unavailable", error)
        postIfCurrent {
            if (error is LocalStoreRecoveryRequiredException) {
                if (openStoreRecovery(error)) finish()
            } else {
                showFailure()
            }
        }
    }

    private var editServerId: Long = -1

    companion object {
        private const val TAG = "ServerEnrollment"
        const val EXTRA_SERVER_ID = "server_id"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        editServerId = intent.getLongExtra(EXTRA_SERVER_ID, -1)
        if (editServerId <= 0) {
            Toast.makeText(
                this,
                getString(R.string.server_enroll_invitation_required),
                Toast.LENGTH_LONG,
            ).show()
            finish()
            return
        }
        binding = ActivityServerEnrollmentBinding.inflate(layoutInflater)
        setContentView(binding.root)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)

        listOf(binding.serverNameText, binding.serverUrlText, binding.serverStudyIdText,
            binding.serverParticipantIdText).forEach { it.isEnabled = false }
        title = getString(R.string.server_delete)
        binding.serverDeleteButton.visibility = View.VISIBLE
        loadServer(editServerId)
        loadStats(editServerId)
        binding.serverDeleteButton.setOnClickListener { doDelete() }
    }

    private fun loadServer(id: Long) {
        executor.execute {
            val server = try { readServer(id) } catch (error: Exception) {
                reportStorageFailure(error)
                return@execute
            }
            if (server != null) {
                postIfCurrent {
                    binding.serverNameText.setText(server.name)
                    binding.serverUrlText.setText(server.url)
                    binding.serverStudyIdText.setText(server.studyId)
                    binding.serverParticipantIdText.setText(server.participantId)
                }
            }
        }
    }

    private fun loadStats(serverId: Long) {
        executor.execute {
            try {
                val stats = ChronicleDb.getInstance(applicationContext).uploadStatsDao().getRecentStats(serverId, 7)
                val lines = if (stats.isEmpty()) null else stats.joinToString("\n") { stat ->
                    val date = try {
                        uploadStatDay(LocalDate.parse(stat.date))
                    } catch (e: DateTimeParseException) {
                        stat.date
                    }
                    getString(
                        R.string.server_upload_stat_line,
                        date,
                        stat.usageEventsUploaded,
                        stat.sensorSamplesUploaded,
                        stat.batterySamplesUploaded,
                        stat.usageUploadFailures,
                        stat.sensorUploadFailures,
                        stat.batteryUploadFailures,
                    )
                }
                postIfCurrent {
                    binding.statsContainer.visibility = View.VISIBLE
                    binding.statsText.text = getString(R.string.server_upload_history,
                        lines ?: getString(R.string.server_no_uploads_recorded))
                }
            } catch (e: Exception) {
                reportStorageFailure(e) {
                    binding.statsContainer.visibility = View.VISIBLE
                    binding.statsText.setText(R.string.server_upload_history_failed)
                }
            }
        }
    }

    /**
     * Deleting the enrolled server is a withdrawal: collection stops at once, the credential and
     * local data stay until the server acknowledges deletion, and the withdrawal worker erases local
     * data only after that. Without an API key the server can never be asked to delete, so nothing
     * changes and the participant is sent to the study team.
     */
    private fun doDelete() {
        if (editServerId <= 0) return

        executor.execute {
            val server = try { readServer(editServerId) } catch (error: Exception) {
                reportStorageFailure(error)
                return@execute
            }
            postIfCurrent {
                when {
                    server == null -> finish()
                    server.apiKey.isNullOrBlank() -> Toast.makeText(
                        this, getString(R.string.server_delete_needs_support), Toast.LENGTH_LONG,
                    ).show()
                    else -> MaterialAlertDialogBuilder(this)
                        .setTitle(R.string.server_delete_confirm_title)
                        .setMessage(R.string.server_delete_confirm_message)
                        .setNegativeButton(android.R.string.cancel, null)
                        .setPositiveButton(R.string.server_delete_confirm_action) { _, _ -> beginWithdrawal() }
                        .show()
                }
            }
        }
    }

    private fun beginWithdrawal() {
        binding.serverDeleteButton.isEnabled = false
        executor.execute {
            val started = ParticipantWithdrawalManager.begin(applicationContext)
            postIfCurrent {
                if (started) {
                    finish()
                } else {
                    binding.serverDeleteButton.isEnabled = true
                    Toast.makeText(this, getString(R.string.server_delete_failed), Toast.LENGTH_LONG).show()
                }
            }
        }
    }
}

/** The phone locale's medium date, day first in Spanish ("29-10-2026" in es-CL), "Oct 29, 2026" in English. */
internal fun uploadStatDay(date: LocalDate, locale: Locale = Locale.getDefault()): String =
    date.format(DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM).withLocale(locale))
