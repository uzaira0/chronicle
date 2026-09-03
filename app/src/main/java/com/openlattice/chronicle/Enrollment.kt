package com.openlattice.chronicle

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.View
import android.view.inputmethod.InputMethodManager
import android.widget.ProgressBar
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.activity.result.contract.ActivityResultContracts
import com.google.android.material.button.MaterialButton
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import com.openlattice.chronicle.constants.TelemetryEvents
import com.openlattice.chronicle.preferences.*
import com.openlattice.chronicle.services.upload.UploadWorker
import com.openlattice.chronicle.storage.ChronicleDb
import com.openlattice.chronicle.storage.LocalStoreRecoveryRequiredException
import com.openlattice.chronicle.storage.SingleEnrollmentConflictException
import com.openlattice.chronicle.storage.SingleEnrollmentResolution
import com.openlattice.chronicle.storage.UploadServerEntity
import com.openlattice.chronicle.storage.resolveSingleEnrollment
import com.openlattice.chronicle.telemetry.LocalTelemetry
import com.openlattice.chronicle.api.EnrollmentPreviewResponse
import com.openlattice.chronicle.collection.AndroidDataCollectionSetting
import com.openlattice.chronicle.collection.CollectionModuleId
import com.openlattice.chronicle.collection.DistributionRestrictedRuntime
import com.openlattice.chronicle.collection.state.CollectionLoopCoordinator
import com.openlattice.chronicle.collection.state.CollectionOrientationActivity
import com.openlattice.chronicle.serialization.ChronicleJson
import com.openlattice.chronicle.utils.Utils
import java.time.OffsetDateTime
import java.util.*
import java.util.concurrent.Executors

class Enrollment : AppCompatActivity() {
    private val executor = Executors.newSingleThreadExecutor()
    private val mHandler = object : Handler(Looper.getMainLooper()) {}

    private lateinit var studyIdText: TextInputEditText
    private lateinit var participantIdText: TextInputEditText
    private lateinit var studyIdTextView: TextView
    private lateinit var participantIdTextView: TextView
    private lateinit var statusMessageText: TextView
    private lateinit var progressBar: ProgressBar
    private lateinit var submitBtn: MaterialButton
    private lateinit var doneBtn: MaterialButton
    private lateinit var studyIdTextLayout: TextInputLayout
    private lateinit var participantIdTextLayout: TextInputLayout
    private lateinit var serverUrlText: TextInputEditText
    private lateinit var serverSigningSecretText: TextInputEditText
    private lateinit var serverSigningSecretTextLayout: TextInputLayout

    // Context held across the orientation wizard launch so the result callback can enroll with
    // the very setting consent was shown for (consent-before-enroll; no second settings fetch).
    private var pendingStudyId: UUID? = null
    private var pendingParticipantId: String? = null
    private var pendingServerUrl: String? = null
    private var pendingMobileSigningSecretOverride: String? = null
    private var pendingFetched: AndroidDataCollectionSetting? = null
    private var pendingPreview: EnrollmentPreviewResponse? = null
    private var enrollmentAccessCode: String? = null
    private var pendingEnrollmentAccessCode: String? = null
    private var cancelPendingRecoveryOnDone = false

    private val orientationLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) { result -> onOrientationResult(result.resultCode, result.data) }

    private val disclosureLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) { result -> onDisclosureResult(result.resultCode) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        try {
            ChronicleDb.getInstance(applicationContext)
        } catch (error: LocalStoreRecoveryRequiredException) {
            startActivity(LocalStoreRecoveryActivity.intent(this, error.recoveryReason))
            finish()
            return
        }
        setContentView(R.layout.activity_enrollment)

        studyIdText = findViewById(R.id.studyIdText)
        participantIdText = findViewById(R.id.participantIdText)
        statusMessageText = findViewById(R.id.statusMessage)
        progressBar = findViewById(R.id.enrollmentProgress)
        submitBtn = findViewById(R.id.button)
        doneBtn = findViewById(R.id.doneButton)
        participantIdTextView = findViewById(R.id.participantIdTextView)
        studyIdTextView = findViewById(R.id.studyIdTextView)

        studyIdTextLayout = findViewById(R.id.studyIdTextLayout)
        participantIdTextLayout = findViewById(R.id.participantIdTextLayout)
        serverUrlText = findViewById(R.id.serverUrlText)
        serverSigningSecretText = findViewById(R.id.serverSigningSecretText)
        serverSigningSecretTextLayout = findViewById(R.id.serverSigningSecretTextLayout)

        if (BuildConfig.DISTRIBUTION_CHANNEL != "RESEARCH") {
            // Public distributions bootstrap with a one-time enrollment link. A global
            // signing-key override must never be participant-facing or stored on these devices.
            serverSigningSecretTextLayout.visibility = View.GONE
        }
        doneBtn.setOnClickListener {
            if (cancelPendingRecoveryOnDone) {
                cancelPendingEnrollmentRecovery()
            } else {
                handleOnClickDone()
            }
        }

        submitBtn.setOnClickListener {
            reviewThenEnroll()
        }

        // Enrollment must load the study's configuration and show its per-module consent before
        // asking for sensitive OS access. Usage Access is requested later from Data Sharing, and
        // only when an accepted, active module actually needs it.
        resumeIssuedEnrollmentOrHandleIntent(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        val detachedAccessCode = detachEnrollmentCredential(intent)
        if (pendingPreview != null || progressBar.visibility == View.VISIBLE) {
            statusMessageText.text = getString(R.string.enrollment_finish_current_first)
            statusMessageText.visibility = View.VISIBLE
            return
        }
        resumeIssuedEnrollmentOrHandleIntent(intent, detachedAccessCode)
    }

    private fun resumeIssuedEnrollmentOrHandleIntent(
        sourceIntent: Intent,
        detachedAccessCode: String? = detachEnrollmentCredential(sourceIntent),
    ) {
        val invitationOpened = sourceIntent.action == Intent.ACTION_VIEW && sourceIntent.data != null
        submitBtn.isEnabled = false
        progressBar.visibility = View.VISIBLE
        executor.execute {
            val recovery = EnrollmentRecoveryManager.resumeIfNeeded(applicationContext)
            val enrollmentAlreadyComplete = recovery == EnrollmentRecoveryResult.NONE &&
                runCatching { EnrollmentSettings(applicationContext).isEnrolled() }.getOrDefault(false)
            mHandler.post {
                progressBar.visibility = View.INVISIBLE
                when (recovery) {
                    EnrollmentRecoveryResult.NONE -> {
                        if (enrollmentAlreadyComplete) {
                            if (invitationOpened) {
                                showExistingEnrollmentRejection()
                            } else {
                                showEnrollmentSuccess()
                            }
                        } else {
                            submitBtn.isEnabled = true
                            handleIntent(sourceIntent, detachedAccessCode)
                        }
                    }
                    EnrollmentRecoveryResult.COMPLETED -> {
                        if (invitationOpened) {
                            showExistingEnrollmentRejection()
                        } else {
                            showEnrollmentSuccess()
                        }
                    }
                    EnrollmentRecoveryResult.RETRY_REQUIRED -> showIssuedEnrollmentRecoveryRetry()
                    EnrollmentRecoveryResult.PENDING_RETRY_REQUIRED ->
                        showPendingEnrollmentRecoveryRetry()
                    EnrollmentRecoveryResult.TERMINAL_FAILURE -> {
                        clearPendingEnrollmentState()
                        cancelPendingRecoveryOnDone = false
                        doneBtn.visibility = View.INVISIBLE
                        submitBtn.isEnabled = true
                        handleIntent(sourceIntent, detachedAccessCode)
                        statusMessageText.text =
                            getString(R.string.enrollment_recovery_unrecoverable)
                        statusMessageText.visibility = View.VISIBLE
                    }
                }
            }
        }
    }

    /** Removes a one-time invitation capability before this Activity can retain the Intent. */
    private fun detachEnrollmentCredential(intent: Intent): String? {
        val data = intent.data ?: return null
        val accessCode = EnrollmentLinkCredential.fromFragment(data.fragment)
        if (data.fragment != null) {
            intent.data = data.buildUpon().fragment(null).build()
        }
        return accessCode
    }

    private fun handleIntent(intent: Intent, detachedAccessCode: String?) {
        val appLinkIntent = intent
        val appLinkAction = appLinkIntent.action
        val appLinkData = appLinkIntent.data

        if (Intent.ACTION_VIEW == appLinkAction && appLinkData != null) {
            val studyId = appLinkData.getQueryParameter("studyId")?.take(36)
            val participantId = appLinkData.getQueryParameter("participantId")?.take(256)
            enrollmentAccessCode = detachedAccessCode

            // Validate studyId format from deep link before populating UI
            if (!studyId.isNullOrBlank() && !Utils.isValidUUID(studyId)) {
                Log.w(Enrollment::class.java.name, "Rejecting invalid studyId from deep link")
                statusMessageText.text = getString(R.string.invalid_study_id_format)
                statusMessageText.visibility = View.VISIBLE
                return
            }

            studyIdText.setText(studyId)
            participantIdText.setText(participantId)

            val serverUrl = appLinkData.getQueryParameter("serverUrl")
            serverUrlText.setText("")
            if (serverUrl.isNullOrBlank()) {
                enrollmentAccessCode = null
                statusMessageText.text = getString(R.string.enrollment_missing_server_url)
                statusMessageText.visibility = View.VISIBLE
                return
            }
            val trustedServerUrl = Utils.normalizeTrustedServerUrl(serverUrl)
            if (trustedServerUrl == null) {
                enrollmentAccessCode = null
                Log.e(Enrollment::class.java.name, "Rejecting untrusted server URL from deep link")
                statusMessageText.text = getString(R.string.enrollment_untrusted_server_url)
                statusMessageText.visibility = View.VISIBLE
                return
            }
            serverUrlText.setText(trustedServerUrl)
        }
    }

    private fun handleOnClickDone() {
        val intent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK
        }
        startActivity(intent)
        finish()
    }

    private fun cancelPendingEnrollmentRecovery() {
        doneBtn.isEnabled = false
        progressBar.visibility = View.VISIBLE
        executor.execute {
            val cancelled = EnrollmentRecoveryManager.cancelPendingAttempt(applicationContext)
            mHandler.post {
                progressBar.visibility = View.INVISIBLE
                doneBtn.isEnabled = true
                if (cancelled) {
                    clearPendingEnrollmentState()
                    cancelPendingRecoveryOnDone = false
                    doneBtn.visibility = View.INVISIBLE
                    submitBtn.visibility = View.VISIBLE
                    submitBtn.isEnabled = true
                    statusMessageText.text =
                        getString(R.string.enrollment_attempt_cancelled)
                } else {
                    statusMessageText.text =
                        getString(R.string.enrollment_cancel_failed)
                }
                statusMessageText.visibility = View.VISIBLE
            }
        }
    }

    private fun showPendingEnrollmentRecoveryRetry() {
        progressBar.visibility = View.INVISIBLE
        submitBtn.visibility = View.INVISIBLE
        submitBtn.isEnabled = false
        cancelPendingRecoveryOnDone = true
        doneBtn.text = getString(R.string.cancel_pending_enrollment)
        doneBtn.visibility = View.VISIBLE
        statusMessageText.text =
            getString(R.string.enrollment_pending_retry)
        statusMessageText.visibility = View.VISIBLE
    }

    private fun showIssuedEnrollmentRecoveryRetry() {
        progressBar.visibility = View.INVISIBLE
        submitBtn.visibility = View.INVISIBLE
        submitBtn.isEnabled = false
        cancelPendingRecoveryOnDone = false
        doneBtn.visibility = View.INVISIBLE
        statusMessageText.text =
            getString(R.string.enrollment_issued_retry)
        statusMessageText.visibility = View.VISIBLE
    }

    private fun showEnrollmentSuccess() {
        clearPendingEnrollmentState()
        cancelPendingRecoveryOnDone = false
        studyIdTextLayout.visibility = View.GONE
        participantIdTextLayout.visibility = View.GONE
        serverSigningSecretTextLayout.visibility = View.GONE
        submitBtn.visibility = View.GONE
        studyIdTextView.visibility = View.GONE
        participantIdTextView.visibility = View.GONE
        progressBar.visibility = View.GONE
        statusMessageText.text = getString(R.string.device_enroll_success)
        statusMessageText.visibility = View.VISIBLE
        doneBtn.text = getString(R.string.enrollment_done)
        doneBtn.visibility = View.VISIBLE
    }

    private fun showExistingEnrollmentRejection() {
        clearPendingEnrollmentState()
        cancelPendingRecoveryOnDone = false
        studyIdTextLayout.visibility = View.GONE
        participantIdTextLayout.visibility = View.GONE
        serverSigningSecretTextLayout.visibility = View.GONE
        submitBtn.visibility = View.GONE
        studyIdTextView.visibility = View.GONE
        participantIdTextView.visibility = View.GONE
        progressBar.visibility = View.GONE
        statusMessageText.text =
            getString(R.string.enrollment_existing_study_rejection)
        statusMessageText.visibility = View.VISIBLE
        doneBtn.text = getString(R.string.enrollment_done)
        doneBtn.visibility = View.VISIBLE
    }

    private fun clearPendingEnrollmentState() {
        pendingStudyId = null
        pendingParticipantId = null
        pendingServerUrl = null
        pendingMobileSigningSecretOverride = null
        pendingFetched = null
        pendingPreview = null
        enrollmentAccessCode = null
        pendingEnrollmentAccessCode = null
    }

    private fun validateInput(studyId: String, participantId: String): Boolean {

        if (studyId.isBlank()) {
            studyIdText.error = getString(R.string.invalid_study_id_blank)

        } else if (!Utils.isValidUUID(studyId)) {
            studyIdText.error = getString(R.string.invalid_study_id_format)
        }

        if (participantId.isBlank()) {
            participantIdText.error = getString(R.string.invalid_participant)
        }

        return studyIdText.error.isNullOrBlank() && participantIdText.error.isNullOrBlank()
    }

    private fun closeKeyBoard() {
        val view = this.currentFocus
        if (view != null) {
            val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
            imm.hideSoftInputFromWindow(view.windowToken, 0)
        }
    }
    /** Resolve the invitation to an authoritative disclosure before any enrollment request. */
    private fun reviewThenEnroll() {
        val studyIdStr: String = studyIdText.text.toString().trim()
        val participantId: String = participantIdText.text.toString().trim()
        if (!validateInput(studyIdStr, participantId)) return

        val studyId = try {
            UUID.fromString(studyIdStr)
        } catch (e: Exception) {
            statusMessageText.text = getString(R.string.invalid_study_id_format)
            statusMessageText.visibility = View.VISIBLE
            return
        }
        val rawServerUrl = serverUrlText.text.toString().trim()
        if (rawServerUrl.isBlank()) {
            statusMessageText.text = getString(R.string.enrollment_missing_server_url)
            statusMessageText.visibility = View.VISIBLE
            return
        }
        val serverUrl = Utils.normalizeTrustedServerUrl(rawServerUrl)
        if (serverUrl == null) {
            statusMessageText.text = getString(R.string.enrollment_untrusted_server_url)
            statusMessageText.visibility = View.VISIBLE
            return
        }
        val mobileSigningSecretOverride = serverSigningSecretText.text.toString().trim()
            .takeIf { it.isNotBlank() }
        val accessCode = enrollmentAccessCode
        if (accessCode == null) {
            statusMessageText.text = getString(R.string.enrollment_link_required)
            statusMessageText.visibility = View.VISIBLE
            return
        }

        statusMessageText.visibility = View.INVISIBLE
        submitBtn.isEnabled = false
        progressBar.visibility = View.VISIBLE
        closeKeyBoard()

        executor.execute {
            val existingEnrollment = try {
                ChronicleDb.getInstance(applicationContext).uploadServerDao().getAll()
            } catch (e: Exception) {
                Log.e(javaClass.canonicalName, "Failed to read the local enrollment slot", e)
                null
            }
            if (existingEnrollment == null) {
                mHandler.post {
                    progressBar.visibility = View.INVISIBLE
                    submitBtn.isEnabled = true
                    statusMessageText.text = getString(R.string.device_enroll_failure)
                    statusMessageText.visibility = View.VISIBLE
                }
                return@execute
            }
            if (
                resolveSingleEnrollment(
                    existingEnrollment,
                    serverUrl,
                    studyId.toString(),
                    participantId,
                ) is SingleEnrollmentResolution.Reject
            ) {
                mHandler.post {
                    progressBar.visibility = View.INVISIBLE
                    submitBtn.isEnabled = true
                    statusMessageText.text =
                        getString(R.string.enrollment_already_enrolled)
                    statusMessageText.visibility = View.VISIBLE
                }
                return@execute
            }
            // Capability-protected preview validates the invitation without consuming it. It is
            // the authority for identity, policy, and settings; URL text is never consent copy.
            val preview = try {
                UploadWorker.getChronicleStudyApi(serverUrl, mobileSigningSecretOverride)
                    .getEnrollmentPreview(studyId, participantId, accessCode)
            } catch (e: Exception) {
                Log.w(javaClass.canonicalName, "Failed to resolve the enrollment invitation", e)
                null
            }
            val previewMatchesInvitation = preview != null &&
                preview.manifest.studyId == studyId &&
                preview.manifest.participantId == participantId &&
                Utils.normalizeTrustedServerUrl(preview.manifest.serverOrigin) == serverUrl
            val fetched = preview?.manifest?.collectionSettings?.takeIf { previewMatchesInvitation }
            val plan = fetched?.let {
                try {
                    CollectionLoopCoordinator(applicationContext).consentPlanFor(it)
                } catch (e: Exception) {
                    Log.w(javaClass.canonicalName, "Failed to resolve consent plan", e)
                    null
                }
            }
            mHandler.post {
                progressBar.visibility = View.INVISIBLE
                submitBtn.isEnabled = true
                if (preview == null || fetched == null || plan == null) {
                    statusMessageText.text =
                        getString(R.string.enrollment_invitation_unverified)
                    statusMessageText.visibility = View.VISIBLE
                    return@post
                }
                // Hold the exact authenticated disclosure, then obtain an affirmative study-level
                // decision before asking for module-specific choices.
                pendingStudyId = studyId
                pendingParticipantId = participantId
                pendingServerUrl = serverUrl
                pendingMobileSigningSecretOverride = mobileSigningSecretOverride
                pendingFetched = fetched
                pendingPreview = preview
                pendingEnrollmentAccessCode = accessCode
                submitBtn.isEnabled = false
                disclosureLauncher.launch(StudyDisclosureActivity.intent(this, preview))
            }
        }
    }

    private fun onDisclosureResult(resultCode: Int) {
        val fetched = pendingFetched
        val preview = pendingPreview
        if (resultCode != RESULT_OK || fetched == null || preview == null) {
            clearPendingEnrollmentState()
            submitBtn.isEnabled = true
            statusMessageText.text = getString(R.string.enrollment_cancelled_nothing_collected)
            statusMessageText.visibility = View.VISIBLE
            return
        }
        val plan = try {
            CollectionLoopCoordinator(applicationContext).consentPlanFor(fetched)
        } catch (e: Exception) {
            Log.w(javaClass.canonicalName, "Failed to resolve consent plan", e)
            null
        }
        if (plan == null) {
            clearPendingEnrollmentState()
            submitBtn.isEnabled = true
            statusMessageText.text = getString(R.string.enrollment_consent_plan_failed)
            statusMessageText.visibility = View.VISIBLE
            return
        }
        if (plan.isEmpty) {
            completePendingEnrollment(emptySet(), emptySet())
        } else {
            orientationLauncher.launch(CollectionOrientationActivity.intent(this, plan))
        }
    }

    /**
     * Result of the orientation wizard. RESULT_OK → the participant decided every module (every
     * required one accepted); enroll with those decisions. Anything else (incl. a required
     * decline, which the wizard returns as RESULT_CANCELED) → abort, nothing enrolled.
     */
    private fun onOrientationResult(resultCode: Int, data: Intent?) {
        val studyId = pendingStudyId
        val participantId = pendingParticipantId
        val serverUrl = pendingServerUrl
        val mobileSigningSecretOverride = pendingMobileSigningSecretOverride
        val fetched = pendingFetched
        val accessCode = pendingEnrollmentAccessCode
        val preview = pendingPreview
        if (resultCode != RESULT_OK || studyId == null || participantId == null ||
            serverUrl == null || fetched == null || accessCode == null || preview == null
        ) {
            clearPendingEnrollmentState()
            submitBtn.isEnabled = true
            statusMessageText.text =
                getString(R.string.enrollment_cancelled_nothing_collected)
            statusMessageText.visibility = View.VISIBLE
            return
        }
        completePendingEnrollment(
            CollectionOrientationActivity.acceptedFrom(data),
            CollectionOrientationActivity.declinedFrom(data),
        )
    }

    private fun completePendingEnrollment(
        accepted: Set<CollectionModuleId>,
        declined: Set<CollectionModuleId>,
    ) {
        val studyId = pendingStudyId
        val participantId = pendingParticipantId
        val serverUrl = pendingServerUrl
        val accessCode = pendingEnrollmentAccessCode
        val fetched = pendingFetched
        val preview = pendingPreview
        if (studyId == null || participantId == null || serverUrl == null || accessCode == null ||
            fetched == null || preview == null
        ) {
            submitBtn.isEnabled = true
            statusMessageText.text = getString(R.string.enrollment_state_expired)
            statusMessageText.visibility = View.VISIBLE
            return
        }
        val partition = try {
            CollectionLoopCoordinator(applicationContext).enrollmentModulePartitionFor(
                fetched = fetched,
                accepted = accepted,
                declined = declined,
            )
        } catch (error: IllegalArgumentException) {
            clearPendingEnrollmentState()
            submitBtn.isEnabled = true
            statusMessageText.text =
                getString(R.string.enrollment_modules_mismatch)
            statusMessageText.visibility = View.VISIBLE
            return
        }
        doEnrollment(
            studyId,
            participantId,
            serverUrl,
            pendingMobileSigningSecretOverride,
            accessCode,
            fetched,
            preview,
            partition,
        )
    }

    /**
     * Phase 2: actually enroll. Reached only after the participant agreed in [showConsentDialog],
     * so this is the first point the server learns of the device. The modules the participant
     * consented to are activated synchronously from the [fetched] setting we already have (no
     * second network fetch that could fail and leave the device stuck awaiting acknowledgment).
     */
    private fun doEnrollment(
        studyId: UUID,
        participantId: String,
        serverUrl: String,
        mobileSigningSecretOverride: String?,
        enrollmentAccessCode: String,
        fetched: AndroidDataCollectionSetting,
        preview: EnrollmentPreviewResponse,
        partition: com.openlattice.chronicle.collection.state.EnrollmentModulePartition,
    ) {
        if (!OffsetDateTime.now().isBefore(preview.manifest.expiresAt)) {
            clearPendingEnrollmentState()
            submitBtn.visibility = View.VISIBLE
            submitBtn.isEnabled = true
            statusMessageText.text = getString(R.string.enrollment_disclosure_expired)
            statusMessageText.visibility = View.VISIBLE
            return
        }
        try {
            val deviceInstanceId = DeviceInstanceIdentity.getOrCreate(applicationContext)

            statusMessageText.visibility = View.INVISIBLE
            submitBtn.visibility = View.INVISIBLE
            progressBar.visibility = View.VISIBLE
            closeKeyBoard()

            executor.execute {
                try {
                    val serverName = preview.manifest.studyTitle.take(30)
                    val manifestJson = ChronicleJson.moshi
                        .adapter(com.openlattice.chronicle.api.MobileEnrollmentManifest::class.java)
                        .toJson(preview.manifest)
                    val enrollmentAttemptId = UUID.randomUUID().toString()
                    val proposedApiKey = generateProposedEnrollmentApiKey()
                    val sourceDeviceJson = encodePendingEnrollmentSourceDevice(
                        getDevice(deviceInstanceId),
                    )
                    try {
                        ChronicleDb.getInstance(applicationContext).uploadServerDao()
                            .reserveSingleEnrollment(
                                UploadServerEntity(
                                    name = serverName,
                                    url = serverUrl,
                                    studyId = studyId.toString(),
                                    participantId = participantId,
                                    sourceDeviceId = deviceInstanceId,
                                    mobileSigningSecretOverride = mobileSigningSecretOverride,
                                    studyDisclosureJson = manifestJson,
                                    disclosureVersion = preview.manifest.participantPolicy.version,
                                    manifestDigest = preview.manifestDigest,
                                    pendingAcceptedModuleIds =
                                        encodePendingEnrollmentModules(partition.accepted),
                                    pendingDeclinedModuleIds =
                                        encodePendingEnrollmentModules(partition.declined),
                                    pendingUnavailableModuleIds =
                                        encodePendingEnrollmentModules(partition.unavailable),
                                    pendingEnrollmentAttemptId = enrollmentAttemptId,
                                    pendingEnrollmentAccessCode = enrollmentAccessCode,
                                    pendingEnrollmentInviteExpiresAtEpochMillis =
                                        preview.manifest.expiresAt.toInstant().toEpochMilli(),
                                    pendingProposedApiKey = proposedApiKey,
                                    pendingEnrollmentSourceDeviceJson = sourceDeviceJson,
                                    enabled = false,
                                    createdAt = OffsetDateTime.now().toString(),
                                ),
                            )
                    } catch (error: SingleEnrollmentConflictException) {
                        mHandler.post {
                            clearPendingEnrollmentState()
                            progressBar.visibility = View.INVISIBLE
                            submitBtn.visibility = View.VISIBLE
                            submitBtn.isEnabled = true
                            statusMessageText.text =
                                getString(R.string.enrollment_conflict_pending)
                            statusMessageText.visibility = View.VISIBLE
                        }
                        return@execute
                    }

                    // The invitation and proposed credential are now durable in SQLCipher. Remove
                    // Activity references before the first network request; recovery owns them now.
                    clearPendingEnrollmentState()
                    when (EnrollmentRecoveryManager.resumeIfNeeded(applicationContext)) {
                        EnrollmentRecoveryResult.COMPLETED -> Unit
                        EnrollmentRecoveryResult.RETRY_REQUIRED -> {
                            mHandler.post { showIssuedEnrollmentRecoveryRetry() }
                            return@execute
                        }
                        EnrollmentRecoveryResult.PENDING_RETRY_REQUIRED -> {
                            mHandler.post { showPendingEnrollmentRecoveryRetry() }
                            return@execute
                        }
                        EnrollmentRecoveryResult.TERMINAL_FAILURE -> {
                            LocalTelemetry.logEvent(TelemetryEvents.ENROLLMENT_FAILURE, null)
                            mHandler.post {
                                progressBar.visibility = View.INVISIBLE
                                submitBtn.visibility = View.VISIBLE
                                submitBtn.isEnabled = true
                                doneBtn.visibility = View.INVISIBLE
                                statusMessageText.text =
                                    getString(R.string.enrollment_attempt_rejected)
                                statusMessageText.visibility = View.VISIBLE
                            }
                            return@execute
                        }
                        EnrollmentRecoveryResult.NONE -> {
                            mHandler.post { showIssuedEnrollmentRecoveryRetry() }
                            return@execute
                        }
                    }

                    val sourceDeviceId = deviceInstanceId
                    val issuedApiKey = proposedApiKey
                    val studyApi = UploadWorker.getChronicleStudyApi(
                        serverUrl,
                        mobileSigningSecretOverride,
                    )
                    Log.i(javaClass.canonicalName, "Enrollment succeeded")
                    LocalTelemetry.logEvent(TelemetryEvents.ENROLLMENT_SUCCESS, null)
                    // Legacy device-wide AndroidSensor path — runs ONLY for studies that have NOT
                    // migrated to the per-sensor model. For a per-sensor study this would persist a
                    // device-wide legacy sensor blob that the resolver's legacy bridge could later
                    // use to re-enable sensors the per-sensor config omits — making a researcher's
                    // mid-study sensor removal silently no-op. Per-sensor studies are owned end-to-end
                    // by seedAndApplyDecisions + the coordinator sync below, so the legacy fetch/save
                    // is skipped for them (defence-in-depth atop CollectionSettingsResolver's
                    // per-sensor authority — see AndroidDataCollectionSetting.hasAnySensorModule).
                    if (BuildConfig.ALLOW_RESTRICTED_RESEARCH_PERMISSIONS &&
                        !fetched.hasAnySensorModule()
                    ) try {
                        val sensorSetting = studyApi.getAndroidSensorSettings(studyId)
                        val sensorSettings = SensorSettings(applicationContext)
                        sensorSettings.save(sensorSetting)
                        if (sensorSettings.isEnabled()) {
                            DistributionRestrictedRuntime.startHardwareSensors(applicationContext)
                            Log.i(javaClass.canonicalName, "Sensor collection started with ${sensorSetting.sensors.size} sensors")
                        }
                        // Report sensor availability after enrollment. Reporter swallows its
                        // own exceptions and returns false; the periodic SensorSettingsRefreshWorker
                        // will retry and persist the error on the server row.
                        if (sensorSetting.sensors.isNotEmpty()) {
                            val ok = DistributionRestrictedRuntime.reportSensorAvailability(
                                applicationContext,
                                studyId,
                                participantId,
                                sourceDeviceId,
                                issuedApiKey,
                                sensorSetting.sensors,
                                serverUrl,
                                mobileSigningSecretOverride
                            )
                            if (!ok) {
                                Log.w(javaClass.canonicalName, "Initial sensor availability report failed; will retry on schedule")
                            }
                        }
                    } catch (e: Exception) {
                        Log.w(javaClass.canonicalName, "Could not fetch sensor settings", e)
                    }

                    if (BuildConfig.ALLOW_RESTRICTED_RESEARCH_PERMISSIONS) {
                        DistributionRestrictedRuntime.scheduleSensorSettingsRefresh(applicationContext)
                    } else {
                        SensorSettings(applicationContext).clear()
                    }

                    // Persist enrollment and settle the participant's per-module decisions
                    // synchronously here on the enrollment thread — BEFORE navigating to the
                    // dashboard. The participant already decided every module in the orientation
                    // wizard, so [accepted] go straight to ACTIVE and [declined] to DECLINED, from
                    // the very [fetched] setting the wizard was shown for — NOT a second network
                    // fetch that could fail and strand the device awaiting a decision. The decision
                    // snapshot is reported to the study (trigger = ENROLLMENT). The periodic worker
                    // still picks up any module a researcher ADDS later.
                    com.openlattice.chronicle.collection.state.CollectionSettingsSyncWorker
                        .schedulePeriodic(applicationContext)
                    com.openlattice.chronicle.collection.state.CollectionSettingsSyncWorker
                        .enqueueNow(applicationContext)

                    mHandler.post {
                        showEnrollmentSuccess()
                    }
                } catch (error: Exception) {
                    Log.e(
                        javaClass.canonicalName,
                        "Enrollment setup failed safely (${error.javaClass.simpleName})",
                    )
                    mHandler.post {
                        clearPendingEnrollmentState()
                        progressBar.visibility = View.INVISIBLE
                        submitBtn.visibility = View.VISIBLE
                        submitBtn.isEnabled = true
                        doneBtn.visibility = View.INVISIBLE
                        statusMessageText.text = getString(R.string.device_enroll_failure)
                        statusMessageText.visibility = View.VISIBLE
                    }
                }
            }
        } catch (error: Exception) {
            statusMessageText.text = getString(R.string.device_enroll_failure)
            statusMessageText.visibility = View.VISIBLE
            submitBtn.visibility = View.VISIBLE
            doneBtn.visibility = View.INVISIBLE
            Log.e(javaClass.canonicalName, "Unable to prepare enrollment (${error.javaClass.simpleName})")
        }
    }
}

internal fun enrollmentCredentialMeetsDistributionContract(
    distributionChannel: String,
    apiKey: String?,
): Boolean = distributionChannel == "RESEARCH" || !apiKey.isNullOrBlank()
