package com.openlattice.chronicle

import android.os.Bundle
import android.util.Log
import android.view.View
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.button.MaterialButton
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.switchmaterial.SwitchMaterial
import com.google.android.material.textfield.TextInputEditText
import com.openlattice.chronicle.preferences.DeviceInstanceIdentity
import com.openlattice.chronicle.preferences.getDevice
import com.openlattice.chronicle.services.upload.UploadWorker
import com.openlattice.chronicle.services.withdrawal.ParticipantWithdrawalManager
import com.openlattice.chronicle.storage.ChronicleDb
import com.openlattice.chronicle.storage.UploadServerEntity
import com.openlattice.chronicle.storage.SingleEnrollmentReservation
import com.openlattice.chronicle.utils.Utils
import java.time.OffsetDateTime
import java.util.*
import java.util.concurrent.Executors

class ServerEnrollmentActivity : AppCompatActivity() {
    private val executor = Executors.newSingleThreadExecutor()

    private fun postIfCurrent(action: () -> Unit) {
        runOnUiThread { if (!isFinishing && !isDestroyed) action() }
    }

    override fun onDestroy() {
        executor.shutdown()
        super.onDestroy()
    }

    private fun readServer(id: Long): com.openlattice.chronicle.storage.UploadServerEntity? =
        ChronicleDb.getInstance(applicationContext).uploadServerDao().getById(id)

    private fun reportStorageFailure(error: Exception) {
        android.util.Log.e("ServerEnrollmentActivity", "Server storage unavailable", error)
        postIfCurrent {
            if (error is com.openlattice.chronicle.storage.LocalStoreRecoveryRequiredException) {
                startActivity(LocalStoreRecoveryActivity.intent(this, error.recoveryReason))
                finish()
            } else {
                statusText.setText(R.string.server_delete_failed)
                statusText.visibility = View.VISIBLE
            }
        }
    }

    private lateinit var nameText: TextInputEditText
    private lateinit var urlText: TextInputEditText
    private lateinit var studyIdText: TextInputEditText
    private lateinit var participantIdText: TextInputEditText
    private lateinit var signingSecretText: TextInputEditText
    private lateinit var statusText: TextView
    private lateinit var progressBar: ProgressBar
    private lateinit var saveBtn: MaterialButton
    private lateinit var deleteBtn: MaterialButton
    private lateinit var enabledSwitch: SwitchMaterial
    private lateinit var healthBtn: MaterialButton
    private lateinit var statsContainer: LinearLayout
    private lateinit var statsText: TextView

    private var editServerId: Long = -1

    companion object {
        const val EXTRA_SERVER_ID = "server_id"
        const val MAX_SERVERS = 1
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (BuildConfig.DISTRIBUTION_CHANNEL != "RESEARCH") {
            Toast.makeText(
                this,
                getString(R.string.server_enroll_invitation_required),
                Toast.LENGTH_LONG,
            ).show()
            finish()
            return
        }
        setContentView(R.layout.activity_server_enrollment)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)

        nameText = findViewById(R.id.serverNameText)
        urlText = findViewById(R.id.serverUrlText)
        studyIdText = findViewById(R.id.serverStudyIdText)
        participantIdText = findViewById(R.id.serverParticipantIdText)
        signingSecretText = findViewById(R.id.serverSigningSecretText)
        statusText = findViewById(R.id.serverStatusMessage)
        progressBar = findViewById(R.id.serverProgress)
        saveBtn = findViewById(R.id.serverSaveButton)
        deleteBtn = findViewById(R.id.serverDeleteButton)
        enabledSwitch = findViewById(R.id.serverEnabledSwitch)
        healthBtn = findViewById(R.id.serverHealthButton)
        statsContainer = findViewById(R.id.statsContainer)
        statsText = findViewById(R.id.statsText)

        editServerId = intent.getLongExtra(EXTRA_SERVER_ID, -1)

        if (editServerId > 0) {
            title = getString(R.string.server_edit_title)
            deleteBtn.visibility = View.VISIBLE
            loadServer(editServerId)
            loadStats(editServerId)
        } else {
            title = getString(R.string.server_add_title)
            deleteBtn.visibility = View.GONE
            statsContainer.visibility = View.GONE
        }

        saveBtn.setOnClickListener { doSave() }
        deleteBtn.setOnClickListener { doDelete() }
        healthBtn.setOnClickListener { checkServerHealth() }
    }

    private fun loadServer(id: Long) {
        executor.execute {
            val server = try { readServer(id) } catch (error: Exception) {
                reportStorageFailure(error)
                return@execute
            }
            if (server != null) {
                postIfCurrent {
                    nameText.setText(server.name)
                    urlText.setText(server.url)
                    studyIdText.setText(server.studyId)
                    participantIdText.setText(server.participantId)
                    if (!server.mobileSigningSecretOverride.isNullOrBlank()) {
                        signingSecretText.hint = getString(R.string.server_override_saved_hint)
                    }
                    enabledSwitch.isChecked = server.enabled
                }
            }
        }
    }

    private fun loadStats(serverId: Long) {
        executor.execute {
            try {
                val stats = ChronicleDb.getInstance(applicationContext).uploadStatsDao().getRecentStats(serverId, 7)
                if (stats.isNotEmpty()) {
                    val lines = stats.joinToString("\n") { stat ->
                        val date = try {
                            val ld = java.time.LocalDate.parse(stat.date)
                            ld.format(java.time.format.DateTimeFormatter.ofPattern("MMM d"))
                        } catch (e: Exception) {
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
                        statsContainer.visibility = View.VISIBLE
                        statsText.text = getString(R.string.server_upload_history, lines)
                    }
                } else {
                    postIfCurrent {
                        statsContainer.visibility = View.VISIBLE
                        statsText.text = getString(R.string.server_upload_history, getString(R.string.server_no_uploads_recorded))
                    }
                }
            } catch (e: Exception) {
                Log.w("ServerEnrollment", "Failed to load stats", e)
            }
        }
    }

    private fun doSave() {
        val name = nameText.text.toString().trim()
        val inputUrl = urlText.text.toString().trim()
        val studyId = studyIdText.text.toString().trim()
        val participantId = participantIdText.text.toString().trim()
        val enteredSigningOverride = signingSecretText.text.toString().trim()
            .takeIf { it.isNotBlank() }
        val enabled = enabledSwitch.isChecked

        // Validate
        if (name.isBlank()) { nameText.error = getString(R.string.server_name_required); return }
        if (inputUrl.isBlank()) { urlText.error = getString(R.string.server_url_required); return }
        val url = Utils.normalizeTrustedServerUrl(inputUrl)
        if (url == null) { urlText.error = getString(R.string.server_url_untrusted); return }
        if (studyId.isBlank()) { studyIdText.error = getString(R.string.server_study_id_required); return }
        if (!Utils.isValidUUID(studyId)) { studyIdText.error = getString(R.string.server_study_id_invalid); return }
        if (participantId.isBlank()) { participantIdText.error = getString(R.string.server_participant_id_required); return }

        statusText.visibility = View.INVISIBLE
        progressBar.visibility = View.VISIBLE
        saveBtn.isEnabled = false

        executor.execute {
            var reservationToRelease: SingleEnrollmentReservation? = null
            try {
                val db = ChronicleDb.getInstance(applicationContext)
                val serverDao = db.uploadServerDao()
                val existingServer = if (editServerId > 0) {
                    serverDao.getById(editServerId)
                } else {
                    null
                }
                val mobileSigningSecretOverride = enteredSigningOverride
                    ?: existingServer?.mobileSigningSecretOverride
                if (editServerId > 0 && !enabled) {
                    val rowsUpdated = com.openlattice.chronicle.collection.state.ResearchPersistenceGate.setServerEnabled(applicationContext, editServerId, false)
                    postIfCurrent {
                        progressBar.visibility = View.INVISIBLE
                        if (rowsUpdated == 0) {
                            saveBtn.isEnabled = true
                            statusText.text = getString(R.string.server_gone)
                            statusText.visibility = View.VISIBLE
                        } else {
                            statusText.text = getString(R.string.server_saved_paused)
                            statusText.visibility = View.VISIBLE
                            finish()
                        }
                    }
                    return@execute
                }
                if (editServerId <= 0 && serverDao.count() >= MAX_SERVERS) {
                    postIfCurrent {
                        progressBar.visibility = View.INVISIBLE
                        saveBtn.isEnabled = true
                        statusText.text = getString(R.string.server_already_configured)
                        statusText.visibility = View.VISIBLE
                    }
                    return@execute
                }

                if (
                    existingServer != null &&
                    (existingServer.url != url ||
                        existingServer.studyId != studyId ||
                        existingServer.participantId != participantId)
                ) {
                    postIfCurrent {
                        progressBar.visibility = View.INVISIBLE
                        saveBtn.isEnabled = true
                        statusText.text = getString(R.string.server_identity_locked)
                        statusText.visibility = View.VISIBLE
                    }
                    return@execute
                }

                // The server derives its deduped device UUID from this app-instance
                // ID. Upload endpoints expect the same ID while app data is intact.
                val studyApi = UploadWorker.getChronicleStudyApi(url, mobileSigningSecretOverride)
                val deviceInstanceId = DeviceInstanceIdentity.getOrCreate(applicationContext)
                val reservation = com.openlattice.chronicle.collection.state.ResearchPersistenceGate.enrollmentMutation(applicationContext) {
                    serverDao.reserveSingleEnrollment(
                        UploadServerEntity(
                            name = name,
                            url = url,
                            studyId = studyId,
                            participantId = participantId,
                            sourceDeviceId = deviceInstanceId,
                            mobileSigningSecretOverride = mobileSigningSecretOverride,
                            enabled = false,
                            createdAt = existingServer?.createdAt ?: OffsetDateTime.now().toString(),
                        ),
                    )
                }
                reservationToRelease = reservation
                val response = studyApi.enroll(
                    UUID.fromString(studyId), participantId, deviceInstanceId, getDevice(deviceInstanceId)
                )
                val sourceDeviceId = deviceInstanceId
                val issuedApiKey = response.apiKey
                val authMode = if (issuedApiKey != null)
                    com.openlattice.chronicle.storage.AUTH_MODE_API_KEY
                else
                    com.openlattice.chronicle.storage.AUTH_MODE_DEVICE_ID

                com.openlattice.chronicle.collection.state.ResearchPersistenceGate.enrollmentMutation(applicationContext) {
                    serverDao.finalizeSingleEnrollment(
                        reservation = reservation,
                        requestedUrl = url,
                        requestedStudyId = studyId,
                        requestedParticipantId = participantId,
                        name = name,
                        sourceDeviceId = sourceDeviceId,
                        authMode = authMode,
                        apiKey = issuedApiKey,
                        mobileSigningSecretOverride = mobileSigningSecretOverride,
                    )
                }
                reservationToRelease = null

                postIfCurrent {
                    progressBar.visibility = View.INVISIBLE
                    statusText.text = getString(if (enabled) R.string.server_saved else R.string.server_saved_paused)
                    statusText.visibility = View.VISIBLE
                    finish()
                }
            } catch (e: Exception) {
                reservationToRelease?.let { reservation ->
                    runCatching {
                        com.openlattice.chronicle.collection.state.ResearchPersistenceGate.enrollmentMutation(applicationContext) {
                            ChronicleDb.getInstance(applicationContext).uploadServerDao().releaseEnrollmentReservation(reservation)
                        }
                    }
                }
                Log.e("ServerEnrollment", "Failed to save server", e)
                postIfCurrent {
                    progressBar.visibility = View.INVISIBLE
                    saveBtn.isEnabled = true
                    statusText.text = getString(R.string.server_connect_unavailable)
                    statusText.visibility = View.VISIBLE
                }
            }
        }
    }

    private fun checkServerHealth() {
        val url = urlText.text.toString().trim()
        if (url.isBlank()) { urlText.error = getString(R.string.server_url_required); return }
        val trustedUrl = Utils.normalizeTrustedServerUrl(url)
        if (trustedUrl == null) { urlText.error = getString(R.string.server_url_untrusted); return }

        statusText.visibility = View.INVISIBLE
        progressBar.visibility = View.VISIBLE
        healthBtn.isEnabled = false

        executor.execute {
            val result = runCatching {
                val statusCode = UploadWorker.getChronicleStudyApi(trustedUrl).health().code()
                when (statusCode) {
                    in 200..299 -> getString(R.string.server_health_online)
                    in 500..599 -> getString(R.string.server_health_offline_http, statusCode)
                    else -> getString(R.string.server_health_degraded_http, statusCode)
                }
            }.getOrElse { error ->
                Log.w("ServerEnrollment", "Server health unavailable", error)
                getString(R.string.server_health_unavailable)
            }

            postIfCurrent {
                progressBar.visibility = View.INVISIBLE
                healthBtn.isEnabled = true
                statusText.text = result
                statusText.visibility = View.VISIBLE
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
        deleteBtn.isEnabled = false
        executor.execute {
            val started = ParticipantWithdrawalManager.begin(applicationContext)
            postIfCurrent {
                if (started) {
                    finish()
                } else {
                    deleteBtn.isEnabled = true
                    Toast.makeText(this, getString(R.string.server_delete_failed), Toast.LENGTH_LONG).show()
                }
            }
        }
    }
}
