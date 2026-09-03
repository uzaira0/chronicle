package com.openlattice.chronicle

import android.content.Context
import android.util.Log
import com.openlattice.chronicle.api.MobileEnrollmentManifest
import com.openlattice.chronicle.collection.CollectionModuleId
import com.openlattice.chronicle.collection.state.CollectionLoopCoordinator
import com.openlattice.chronicle.collection.state.ResearchPersistenceGate
import com.openlattice.chronicle.serialization.ChronicleJson
import com.openlattice.chronicle.serialization.ChronicleCallException
import com.openlattice.chronicle.services.upload.UploadWorker
import com.openlattice.chronicle.services.withdrawal.WithdrawalStateStore
import com.openlattice.chronicle.services.upload.LocalUploadDiagnosticsStore
import com.openlattice.chronicle.collection.state.CollectionAckRetryQueue
import com.openlattice.chronicle.storage.AUTH_MODE_API_KEY
import com.openlattice.chronicle.storage.ChronicleDb
import com.openlattice.chronicle.storage.UploadServerDao
import com.openlattice.chronicle.storage.UploadServerEntity
import com.openlattice.chronicle.storage.hasRecoverableIssuedEnrollment
import com.openlattice.chronicle.utils.Utils
import retrofit2.HttpException
import java.util.UUID

internal enum class EnrollmentRecoveryResult {
    NONE,
    COMPLETED,
    RETRY_REQUIRED,
    PENDING_RETRY_REQUIRED,
    TERMINAL_FAILURE,
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

/**
 * Reconciles both halves of enrollment after process death. Pending request capabilities and the
 * proposed device credential live only in SQLCipher; every retry reuses the exact bound request.
 */
internal object EnrollmentRecoveryManager {
    private const val TAG = "EnrollmentRecovery"

    fun resumeIfNeeded(context: Context): EnrollmentRecoveryResult {
        val appContext = context.applicationContext
        return try {
            val dao = ChronicleDb.getInstance(appContext).uploadServerDao()
            val issued = dao.getRecoverableIssuedEnrollment()
            if (issued != null) {
                recoverIssuedEnrollment(appContext, dao, issued)
            } else {
                val pending = dao.getRecoverablePendingEnrollment()
                    ?: return EnrollmentRecoveryResult.NONE
                recoverPendingEnrollment(appContext, dao, pending)
            }
        } catch (error: Exception) {
            // Do not attach the exception: network/serialization failures can retain request data.
            Log.e(TAG, "Enrollment recovery failed closed (${error.javaClass.simpleName})")
            EnrollmentRecoveryResult.RETRY_REQUIRED
        }
    }

    /** Explicitly abandons the exact unissued attempt and removes all of its encrypted secrets. */
    fun cancelPendingAttempt(context: Context): Boolean {
        val appContext = context.applicationContext
        return try {
            val dao = ChronicleDb.getInstance(appContext).uploadServerDao()
            if (
                dao.getRecoverableIssuedEnrollment() != null ||
                dao.getEnabledServer() != null
            ) return false
            val row = dao.getRecoverablePendingEnrollment() ?: return true
            val recovery = validateRecoveryState(row)
            val pending = recovery?.let { pendingRequest(row, it) }
            if (pending == null) {
                dao.deleteCorruptPendingEnrollment(row.id) == 1
            } else {
                deleteExactPendingAttempt(dao, pending)
            }
        } catch (error: Exception) {
            Log.e(TAG, "Pending enrollment cancellation failed (${error.javaClass.simpleName})")
            false
        }
    }

    private fun recoverPendingEnrollment(
        context: Context,
        dao: UploadServerDao,
        row: UploadServerEntity,
    ): EnrollmentRecoveryResult {
        val recovery = validateRecoveryState(row)
        val pending = recovery?.let { pendingRequest(row, it) }
        if (pending == null) {
            return if (dao.deleteCorruptPendingEnrollment(row.id) == 1) {
                EnrollmentRecoveryResult.TERMINAL_FAILURE
            } else {
                EnrollmentRecoveryResult.PENDING_RETRY_REQUIRED
            }
        }

        val plan = planEnrollmentReplay(pending, System.currentTimeMillis())
        if (plan is EnrollmentReplayPlan.Cleanup) {
            return if (deleteExactPendingAttempt(dao, pending)) {
                EnrollmentRecoveryResult.TERMINAL_FAILURE
            } else {
                EnrollmentRecoveryResult.PENDING_RETRY_REQUIRED
            }
        }
        plan as EnrollmentReplayPlan.Send
        val request = plan.request
        val sourceDevice = decodePendingEnrollmentSourceDevice(request)
        if (sourceDevice == null) {
            return if (deleteExactPendingAttempt(dao, request)) {
                EnrollmentRecoveryResult.TERMINAL_FAILURE
            } else {
                EnrollmentRecoveryResult.PENDING_RETRY_REQUIRED
            }
        }
        if (plan.persistFirstRequestTiming) {
            val persisted = dao.markPendingEnrollmentRequestStarted(
                id = request.serverId,
                ownerNonce = request.ownerNonce,
                enrollmentAttemptId = request.attemptId,
                firstRequestAtEpochMillis = requireNotNull(request.firstRequestAtEpochMillis),
                replayDeadlineEpochMillis = requireNotNull(request.replayDeadlineEpochMillis),
            )
            // Never send until the replay boundary itself is durable. A concurrent process may have
            // persisted it first, in which case the next startup safely reloads the exact request.
            if (persisted != 1) return EnrollmentRecoveryResult.PENDING_RETRY_REQUIRED
        }

        val response = try {
            UploadWorker.getChronicleStudyApi(
                request.serverUrl,
                request.mobileSigningSecretOverride,
            ).enroll(
                UUID.fromString(request.studyId),
                request.participantId,
                request.sourceDeviceId,
                sourceDevice,
                request.accessCode,
                request.manifestDigest,
                request.attemptId,
                request.proposedApiKey,
            )
        } catch (error: ChronicleCallException) {
            if (enrollmentHttpFailureIsTerminal(error.code)) {
                return if (deleteExactPendingAttempt(dao, request)) {
                    EnrollmentRecoveryResult.TERMINAL_FAILURE
                } else {
                    EnrollmentRecoveryResult.PENDING_RETRY_REQUIRED
                }
            }
            Log.w(TAG, "Enrollment replay received retryable HTTP ${error.code}")
            return EnrollmentRecoveryResult.PENDING_RETRY_REQUIRED
        } catch (error: HttpException) {
            if (enrollmentHttpFailureIsTerminal(error.code())) {
                return if (deleteExactPendingAttempt(dao, request)) {
                    EnrollmentRecoveryResult.TERMINAL_FAILURE
                } else {
                    EnrollmentRecoveryResult.PENDING_RETRY_REQUIRED
                }
            }
            Log.w(TAG, "Enrollment replay received retryable HTTP ${error.code()}")
            return EnrollmentRecoveryResult.PENDING_RETRY_REQUIRED
        } catch (error: Exception) {
            Log.w(TAG, "Enrollment replay will retry (${error.javaClass.simpleName})")
            return EnrollmentRecoveryResult.PENDING_RETRY_REQUIRED
        }

        when (evaluateEnrollmentResponse(request, response.apiKey)) {
            is EnrollmentResponseDisposition.Cleanup -> {
                return if (deleteExactPendingAttempt(dao, request)) {
                    EnrollmentRecoveryResult.TERMINAL_FAILURE
                } else {
                    EnrollmentRecoveryResult.PENDING_RETRY_REQUIRED
                }
            }
            is EnrollmentResponseDisposition.Promote -> Unit
        }

        val persisted = try {
            dao.persistIssuedEnrollment(
                id = request.serverId,
                ownerNonce = request.ownerNonce,
                name = row.name,
                sourceDeviceId = request.sourceDeviceId,
                authMode = AUTH_MODE_API_KEY,
                apiKey = request.proposedApiKey,
                mobileSigningSecretOverride = request.mobileSigningSecretOverride,
                studyDisclosureJson = requireNotNull(row.studyDisclosureJson),
                disclosureVersion = requireNotNull(row.disclosureVersion),
                manifestDigest = request.manifestDigest,
                pendingAcceptedModuleIds = encodePendingEnrollmentModules(recovery.accepted),
                pendingDeclinedModuleIds = encodePendingEnrollmentModules(recovery.declined),
                pendingUnavailableModuleIds = encodePendingEnrollmentModules(recovery.unavailable),
                enrollmentAttemptId = request.attemptId,
                issuedAtEpochMillis = System.currentTimeMillis(),
            )
        } catch (error: Exception) {
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
        val legacyResearchRecovery = BuildConfig.DISTRIBUTION_CHANNEL == "RESEARCH" &&
            !row.enrollmentSetupComplete &&
            row.enrollmentIssuedAtEpochMillis != null &&
            !row.studyDisclosureJson.isNullOrBlank() &&
            !row.manifestDigest.isNullOrBlank() &&
            !row.reservationNonce.isNullOrBlank()
        if (!row.hasRecoverableIssuedEnrollment() && !legacyResearchRecovery) {
            Log.e(TAG, "Incomplete enrollment row is missing required issued-response state")
            return EnrollmentRecoveryResult.RETRY_REQUIRED
        }
        val recovery = validateRecoveryState(row)
            ?: return EnrollmentRecoveryResult.RETRY_REQUIRED
        val ownerNonce = requireNotNull(row.reservationNonce)
        ResearchPersistenceGate.stop {
            check(dao.activateIssuedEnrollment(row.id, ownerNonce) == 1) {
                "Issued enrollment ownership changed before activation"
            }
            // Install a new identity only after every previously admitted write/request has
            // finished. Old acknowledgment retries and local diagnostics are cleared in the same
            // stop boundary, so neither can cross into the new enrollment.
            CollectionAckRetryQueue.of(context).clearForWithdrawal()
            LocalUploadDiagnosticsStore.of(context).clear()
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
        )
        if (!locallyApplied) {
            Log.w(TAG, "Issued enrollment local reconciliation will retry")
            return EnrollmentRecoveryResult.RETRY_REQUIRED
        }
        check(dao.completeEnrollmentSetup(row.id, ownerNonce) == 1) {
            "Issued enrollment ownership changed before setup completion"
        }
        return EnrollmentRecoveryResult.COMPLETED
    }

    private fun pendingRequest(
        row: UploadServerEntity,
        recovery: RecoveryState,
    ): PendingEnrollmentReplayRequest? = runCatching {
        val inviteExpiry = requireNotNull(row.pendingEnrollmentInviteExpiresAtEpochMillis)
        require(inviteExpiry == recovery.manifest.expiresAt.toInstant().toEpochMilli())
        PendingEnrollmentReplayRequest(
            serverId = row.id,
            ownerNonce = requireNotNull(row.reservationNonce),
            serverUrl = row.url,
            mobileSigningSecretOverride = row.mobileSigningSecretOverride,
            studyId = row.studyId,
            participantId = row.participantId,
            sourceDeviceId = row.sourceDeviceId,
            sourceDeviceJson = requireNotNull(row.pendingEnrollmentSourceDeviceJson),
            manifestDigest = requireNotNull(row.manifestDigest),
            attemptId = requireNotNull(row.pendingEnrollmentAttemptId),
            accessCode = requireNotNull(row.pendingEnrollmentAccessCode),
            proposedApiKey = requireNotNull(row.pendingProposedApiKey),
            inviteExpiresAtEpochMillis = inviteExpiry,
            firstRequestAtEpochMillis = row.pendingEnrollmentFirstRequestAtEpochMillis,
            replayDeadlineEpochMillis = row.pendingEnrollmentReplayDeadlineEpochMillis,
        )
    }.getOrNull()

    private fun deleteExactPendingAttempt(
        dao: UploadServerDao,
        pending: PendingEnrollmentReplayRequest,
    ): Boolean = dao.deletePendingEnrollmentAttempt(
        id = pending.serverId,
        ownerNonce = pending.ownerNonce,
        enrollmentAttemptId = pending.attemptId,
    ) == 1

    private fun validateRecoveryState(row: UploadServerEntity): RecoveryState? {
        val manifestJson = row.studyDisclosureJson ?: return null
        val manifest = runCatching {
            ChronicleJson.moshi.adapter(MobileEnrollmentManifest::class.java).fromJson(manifestJson)
        }.getOrNull() ?: return null
        val normalizedOrigin = Utils.normalizeTrustedServerUrl(manifest.serverOrigin) ?: return null
        if (
            normalizedOrigin != row.url ||
            manifest.studyId.toString() != row.studyId ||
            manifest.participantId != row.participantId ||
            manifest.participantPolicy.version != row.disclosureVersion
        ) return null

        val accepted = decodePendingEnrollmentModules(row.pendingAcceptedModuleIds) ?: return null
        val declined = decodePendingEnrollmentModules(row.pendingDeclinedModuleIds) ?: return null
        // v26 rows can only recover with an empty unavailable set when accepted+declined already
        // form a complete valid partition. New v27 attempts always persist this field, even empty.
        val unavailable = if (row.pendingUnavailableModuleIds == null) {
            emptySet()
        } else {
            decodePendingEnrollmentModules(row.pendingUnavailableModuleIds) ?: return null
        }
        val partition = runCatching {
            com.openlattice.chronicle.collection.state.validateEnrollmentModulePartition(
                manifest.collectionSettings,
                accepted,
                declined,
                unavailable,
            )
        }.getOrNull() ?: return null
        return RecoveryState(
            manifest = manifest,
            accepted = partition.accepted,
            declined = partition.declined,
            unavailable = partition.unavailable,
        )
    }

    private data class RecoveryState(
        val manifest: MobileEnrollmentManifest,
        val accepted: Set<CollectionModuleId>,
        val declined: Set<CollectionModuleId>,
        val unavailable: Set<CollectionModuleId>,
    )
}
