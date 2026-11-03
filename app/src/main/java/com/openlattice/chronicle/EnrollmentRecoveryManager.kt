package com.openlattice.chronicle

import com.openlattice.chronicle.security.CallDeadline
import com.openlattice.chronicle.security.InvitedServerCa

import android.content.Context
import android.util.Log
import com.openlattice.chronicle.api.MobileEnrollmentManifest
import com.openlattice.chronicle.collection.CollectionModuleId
import com.openlattice.chronicle.collection.state.CollectionAckRetryQueue
import com.openlattice.chronicle.collection.state.CollectionLoopCoordinator
import com.openlattice.chronicle.collection.state.ResearchErasureFence
import com.openlattice.chronicle.collection.state.ResearchPersistenceGate
import com.openlattice.chronicle.collection.state.eraseResearchSourceState
import com.openlattice.chronicle.collection.state.validateEnrollmentModulePartition
import com.openlattice.chronicle.generated.EnrollmentAttemptContracts
import com.openlattice.chronicle.serialization.ChronicleJson
import com.openlattice.chronicle.services.upload.LocalUploadDiagnosticsStore
import com.openlattice.chronicle.services.upload.UNPROCESSABLE_PAYLOAD_STATUS
import com.openlattice.chronicle.services.upload.UploadWorker
import com.openlattice.chronicle.services.upload.serverErrorCode
import com.openlattice.chronicle.services.upload.uploadHttpStatus
import com.openlattice.chronicle.services.withdrawal.WithdrawalState
import com.openlattice.chronicle.services.withdrawal.WithdrawalStateStore
import com.openlattice.chronicle.services.withdrawal.isPermanentWithdrawalRefusal
import com.openlattice.chronicle.storage.ChronicleDb
import com.openlattice.chronicle.storage.SingleEnrollmentReservation
import com.openlattice.chronicle.storage.UploadServerDao
import com.openlattice.chronicle.storage.UploadServerEntity
import com.openlattice.chronicle.storage.hasRecoverableIssuedEnrollment
import com.openlattice.chronicle.utils.Utils
import java.net.HttpURLConnection
import java.util.UUID

internal enum class EnrollmentRecoveryResult {
    NONE,
    COMPLETED,
    RETRY_REQUIRED,
    STATUS_UNAVAILABLE,
    PENDING_RETRY_REQUIRED,
    TERMINAL_FAILURE,

    /** The study team (or this phone) cancelled the attempt; the participant was never withdrawn. */
    CANCELLED,

    /** The study changed while the participant reviewed it; the same link opens the changed study. */
    STUDY_CHANGED,

    /** The server cannot serve this app version's request (an API contract mismatch); only the study team can fix it. */
    SERVER_INCOMPATIBLE,

    /** Another final refusal, such as 403: contact the study team. */
    SERVER_REFUSED,
}

/** Statuses a server answers when it cannot serve the request this app sent at all: the two versions disagree. */
private val CONTRACT_MISMATCH_STATUSES = setOf(
    HttpURLConnection.HTTP_BAD_REQUEST, HttpURLConnection.HTTP_NOT_FOUND, HttpURLConnection.HTTP_BAD_METHOD,
    HttpURLConnection.HTTP_GONE, HttpURLConnection.HTTP_UNSUPPORTED_TYPE, UNPROCESSABLE_PAYLOAD_STATUS,
)
private const val HTTP_TOO_MANY_REQUESTS = 429

/**
 * The one classification of a failed enrollment request, the preview and the enroll replay alike. Null when a retry may
 * succeed: no reply, a timeout, 408, an uncoded 409, 429 or 5xx. Otherwise the server's final answer: its coded
 * refusals (cancelled, study changed), 401 (the invitation or attempt was rejected or has expired), a contract
 * mismatch between app and server versions, or another refusal only the study team can resolve.
 */
internal fun enrollmentRefusal(error: Throwable): EnrollmentRecoveryResult? {
    val status = uploadHttpStatus(error)?.takeIf {
        it in 400..499 && it != HttpURLConnection.HTTP_CLIENT_TIMEOUT && it != HTTP_TOO_MANY_REQUESTS
    } ?: return null
    return when (serverErrorCode(error)) {
        EnrollmentAttemptContracts.ENROLLMENT_CANCELLED_CODE -> EnrollmentRecoveryResult.CANCELLED
        EnrollmentAttemptContracts.ENROLLMENT_STUDY_CHANGED_CODE -> EnrollmentRecoveryResult.STUDY_CHANGED
        else -> when (status) {
            // Configuration can be temporarily unavailable while the study is edited; keep the exact attempt.
            HttpURLConnection.HTTP_CONFLICT -> null
            HttpURLConnection.HTTP_UNAUTHORIZED -> EnrollmentRecoveryResult.TERMINAL_FAILURE
            in CONTRACT_MISMATCH_STATUSES -> EnrollmentRecoveryResult.SERVER_INCOMPATIBLE
            else -> EnrollmentRecoveryResult.SERVER_REFUSED
        }
    }
}

internal fun encodePendingEnrollmentModules(modules: Set<CollectionModuleId>): String =
    modules.map { it.id }.sorted().joinToString("\n")

internal fun decodePendingEnrollmentModules(encoded: String?): Set<CollectionModuleId>? {
    if (encoded == null) return null
    if (encoded.isEmpty()) return emptySet()
    val decoded = linkedSetOf<CollectionModuleId>()
    encoded.lineSequence().forEach { id ->
        val module = CollectionModuleId.fromIdOrNull(id) ?: return null
        decoded += module
    }
    return decoded
}

private const val TAG = "EnrollmentRecovery"

/**
 * Reconciles both halves of enrollment after process death. Pending request capabilities and the
 * proposed device credential live only in SQLCipher; every retry reuses the exact bound request.
 */
internal object EnrollmentRecoveryManager {
    fun resumeIfNeeded(context: Context): EnrollmentRecoveryResult {
        val appContext = context.applicationContext
        var issuedEnrollmentFound = false
        return try {
            val dao = ChronicleDb.getInstance(appContext).uploadServerDao()
            val issued = dao.getRecoverableIssuedEnrollment()
            if (issued != null) {
                issuedEnrollmentFound = issued.hasRecoverableIssuedEnrollment()
                return recoverIssuedEnrollment(appContext, dao, issued)
            }
            val pending = dao.getRecoverablePendingEnrollment()
            if (pending != null) return recoverPendingEnrollment(appContext, dao, pending)
            // No enrollment owns a root that an earlier process stored: its invitation ended with that process.
            if (dao.getConfiguredServer() == null) InvitedServerCa.clear(appContext, InvitedServerCa.INHERITED_TRUST_GENERATION)
            if (dao.getEnabledServer() != null) EnrolledWork.reconcile(appContext)
            // An enrollment the study team cancelled after its key installed is shown as cancelled until the next.
            if (WithdrawalStateStore(appContext).state() == WithdrawalState.CANCELLED) EnrollmentRecoveryResult.CANCELLED
            else EnrollmentRecoveryResult.NONE
        } catch (error: Exception) {
            // Do not attach the exception: network/serialization failures can retain request data.
            Log.e(TAG, "Enrollment recovery failed closed (${error.javaClass.simpleName})")
            if (issuedEnrollmentFound || enrollmentAttempt {
                ChronicleDb.getInstance(appContext).uploadServerDao().getRecoverableIssuedEnrollment()?.hasRecoverableIssuedEnrollment() == true
            }.getOrDefault(false)) EnrollmentRecoveryResult.RETRY_REQUIRED
            else EnrollmentRecoveryResult.STATUS_UNAVAILABLE
        }
    }

    /** Sent attempts retain their credential until the server acknowledges withdrawal. */
    fun cancelPendingAttempt(context: Context): Boolean {
        val appContext = context.applicationContext
        return try {
            val dao = ChronicleDb.getInstance(appContext).uploadServerDao()
            if (
                dao.getRecoverableIssuedEnrollment() != null ||
                dao.getEnabledServer() != null
            ) return false
            val row = dao.getRecoverablePendingEnrollment() ?: return true
            val pending = validateRecoveryState(row)?.let { pendingRequest(row) }
            if (pending == null) {
                ResearchPersistenceGate.enrollmentMutation(context) {
                    InvitedServerCa.clearAfterPendingRemoval(context) { dao.deleteCorruptPendingEnrollment(row.id) == 1 }
                }
            } else {
                deleteExactPendingAttempt(context, dao, pending)
            }
        } catch (error: Exception) {
            Log.e(TAG, "Pending enrollment cancellation failed (${error.javaClass.simpleName})")
            false
        }
    }

}

private fun recoverPendingEnrollment(
    context: Context,
    dao: UploadServerDao,
    row: UploadServerEntity,
): EnrollmentRecoveryResult {
    val recovery = validateRecoveryState(row)
    val pending = recovery?.let { pendingRequest(row) }
    if (pending == null) {
        return if (ResearchPersistenceGate.enrollmentMutation(context) {
            InvitedServerCa.clearAfterPendingRemoval(context) { dao.deleteCorruptPendingEnrollment(row.id) == 1 }
        }) {
            EnrollmentRecoveryResult.TERMINAL_FAILURE
        } else {
            EnrollmentRecoveryResult.PENDING_RETRY_REQUIRED
        }
    }

    return replayEnrollmentRequest(context, dao, row, recovery, pending)
}

/**
 * The server alone decides whether this attempt is still live (one clock): the invitation's expiry before
 * the first request, its replay window after. Its final refusal ([enrollmentRefusal]) ends the attempt; every failure a
 * retry may pass keeps it for an exact replay, whatever this phone's clock reads. A refusal other than 401 or a coded one
 * comes before the server binds anything, so when it answers this attempt's only request there is nothing to withdraw:
 * the attempt is deleted without asking a server that just refused this app's request to accept another.
 */
private fun replayEnrollmentRequest(
    context: Context,
    dao: UploadServerDao,
    row: UploadServerEntity,
    recovery: RecoveryState,
    pending: PendingEnrollmentReplayRequest,
): EnrollmentRecoveryResult {
    val firstRequest = pending.firstRequestAtEpochMillis == null
    val sourceDevice = decodePendingEnrollmentSourceDevice(pending) ?: return cleanupPendingEnrollment(context, dao, pending)
    val request = if (firstRequest) pending.copy(firstRequestAtEpochMillis = System.currentTimeMillis()) else pending
    // Send only after the "may have reached the server" mark is durable; a concurrent writer is reloaded on retry.
    if (firstRequest && dao.markPendingEnrollmentRequestStarted(
            id = request.serverId,
            ownerNonce = request.ownerNonce,
            enrollmentAttemptId = request.attemptId,
            firstRequestAtEpochMillis = requireNotNull(request.firstRequestAtEpochMillis),
        ) != 1
    ) return EnrollmentRecoveryResult.PENDING_RETRY_REQUIRED
    val response = enrollmentAttempt { CallDeadline.within(CallDeadline.INTERACTIVE_TIMEOUT_MS) {
        UploadWorker.getChronicleStudyApi(request.serverUrl).enroll(
            UUID.fromString(request.studyId),
            request.participantId,
            request.sourceRandomDeviceId,
            sourceDevice,
            request.accessCode,
            request.manifestDigest,
            request.attemptId,
            request.proposedApiKey,
        )
    } }
    return response.fold(
        onSuccess = { issued ->
            if (issued.apiKey == request.proposedApiKey) {
                promotePendingEnrollment(context, dao, row, recovery, request, issued.hardStopGeneration)
            } else {
                cleanupPendingEnrollment(context, dao, request)
            }
        },
        onFailure = { error ->
            val status = uploadHttpStatus(error)
            val ended = enrollmentRefusal(error)
            if (ended != null) {
                Log.w(TAG, "Enrollment refused (HTTP $status): $ended")
                val neverBound = firstRequest && ended in REFUSED_BEFORE_BINDING
                cleanupPendingEnrollment(context, dao, request, ended, withdraw = !neverBound)
            } else {
                Log.w(TAG, "Enrollment replay will retry (HTTP $status, ${error.javaClass.simpleName})")
                EnrollmentRecoveryResult.PENDING_RETRY_REQUIRED
            }
        },
    )
}

private fun promotePendingEnrollment(
    context: Context,
    dao: UploadServerDao,
    row: UploadServerEntity,
    recovery: RecoveryState,
    request: PendingEnrollmentReplayRequest,
    hardStopGeneration: Long?,
): EnrollmentRecoveryResult {
    val persisted = enrollmentAttempt {
        ResearchPersistenceGate.enrollmentMutation(context) {
            ResearchErasureFence(context).recordEnrollmentHardStop(request.ownerNonce, hardStopGeneration)
            dao.persistIssuedEnrollment(
                reservation = SingleEnrollmentReservation(request.serverId, provisional = true, ownerNonce = request.ownerNonce),
                enrollmentAttemptId = request.attemptId,
                server = row.copy(
                    sourceRandomDeviceId = request.sourceRandomDeviceId,
                    apiKey = request.proposedApiKey,
                    manifestDigest = request.manifestDigest,
                    pendingAcceptedModuleIds = encodePendingEnrollmentModules(recovery.accepted),
                    pendingDeclinedModuleIds = encodePendingEnrollmentModules(recovery.declined),
                    pendingUnavailableModuleIds = encodePendingEnrollmentModules(recovery.unavailable),
                ),
                issuedAtEpochMillis = System.currentTimeMillis(),
            )
        }
    }.getOrElse { error ->
        // Exact request state remains intact, including after remote success + local I/O failure.
        Log.e(TAG, "Issued credential persistence will retry (${error.javaClass.simpleName})")
        return recoverCommittedIssuedEnrollmentIfPresent(context, dao, request)
            ?: EnrollmentRecoveryResult.PENDING_RETRY_REQUIRED
    }
    if (persisted != 1) {
        return recoverCommittedIssuedEnrollmentIfPresent(context, dao, request)
            ?: EnrollmentRecoveryResult.PENDING_RETRY_REQUIRED
    }

    val issued = dao.getById(request.serverId) ?: return EnrollmentRecoveryResult.RETRY_REQUIRED
    return recoverIssuedEnrollment(context, dao, issued)
}

/** Handles an SQLite commit whose success became uncertain before the caller observed it. */
private fun recoverCommittedIssuedEnrollmentIfPresent(
    context: Context,
    dao: UploadServerDao,
    pending: PendingEnrollmentReplayRequest,
): EnrollmentRecoveryResult? {
    val issued = dao.getById(pending.serverId) ?: return null
    if (
        issued.enrollmentIssuedAtEpochMillis == null ||
        issued.apiKey != pending.proposedApiKey ||
        issued.pendingEnrollmentAttemptId != null
    ) return null
    return recoverIssuedEnrollment(context, dao, issued)
}

private fun recoverIssuedEnrollment(
    context: Context,
    dao: UploadServerDao,
    row: UploadServerEntity,
): EnrollmentRecoveryResult {
    val retryResult = if (row.hasRecoverableIssuedEnrollment()) EnrollmentRecoveryResult.RETRY_REQUIRED
        else EnrollmentRecoveryResult.STATUS_UNAVAILABLE
    if (!row.hasRecoverableIssuedEnrollment()) {
        Log.e(TAG, "Incomplete enrollment row is missing required issued-response state")
        return retryResult
    }
    val recovery = validateRecoveryState(row)
        ?: return retryResult
    val ownerNonce = requireNotNull(row.reservationNonce)
    ResearchPersistenceGate.stop {
        CollectionLoopCoordinator(context).replayPendingErasures()
        if (ResearchErasureFence(context).installEnrollment(row)) {
            eraseResearchSourceState(context)
            CollectionAckRetryQueue.of(context).clearForWithdrawal()
        }
        // Resolve the legacy preference while the previous enrollment identity is still
        // available. Ambiguous ownership is quarantined by the importer.
        LocalUploadDiagnosticsStore.of(context)
        check(dao.activateIssuedEnrollment(row.id, ownerNonce) == 1) {
            "Issued enrollment ownership changed before activation"
        }
        // Install a new identity after admitted writes finish. Diagnostic rows retain
        // their original enrollment scope and cannot cross into the new enrollment.
        WithdrawalStateStore(context).completeReenrollment(
            recovery.manifest.studyId,
            recovery.manifest.participantId,
        )
    }
    val locallyApplied = CollectionLoopCoordinator(context).seedAndApplyDecisions(
        recovery.manifest.studyId,
        recovery.manifest.collectionSettings,
        recovery.accepted,
        recovery.declined,
        recovery.unavailable,
        expectedEnrollment = row,
    )
    if (!locallyApplied) {
        Log.w(TAG, "Issued enrollment local reconciliation will retry")
        return retryResult
    }
    ResearchPersistenceGate.stop {
        EnrolledWork.reconcile(context)
        check(dao.completeEnrollmentSetup(row.id, ownerNonce) == 1) {
            "Issued enrollment ownership changed before setup completion"
        }
    }
    return EnrollmentRecoveryResult.COMPLETED
}

private fun pendingRequest(row: UploadServerEntity): PendingEnrollmentReplayRequest? = enrollmentAttempt {
    PendingEnrollmentReplayRequest(
        serverId = row.id,
        ownerNonce = requireNotNull(row.reservationNonce),
        serverUrl = row.url,
        studyId = row.studyId,
        participantId = row.participantId,
        sourceRandomDeviceId = row.sourceRandomDeviceId,
        sourceDeviceJson = requireNotNull(row.pendingEnrollmentSourceDeviceJson),
        manifestDigest = requireNotNull(row.manifestDigest),
        attemptId = requireNotNull(row.pendingEnrollmentAttemptId),
        accessCode = requireNotNull(row.pendingEnrollmentAccessCode),
        proposedApiKey = requireNotNull(row.pendingProposedApiKey),
        firstRequestAtEpochMillis = row.pendingEnrollmentFirstRequestAtEpochMillis,
    )
}.getOrNull()

/** Refusals the server answers before it binds an attempt (enroll's 401 and coded answers may follow a bind). */
private val REFUSED_BEFORE_BINDING = setOf(EnrollmentRecoveryResult.SERVER_INCOMPATIBLE, EnrollmentRecoveryResult.SERVER_REFUSED)

/**
 * A sent attempt ends on the server's answer: a withdrawal receipt for this exact attempt (the server revoked it), or
 * 410 (it holds no binding for the attempt, so the proposed key never installed). Any other failure keeps it. With
 * [withdraw] false the server already said it holds no binding (it refused the attempt's only request before binding).
 */
private fun deleteExactPendingAttempt(
    context: Context,
    dao: UploadServerDao,
    pending: PendingEnrollmentReplayRequest,
    withdraw: Boolean = pending.firstRequestAtEpochMillis != null,
): Boolean {
    if (withdraw) {
        enrollmentAttempt {
            val receipt = CallDeadline.within(CallDeadline.INTERACTIVE_TIMEOUT_MS) {
                UploadWorker.getChronicleStudyApi(pending.serverUrl).withdrawCurrentEnrollment(
                    pending.sourceRandomDeviceId, pending.proposedApiKey, pending.attemptId)
            }
            check(receipt.requestId == UUID.fromString(pending.attemptId)) {
                "Withdrawal did not acknowledge this enrollment attempt"
            }
        }.onFailure { error -> if (!isPermanentWithdrawalRefusal(error)) throw error }
    }
    return ResearchPersistenceGate.enrollmentMutation(context) {
        InvitedServerCa.clearAfterPendingRemoval(context) {
            dao.deletePendingEnrollmentAttempt(
                id = pending.serverId,
                ownerNonce = pending.ownerNonce,
                enrollmentAttemptId = pending.attemptId,
                firstRequestAtEpochMillis = pending.firstRequestAtEpochMillis,
            ) == 1
        }
    }
}

private fun validateRecoveryState(row: UploadServerEntity): RecoveryState? = enrollmentAttempt {
    val manifest = requireNotNull(ChronicleJson.moshi.adapter(MobileEnrollmentManifest::class.java)
        .fromJson(requireNotNull(row.studyDisclosureJson)))
    val normalizedOrigin = requireNotNull(Utils.normalizeTrustedServerUrl(manifest.serverOrigin))
    val sameParticipant = manifest.studyId.toString() == row.studyId && manifest.participantId == row.participantId
    require(normalizedOrigin == row.url && sameParticipant && manifest.participantPolicy.version == row.disclosureVersion)
    val accepted = requireNotNull(decodePendingEnrollmentModules(row.pendingAcceptedModuleIds))
    val declined = requireNotNull(decodePendingEnrollmentModules(row.pendingDeclinedModuleIds))
    val unavailable = requireNotNull(decodePendingEnrollmentModules(row.pendingUnavailableModuleIds))
    val partition = validateEnrollmentModulePartition(manifest.collectionSettings, accepted, declined, unavailable)
    RecoveryState(manifest = manifest, accepted = partition.accepted, declined = partition.declined, unavailable = partition.unavailable)
}.getOrNull()

private data class RecoveryState(
    val manifest: MobileEnrollmentManifest,
    val accepted: Set<CollectionModuleId>,
    val declined: Set<CollectionModuleId>,
    val unavailable: Set<CollectionModuleId>,
)

private fun cleanupPendingEnrollment(
    context: Context,
    dao: UploadServerDao,
    request: PendingEnrollmentReplayRequest,
    ended: EnrollmentRecoveryResult = EnrollmentRecoveryResult.TERMINAL_FAILURE,
    withdraw: Boolean = request.firstRequestAtEpochMillis != null,
): EnrollmentRecoveryResult =
    if (enrollmentAttempt { deleteExactPendingAttempt(context, dao, request, withdraw) }.getOrDefault(false)) {
        ended
    } else {
        EnrollmentRecoveryResult.PENDING_RETRY_REQUIRED
    }
