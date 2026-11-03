package com.openlattice.chronicle

import com.openlattice.chronicle.security.CallDeadline
import com.openlattice.chronicle.security.InvitedServerCa

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.StringRes
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.openlattice.chronicle.api.EnrollmentPreviewResponse
import com.openlattice.chronicle.api.MobileEnrollmentManifest
import com.openlattice.chronicle.collection.AndroidDataCollectionSetting
import com.openlattice.chronicle.collection.CollectionModuleId
import com.openlattice.chronicle.collection.state.CollectionLoopCoordinator
import com.openlattice.chronicle.collection.state.CollectionOrientationActivity
import com.openlattice.chronicle.collection.state.ConsentPlan
import com.openlattice.chronicle.collection.state.ResearchPersistenceGate
import com.openlattice.chronicle.collection.state.UnsupportedStudyModulesException
import com.openlattice.chronicle.constants.TelemetryEvents
import com.openlattice.chronicle.preferences.EnrollmentSettings
import com.openlattice.chronicle.preferences.getDevice
import com.openlattice.chronicle.serialization.ChronicleJson
import com.openlattice.chronicle.services.upload.UploadWorker
import com.openlattice.chronicle.storage.ChronicleDb
import com.openlattice.chronicle.storage.LocalStoreRecoveryRequiredException
import com.openlattice.chronicle.storage.SingleEnrollmentConflictException
import com.openlattice.chronicle.storage.SingleEnrollmentResolution
import com.openlattice.chronicle.storage.UploadServerEntity
import com.openlattice.chronicle.storage.resolveSingleEnrollment
import com.openlattice.chronicle.telemetry.LocalTelemetry
import com.openlattice.chronicle.ui.openStoreRecovery
import com.openlattice.chronicle.utils.Utils
import java.time.OffsetDateTime
import java.util.UUID
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class Enrollment(private val executor: ExecutorService = Executors.newSingleThreadExecutor()) : AppCompatActivity() {
    private val mHandler = object : Handler(Looper.getMainLooper()) {}

    private fun postIfCurrent(action: () -> Unit) {
        mHandler.post { if (!isFinishing && !isDestroyed) action() }
    }

    override fun onDestroy() {
        val durableAttempt = screen is EnrollmentScreen.Submitting || screen is EnrollmentScreen.Recovery
        if (isFinishing && ::wizard.isInitialized && !durableAttempt) {
            executor.execute {
                try {
                    val trustGeneration = wizard.invitationTrustGeneration ?: return@execute
                    ResearchPersistenceGate.enrollmentMutation(applicationContext) {
                        if (ChronicleDb.getInstance(applicationContext).uploadServerDao().getConfiguredServer() == null) {
                            InvitedServerCa.clear(applicationContext, trustGeneration)
                        }
                    }
                } catch (error: Exception) {
                    Log.e(javaClass.simpleName, "Abandoned invitation cleanup failed (${error.javaClass.simpleName})")
                }
            }
        }
        executor.shutdown()
        mHandler.removeCallbacksAndMessages(null)
        super.onDestroy()
    }

    private lateinit var form: EnrollmentForm
    private var screen: EnrollmentScreen = EnrollmentScreen.Editing()

    private fun render(state: EnrollmentScreen) {
        screen = state
        form.render(state)
    }

    /**
     * Context held across the disclosure / orientation wizard launches so the result callback can
     * enroll with the very setting consent was shown for (consent-before-enroll; no second settings
     * fetch). Held in a ViewModel so a configuration change (rotation during the wizard) keeps it;
     * memory only — the one-time credential never goes into a saved-state Bundle, disk or logs.
     */
    internal class WizardState(private val reviewDispatcher: CoroutineDispatcher = Dispatchers.IO) : ViewModel() {
        @Volatile var invitationTrustGeneration: Long? = null
        var step: WizardStep = WizardStep.Idle
        val submissionState = MutableLiveData<EnrollmentScreen?>()
        var pendingReview: EnrollmentInvitation? = null
        val reviewResult = MutableLiveData<EnrollmentReview?>()
        /** The link's credential from the moment it is stripped until handleIntent adopts it. */
        var detachedAccessCode: String? = null
        val accessCode: String?
            get() = when (val current = step) {
                WizardStep.Idle -> null
                is WizardStep.Invited -> current.accessCode
                is WizardStep.Reviewed -> current.invitation.accessCode
            }

        fun clear() {
            invitationTrustGeneration = null
            step = WizardStep.Idle
            detachedAccessCode = null
            pendingReview = null
            reviewResult.postValue(null)
        }

        fun review(context: Context, invitation: EnrollmentInvitation) {
            if (pendingReview != null) return
            pendingReview = invitation
            val appContext = context.applicationContext
            viewModelScope.launch {
                reviewResult.value = withContext(reviewDispatcher) {
                    try { resolveEnrollmentReview(appContext, invitation) }
                    catch (error: LocalStoreRecoveryRequiredException) { EnrollmentReview.StorageFailed(error) }
                }
            }
        }
    }
    private lateinit var wizard: WizardState

    private val orientationLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) { result -> onOrientationResult(result.resultCode, result.data) }

    private val disclosureLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) { result -> onDisclosureResult(result.resultCode) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        wizard = ViewModelProvider(this)[WizardState::class.java]
        // Opening the encrypted store derives its key, so it runs on the executor ahead of every
        // other enrollment task; a store that needs recovery routes there before any of them posts.
        executor.execute {
            try {
                ChronicleDb.getInstance(applicationContext)
            } catch (error: LocalStoreRecoveryRequiredException) {
                postIfCurrent { showStorageRecovery(error) }
            }
        }
        setContentView(R.layout.activity_enrollment)

        form = EnrollmentForm(this, onSubmit = { if (screen is EnrollmentScreen.Recovery) retryRecovery() else reviewThenEnroll() }, onDone = {
            if ((screen as? EnrollmentScreen.Recovery)?.kind?.canCancel == true) cancelPendingEnrollmentRecovery() else handleOnClickDone()
        })

        wizard.submissionState.observe(this) { state ->
            if (state == null) return@observe
            if (state !is EnrollmentScreen.Submitting) wizard.clear()
            render(state)
        }
        wizard.reviewResult.observe(this) { review ->
            if (review == null) return@observe
            val invitation = wizard.pendingReview ?: return@observe
            wizard.pendingReview = null
            wizard.reviewResult.value = null
            onInvitationReviewed(invitation, review)
        }

        // Enrollment must load the study's configuration and show its per-module consent before
        // asking for sensitive OS access. Usage Access is requested later from Data Sharing, and
        // only when an accepted, active module actually needs it.
        if (wizard.submissionState.value != null) {
            render(checkNotNull(wizard.submissionState.value))
        } else if (savedInstanceState != null && wizard.step != WizardStep.Idle) {
            // Configuration change: the stripped Intent no longer carries the credential, so do not
            // re-handle it. The retained invitation / wizard state stays, and the launchers deliver
            // the pending disclosure or orientation result to this new instance.
            render(if (wizard.pendingReview != null) EnrollmentScreen.Reviewing()
                else if (wizard.step is WizardStep.Reviewed) EnrollmentScreen.Reviewing(loading = false)
                else EnrollmentScreen.Editing())
        } else {
            // A recreation before handleIntent ran finds the credential already stripped.
            resumeIssuedEnrollmentOrHandleIntent(
                intent,
                detachEnrollmentCredential(intent) ?: wizard.detachedAccessCode.takeIf { savedInstanceState != null },
            )
        }
    }

    /** Coming back to a recovery screen retries it, as opening the app does. */
    override fun onRestart() {
        super.onRestart()
        if (screen is EnrollmentScreen.Recovery) retryRecovery()
    }

    private fun retryRecovery() {
        if ((screen as? EnrollmentScreen.Recovery)?.kind?.canRetry == true) {
            resumeIssuedEnrollmentOrHandleIntent(intent, wizard.detachedAccessCode)
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        val detachedAccessCode = detachEnrollmentCredential(intent)
        if (wizard.step is WizardStep.Reviewed || screen.isBusy) {
            render(screen.withMessage(R.string.enrollment_finish_current_first))
            return
        }
        resumeIssuedEnrollmentOrHandleIntent(intent, detachedAccessCode)
    }

    private fun resumeIssuedEnrollmentOrHandleIntent(
        sourceIntent: Intent,
        detachedAccessCode: String? = detachEnrollmentCredential(sourceIntent),
    ) {
        wizard.detachedAccessCode = detachedAccessCode
        val invitationOpened = sourceIntent.action == Intent.ACTION_VIEW && sourceIntent.data != null
        render(EnrollmentScreen.Reviewing(message = screen.message))
        executor.execute {
            val recovery = EnrollmentRecoveryManager.resumeIfNeeded(applicationContext)
            val enrollmentAlreadyComplete = recovery == EnrollmentRecoveryResult.NONE &&
                runCatching { EnrollmentSettings(applicationContext).hasConfiguredEnrollmentOrThrow() }.getOrDefault(false)
            val enrolled = enrollmentAlreadyComplete || recovery == EnrollmentRecoveryResult.COMPLETED
            val existingMessage = if (invitationOpened && enrolled) existingInvitationMessage(applicationContext, sourceIntent) else null
            postIfCurrent {
                when (recovery) {
                    EnrollmentRecoveryResult.NONE, EnrollmentRecoveryResult.COMPLETED -> if (enrolled) {
                        showEnrollmentResult(existingMessage ?: R.string.device_enroll_success)
                    } else {
                        render(EnrollmentScreen.Editing())
                        handleIntent(sourceIntent, detachedAccessCode)
                    }
                    EnrollmentRecoveryResult.RETRY_REQUIRED,
                    EnrollmentRecoveryResult.STATUS_UNAVAILABLE -> showIssuedEnrollmentRecoveryRetry(recovery)
                    EnrollmentRecoveryResult.PENDING_RETRY_REQUIRED ->
                        showPendingEnrollmentRecoveryRetry()
                    EnrollmentRecoveryResult.TERMINAL_FAILURE,
                    EnrollmentRecoveryResult.CANCELLED,
                    EnrollmentRecoveryResult.STUDY_CHANGED,
                    EnrollmentRecoveryResult.SERVER_INCOMPATIBLE,
                    EnrollmentRecoveryResult.SERVER_REFUSED -> {
                        wizard.clear()
                        handleIntent(sourceIntent, detachedAccessCode)
                        render(EnrollmentScreen.Editing(recoveryEndMessage(recovery) ?: R.string.enrollment_recovery_unrecoverable))
                    }
                }
            }
        }
    }

    private fun handleIntent(intent: Intent, detachedAccessCode: String?) {
        wizard.submissionState.value = null
        val uri = intent.data
        if (intent.action != Intent.ACTION_VIEW || uri == null) return
        when (val link = parseEnrollmentLink(uri.toString(), detachedAccessCode)) {
            is EnrollmentLink.Invalid -> {
                endInvitation(link.message)
            }
            is EnrollmentLink.Prefill -> {
                // Before any request: the preview fetch already needs the invited root.
                render(EnrollmentScreen.Reviewing())
                executor.execute {
                    try {
                        wizard.invitationTrustGeneration = InvitedServerCa.replace(applicationContext, link.serverUrl, link.serverCa)
                        postIfCurrent {
                            wizard.step = link.accessCode?.let(WizardStep::Invited) ?: WizardStep.Idle
                            form.prefill(link)
                            render(EnrollmentScreen.Editing(screen.message))
                        }
                    } catch (error: LocalStoreRecoveryRequiredException) {
                        postIfCurrent { showStorageRecovery(error) }
                    }
                }
            }
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
        render(EnrollmentScreen.Recovery(RecoveryKind.CANCELLING,
            screen.message ?: R.string.enrollment_pending_retry))
        executor.execute {
            val cancelled = EnrollmentRecoveryManager.cancelPendingAttempt(applicationContext)
            postIfCurrent {
                if (cancelled) {
                    wizard.clear()
                }
                render(if (cancelled) EnrollmentScreen.Editing(R.string.enrollment_attempt_cancelled)
                    else EnrollmentScreen.Recovery(RecoveryKind.CANCELLATION_FAILED))
            }
        }
    }

    private fun showPendingEnrollmentRecoveryRetry() {
        render(EnrollmentScreen.Recovery(RecoveryKind.PENDING))
    }

    private fun showIssuedEnrollmentRecoveryRetry(result: EnrollmentRecoveryResult = EnrollmentRecoveryResult.RETRY_REQUIRED) {
        render(EnrollmentScreen.Recovery(if (result == EnrollmentRecoveryResult.RETRY_REQUIRED)
            RecoveryKind.ISSUED else RecoveryKind.STATUS_UNAVAILABLE))
    }

    private fun showEnrollmentResult(message: Int) {
        wizard.clear()
        render(EnrollmentScreen.Result(message))
    }

    private fun showStorageRecovery(error: LocalStoreRecoveryRequiredException) {
        // Only a damaged store ends the attempt. A full disk keeps the wizard and its answers on a screen that is not
        // busy, so the participant can retry or re-open the invitation after freeing space.
        if (openStoreRecovery(error)) {
            wizard.clear()
            finish()
        } else {
            render(EnrollmentScreen.Editing(R.string.settings_control_storage_paused))
        }
    }

    /** Before reservation, cancelling the consent wizard ends the invitation's trust too. */
    private fun endInvitation(@StringRes message: Int) {
        val trustGeneration = wizard.invitationTrustGeneration
        wizard.clear()
        render(EnrollmentScreen.Editing(message))
        executor.execute {
            try {
                if (trustGeneration != null) InvitedServerCa.clear(applicationContext, trustGeneration)
            } catch (error: LocalStoreRecoveryRequiredException) {
                postIfCurrent { showStorageRecovery(error) }
            }
        }
    }

    /** Resolve the invitation to an authoritative disclosure before any enrollment request. */
    private fun reviewThenEnroll() {
        val invitation = form.enrollmentInvitation(wizard.accessCode) ?: return
        render(EnrollmentScreen.Reviewing())
        wizard.review(applicationContext, invitation)
    }

    private fun onInvitationReviewed(invitation: EnrollmentInvitation, review: EnrollmentReview) {
        when (review) {
            is EnrollmentReview.StorageFailed -> showStorageRecovery(review.error)
            is EnrollmentReview.Existing -> showEnrollmentResult(existingEnrollmentMessage(
                review.owner, invitation.serverUrl, invitation.studyId, invitation.participantId))
            is EnrollmentReview.Failed -> render(EnrollmentScreen.Editing(review.message))
            is EnrollmentReview.Ready -> {
                wizard.step = WizardStep.Reviewed(invitation, review.preview)
                render(EnrollmentScreen.Reviewing(loading = false))
                disclosureLauncher.launch(StudyDisclosureActivity.intent(this, review.preview))
            }
        }
    }

    private fun onDisclosureResult(resultCode: Int) {
        val reviewed = wizard.step as? WizardStep.Reviewed
        if (resultCode != RESULT_OK || reviewed == null) {
            endInvitation(R.string.enrollment_cancelled_nothing_collected)
            return
        }
        val (plan, unsupportedModules) = resolveConsentPlan(applicationContext, reviewed.preview.manifest.collectionSettings)
        if (plan == null) {
            endInvitation(if (unsupportedModules) R.string.enrollment_study_modules_unsupported
                else R.string.enrollment_consent_plan_failed)
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
        if (resultCode != RESULT_OK || wizard.step !is WizardStep.Reviewed) {
            endInvitation(R.string.enrollment_cancelled_nothing_collected)
            return
        }
        completePendingEnrollment(CollectionOrientationActivity.acceptedFrom(data), CollectionOrientationActivity.declinedFrom(data))
    }

    private fun completePendingEnrollment(accepted: Set<CollectionModuleId>, declined: Set<CollectionModuleId>) {
        val submission = try {
            (wizard.step as? WizardStep.Reviewed)?.submission(applicationContext, accepted, declined)
        } catch (error: IllegalArgumentException) {
            endInvitation(R.string.enrollment_modules_mismatch)
            return
        }
        if (submission == null) {
            endInvitation(R.string.enrollment_state_expired)
            return
        }
        doEnrollment(submission)
    }

    /** The server decides whether the invitation expired: the phone's clock may differ from its own. */
    private fun doEnrollment(submission: EnrollmentSubmission) {
        wizard.submissionState.value = EnrollmentScreen.Submitting()
        executor.execute { submitEnrollment(submission) }
    }

    private fun submitEnrollment(submission: EnrollmentSubmission) {
        var serverAccepted = false
        try {
            try {
                reserveEnrollment(applicationContext, submission)
            } catch (error: SingleEnrollmentConflictException) {
                wizard.submissionState.postValue(EnrollmentScreen.Failure(R.string.enrollment_conflict_pending))
                return
            }
            // Recovery owns the durable invitation before the first network request.
            wizard.clear()
            val recovery = EnrollmentRecoveryManager.resumeIfNeeded(applicationContext)
            if (recovery != EnrollmentRecoveryResult.COMPLETED) {
                wizard.submissionState.postValue(enrollmentRecoveryScreen(recovery))
                return
            }
            serverAccepted = true
            finishEnrollment()
            wizard.submissionState.postValue(EnrollmentScreen.Result(R.string.device_enroll_success))
        } catch (error: Exception) {
            Log.e(javaClass.canonicalName, "Enrollment setup failed safely (${error.javaClass.simpleName})")
            wizard.submissionState.postValue(EnrollmentScreen.Failure(
                if (serverAccepted) R.string.enrollment_issued_retry else R.string.device_enroll_failure))
        }
    }

    private fun enrollmentRecoveryScreen(recovery: EnrollmentRecoveryResult): EnrollmentScreen = when (recovery) {
        EnrollmentRecoveryResult.COMPLETED -> EnrollmentScreen.Result(R.string.device_enroll_success)
        EnrollmentRecoveryResult.RETRY_REQUIRED -> EnrollmentScreen.Recovery(RecoveryKind.ISSUED)
        EnrollmentRecoveryResult.STATUS_UNAVAILABLE, EnrollmentRecoveryResult.NONE -> EnrollmentScreen.Recovery(RecoveryKind.STATUS_UNAVAILABLE)
        EnrollmentRecoveryResult.PENDING_RETRY_REQUIRED -> EnrollmentScreen.Recovery(RecoveryKind.PENDING)
        EnrollmentRecoveryResult.TERMINAL_FAILURE,
        EnrollmentRecoveryResult.SERVER_INCOMPATIBLE,
        EnrollmentRecoveryResult.SERVER_REFUSED -> {
            LocalTelemetry.logEvent(TelemetryEvents.ENROLLMENT_FAILURE, null)
            EnrollmentScreen.Failure(recoveryEndMessage(recovery) ?: R.string.enrollment_attempt_rejected)
        }
        EnrollmentRecoveryResult.CANCELLED,
        EnrollmentRecoveryResult.STUDY_CHANGED -> EnrollmentScreen.Editing(recoveryEndMessage(recovery))
    }

}

/**
 * An attempt the server ended for a reason the participant can act on: a cancel, a study edit during review, or a
 * refusal only the study team can resolve (this app version and the server disagree, or the server refused outright).
 */
@StringRes
internal fun recoveryEndMessage(recovery: EnrollmentRecoveryResult): Int? = when (recovery) {
    EnrollmentRecoveryResult.CANCELLED -> R.string.enrollment_attempt_cancelled
    EnrollmentRecoveryResult.STUDY_CHANGED -> R.string.enrollment_study_changed
    EnrollmentRecoveryResult.SERVER_INCOMPATIBLE -> R.string.enrollment_server_incompatible
    EnrollmentRecoveryResult.SERVER_REFUSED -> R.string.enrollment_server_rejected
    else -> null
}

/**
 * The reservation owns the enrollment's random device id: drawn here, stored on the row, and
 * read back from it by every replay of this attempt. A cancelled or abandoned attempt, a withdrawal or a local-store
 * recovery leaves no id behind, so the next reservation draws a new one.
 */
internal fun reserveEnrollment(context: Context, submission: EnrollmentSubmission) = with(submission) {
    val randomDeviceId = UUID.randomUUID().toString()
    val serverName = preview.manifest.studyTitle.take(30)
    val manifestJson = ChronicleJson.moshi.adapter(MobileEnrollmentManifest::class.java).toJson(preview.manifest)
    val enrollmentAttemptId = UUID.randomUUID().toString()
    val proposedApiKey = generateProposedEnrollmentApiKey()
    val sourceDeviceJson = encodePendingEnrollmentSourceDevice(getDevice(randomDeviceId))
    ResearchPersistenceGate.enrollmentMutation(context) {
        ChronicleDb.getInstance(context).uploadServerDao().reserveSingleEnrollment(
            UploadServerEntity(
                name = serverName,
                url = serverUrl,
                studyId = studyId.toString(),
                participantId = participantId,
                sourceRandomDeviceId = randomDeviceId,
                studyDisclosureJson = manifestJson,
                disclosureVersion = preview.manifest.participantPolicy.version,
                manifestDigest = preview.manifestDigest,
                pendingAcceptedModuleIds = encodePendingEnrollmentModules(partition.accepted),
                pendingDeclinedModuleIds = encodePendingEnrollmentModules(partition.declined),
                pendingUnavailableModuleIds = encodePendingEnrollmentModules(partition.unavailable),
                pendingEnrollmentAttemptId = enrollmentAttemptId,
                pendingEnrollmentAccessCode = enrollmentAccessCode,
                pendingProposedApiKey = proposedApiKey,
                pendingEnrollmentSourceDeviceJson = sourceDeviceJson,
                enabled = false,
                createdAt = OffsetDateTime.now().toString(),
            ),
        ).also { InvitedServerCa.retainOrigin(context, serverUrl) }
    }
}

private fun finishEnrollment() {
    Log.i(Enrollment::class.java.simpleName, "Enrollment succeeded")
    LocalTelemetry.logEvent(TelemetryEvents.ENROLLMENT_SUCCESS, null)
}

internal sealed interface EnrollmentReview {
    data class StorageFailed(val error: LocalStoreRecoveryRequiredException) : EnrollmentReview
    data class Existing(val owner: UploadServerEntity) : EnrollmentReview
    data class Failed(val message: Int) : EnrollmentReview
    data class Ready(val preview: EnrollmentPreviewResponse) : EnrollmentReview
}

private fun resolveEnrollmentReview(context: Context, invitation: EnrollmentInvitation): EnrollmentReview {
    val existingEnrollment = enrollmentAttempt {
        ChronicleDb.getInstance(context).uploadServerDao().getAll()
    }.getOrElse { error ->
        Log.e(Enrollment::class.java.simpleName, "Failed to read the local enrollment slot (${error.javaClass.simpleName})")
        return EnrollmentReview.Failed(R.string.device_enroll_failure)
    }
    val resolution = resolveSingleEnrollment(existingEnrollment, invitation.serverUrl,
        invitation.studyId.toString(), invitation.participantId)
    if (resolution is SingleEnrollmentResolution.Reject) return EnrollmentReview.Existing(resolution.existing)
    InvitedServerCa.retainOrigin(context, invitation.serverUrl)
    return fetchEnrollmentPreview(context, invitation)
}

private fun EnrollmentPreviewResponse.matchesInvitation(invitation: EnrollmentInvitation): Boolean =
    manifest.studyId == invitation.studyId && manifest.participantId == invitation.participantId &&
        Utils.normalizeTrustedServerUrl(manifest.serverOrigin) == invitation.serverUrl

private fun fetchEnrollmentPreview(context: Context, invitation: EnrollmentInvitation): EnrollmentReview = with(invitation) {
    val outcome = requestEnrollmentPreview(invitation)
    val preview = (outcome as? EnrollmentPreviewOutcome.Verified)?.preview
    val fetched = preview?.takeIf { it.matchesInvitation(invitation) }?.manifest?.collectionSettings
    val consent = fetched?.let { resolveConsentPlan(context, it) }
    if (preview != null && fetched != null && consent?.plan != null) return@with EnrollmentReview.Ready(preview)
    EnrollmentReview.Failed(when {
        consent?.unsupportedModules == true -> R.string.enrollment_study_modules_unsupported
        outcome is EnrollmentPreviewOutcome.Failed -> outcome.message
        outcome is EnrollmentPreviewOutcome.Verified && fetched == null -> R.string.enrollment_response_unexpected
        fetched != null && consent?.plan == null -> R.string.enrollment_consent_plan_failed
        else -> R.string.enrollment_invitation_unverified
    })
}

private fun requestEnrollmentPreview(invitation: EnrollmentInvitation): EnrollmentPreviewOutcome = with(invitation) {
    // A preview validates the invitation without consuming it and owns the disclosure/settings.
    enrollmentAttempt<EnrollmentPreviewOutcome> {
        EnrollmentPreviewOutcome.Verified(CallDeadline.within(CallDeadline.INTERACTIVE_TIMEOUT_MS) {
            UploadWorker.getChronicleStudyApi(serverUrl)
                .getEnrollmentPreview(studyId, participantId, accessCode)
        })
    }.getOrElse { failure ->
        val error = failure as Exception
        Log.w(Enrollment::class.java.simpleName, "Failed to resolve the enrollment invitation (${error.javaClass.simpleName})")
        EnrollmentPreviewOutcome.Failed(enrollmentRequestFailureMessage(error))
    }
}

private data class ConsentPlanResolution(val plan: ConsentPlan?, val unsupportedModules: Boolean)

/** Builds the consent plan; a study enabling modules this build does not ship is reported separately. */
private fun resolveConsentPlan(context: Context, fetched: AndroidDataCollectionSetting): ConsentPlanResolution = enrollmentAttempt {
    ConsentPlanResolution(CollectionLoopCoordinator(context).consentPlanFor(fetched), false)
}.getOrElse { error ->
    val unsupported = error is UnsupportedStudyModulesException
    Log.w(Enrollment::class.java.simpleName, "Failed to resolve consent plan (${error.javaClass.simpleName})")
    ConsentPlanResolution(null, unsupported)
}

private fun existingEnrollmentMessage(existing: UploadServerEntity, serverUrl: String?, studyId: UUID?, participantId: String?): Int {
    val sameInvitation = existing.url == serverUrl && existing.studyId == studyId?.toString() && existing.participantId == participantId
    return if (existing.enrollmentSetupComplete && sameInvitation) {
        R.string.enrollment_already_enrolled
    } else {
        R.string.enrollment_existing_study_rejection
    }
}

/** Removes a one-time invitation capability before the Activity can retain the Intent. */
private fun detachEnrollmentCredential(intent: Intent): String? {
    val data = intent.data ?: return null
    val accessCode = EnrollmentLinkCredential.fromFragment(data.fragment)
    if (data.fragment != null) {
        intent.data = data.buildUpon().fragment(null).build()
    }
    return accessCode
}

/** Explains why a reopened invitation cannot enroll this already-enrolled device. Reads the database. */
private fun existingInvitationMessage(context: Context, sourceIntent: Intent): Int {
    val existing = runCatching {
        ChronicleDb.getInstance(context).uploadServerDao().getConfiguredServer()
    }.getOrNull() ?: return R.string.server_load_failed
    val invitation = sourceIntent.data?.let { parseEnrollmentLink(it.toString(), null) } as? EnrollmentLink.Prefill
    return existingEnrollmentMessage(
        existing, invitation?.serverUrl, invitation?.studyId, invitation?.participantId,
    )
}
