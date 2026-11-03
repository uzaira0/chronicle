package com.openlattice.chronicle.services.withdrawal

import com.openlattice.chronicle.storage.checkLocalStoreWrite
import android.content.Context
import com.openlattice.chronicle.collection.CollectionModuleId
import com.openlattice.chronicle.services.upload.serverErrorCode
import com.openlattice.chronicle.services.upload.uploadHttpStatus
import android.content.SharedPreferences
import android.util.Log
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.Worker
import androidx.work.WorkerParameters
import androidx.core.app.NotificationManagerCompat
import androidx.lifecycle.LiveData
import com.openlattice.chronicle.BuildConfig
import com.openlattice.chronicle.R
import com.openlattice.chronicle.collection.DistributionRestrictedRuntime
import com.openlattice.chronicle.collection.state.CollectionAckRetryQueue
import com.openlattice.chronicle.collection.state.CollectionLoopCoordinator
import com.openlattice.chronicle.collection.state.ResearchPersistenceGate
import com.openlattice.chronicle.data.ParticipationStatus
import com.openlattice.chronicle.generated.EnrollmentAttemptContracts
import com.openlattice.chronicle.preferences.EncryptedPrefsHelper
import com.openlattice.chronicle.preferences.EnrollmentSettings
import com.openlattice.chronicle.preferences.INTERACTION_POLICY_SNAPSHOT_KEY
import com.openlattice.chronicle.preferences.InteractionPolicySettings
import com.openlattice.chronicle.preferences.PARTICIPANT_ID
import com.openlattice.chronicle.preferences.PARTICIPATION_STATUS
import com.openlattice.chronicle.preferences.STUDY_ID
import com.openlattice.chronicle.services.notifications.DeviceUnlockMonitoringService
import com.openlattice.chronicle.services.notifications.IDENTIFY_USER_NOTIFICATION_TAG
import com.openlattice.chronicle.services.upload.UploadWorker
import com.openlattice.chronicle.services.upload.UploadQueueSingleFlight
import com.openlattice.chronicle.services.upload.LocalUploadDiagnosticsStore
import com.openlattice.chronicle.storage.ChronicleDb
import com.openlattice.chronicle.storage.LocalStoreRecoveryManager
import com.openlattice.chronicle.storage.UploadServerEntity
import com.openlattice.chronicle.collection.state.ResearchErasureFence
import com.openlattice.chronicle.collection.state.StorageAdmission
import com.openlattice.chronicle.collection.state.eraseResearchSourceState
import com.openlattice.chronicle.preferences.INVALID_STUDY_ID
import java.net.HttpURLConnection
import java.util.UUID

internal fun isPermanentWithdrawalRefusal(error: Throwable): Boolean =
    uploadHttpStatus(error) == HttpURLConnection.HTTP_GONE

/** The server's coded 410 for an attempt the study team cancelled (key revoked); an uncoded 410 is not one. */
internal fun isEnrollmentCancelledRefusal(error: Throwable): Boolean =
    isPermanentWithdrawalRefusal(error) && serverErrorCode(error) == EnrollmentAttemptContracts.ENROLLMENT_CANCELLED_CODE

public enum class WithdrawalState {
    NONE,
    PENDING,
    COMPLETE,
    NEEDS_SUPPORT,

    /** The study team cancelled the attempt after its key installed; nothing was withdrawn. */
    CANCELLED;

    /** COMPLETE permits reenrollment; NEEDS_SUPPORT keeps Settings open for unresolved server deletion. */
    public val requiresResolution: Boolean
        get() = this == PENDING || this == NEEDS_SUPPORT
}

public class WithdrawalStateStore internal constructor(
    private val prefs: SharedPreferences,
) {
    public constructor(context: Context) : this(
        EncryptedPrefsHelper.getEncryptedPrefs(context.applicationContext),
    )

    public val states: LiveData<WithdrawalState> by lazy {
        object : LiveData<WithdrawalState>() {
            private val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
                if (key == KEY_STATE || key == null) postValue(state())
            }

            override fun onActive() {
                prefs.registerOnSharedPreferenceChangeListener(listener)
                value = state()
            }

            override fun onInactive() {
                prefs.unregisterOnSharedPreferenceChangeListener(listener)
            }
        }
    }

    public fun state(): WithdrawalState = try {
        WithdrawalState.valueOf(prefs.getString(KEY_STATE, WithdrawalState.NONE.name).orEmpty())
    } catch (error: RuntimeException) {
        Log.e(TAG, "Invalid participant withdrawal state; requiring study support", error)
        WithdrawalState.NEEDS_SUPPORT
    }

    /** Used by persistence writers so a prefs read error remains retryable. */
    public fun stateOrThrow(): WithdrawalState = try {
        WithdrawalState.valueOf(prefs.getString(KEY_STATE, WithdrawalState.NONE.name).orEmpty())
    } catch (error: IllegalArgumentException) {
        WithdrawalState.NEEDS_SUPPORT
    }

    public fun setState(state: WithdrawalState) {
        val editor = prefs.edit().putString(KEY_STATE, state.name)
        if (state == WithdrawalState.COMPLETE || state == WithdrawalState.CANCELLED) editor.remove(KEY_WITHDRAWAL_REQUEST_ID)
        checkLocalStoreWrite(editor.commit()) {
            "Failed to persist participant withdrawal state"
        }
    }

    /**
     * Atomically closes enrollment and durably assigns the idempotency identity that every
     * withdrawal attempt will reuse. The ID is committed before WorkManager can run the first
     * network request.
     */
    public fun beginWithdrawal(): String {
        val currentState = state()
        if (currentState != WithdrawalState.NONE) {
            check(
                currentState == WithdrawalState.PENDING ||
                    currentState == WithdrawalState.NEEDS_SUPPORT,
            ) { "A completed withdrawal must be reset by a committed enrollment" }
            return withdrawalRequestIdForRetry()
        }
        check(persistedWithdrawalRequestId() == null) {
            "Cannot replace a withdrawal request identity before reenrollment commits"
        }
        val requestId = UUID.randomUUID().toString()
        checkLocalStoreWrite(
            prefs.edit()
                .putString(KEY_STATE, WithdrawalState.PENDING.name)
                .putString(KEY_WITHDRAWAL_REQUEST_ID, requestId)
                .remove(KEY_ACKNOWLEDGED_SERVERS)
                .remove(KEY_SERVER_DELETION_NEEDS_SUPPORT)
                .commit(),
        ) {
            "Failed to persist participant withdrawal request"
        }
        return requestId
    }

    /** Returns the same canonical lowercase UUID for every network retry and process restart. */
    public fun withdrawalRequestIdForRetry(): String {
        check(state() == WithdrawalState.PENDING || state() == WithdrawalState.NEEDS_SUPPORT) {
            "A withdrawal request id is available only while deletion is unresolved"
        }
        val stored = persistedWithdrawalRequestId()
        canonicalUuid(stored)?.let { return it }

        // Upgrade recovery for withdrawals persisted by a client predating request IDs, or for a
        // malformed local value. Commit the replacement before it can reach an HTTP header.
        val generated = UUID.randomUUID().toString()
        checkLocalStoreWrite(prefs.edit().putString(KEY_WITHDRAWAL_REQUEST_ID, generated).commit()) {
            "Failed to persist participant withdrawal request id"
        }
        return generated
    }

    internal fun persistedWithdrawalRequestId(): String? =
        prefs.getString(KEY_WITHDRAWAL_REQUEST_ID, null)

    public fun acknowledgedServerIds(): Set<Long> =
        prefs.getStringSet(KEY_ACKNOWLEDGED_SERVERS, emptySet()).orEmpty().mapNotNull(String::toLongOrNull).toSet()

    public fun acknowledgeServer(id: Long) {
        val updated = acknowledgedServerIds().toMutableSet().apply { add(id) }
        checkLocalStoreWrite(
            prefs.edit()
                .putStringSet(KEY_ACKNOWLEDGED_SERVERS, updated.mapTo(linkedSetOf(), Long::toString))
                .commit(),
        ) {
            "Failed to persist participant withdrawal acknowledgment"
        }
    }

    public fun requireServerDeletionSupport() {
        checkLocalStoreWrite(prefs.edit().putBoolean(KEY_SERVER_DELETION_NEEDS_SUPPORT, true).commit()) {
            "Failed to persist the server-deletion support requirement"
        }
    }

    public fun serverDeletionNeedsSupport(): Boolean =
        prefs.getBoolean(KEY_SERVER_DELETION_NEEDS_SUPPORT, false)

    public fun resetForReenrollment() {
        checkLocalStoreWrite(
            prefs.edit()
                .remove(KEY_STATE)
                .remove(KEY_ACKNOWLEDGED_SERVERS)
                .remove(KEY_SERVER_DELETION_NEEDS_SUPPORT)
                .commit(),
        ) {
            "Failed to clear participant withdrawal state"
        }
    }

    /**
     * Commits the new enrollment identity and clears terminal withdrawal state in one
     * encrypted-preferences transaction. A process death can therefore expose either the old
     * withdrawn state or the complete new enrollment, never a mixture of the two.
     */
    public fun completeReenrollment(studyId: UUID, participantId: String) {
        InteractionPolicySettings.invalidateMemoryCache()
        checkLocalStoreWrite(
            prefs.edit()
                .putString(STUDY_ID, studyId.toString())
                .putString(PARTICIPANT_ID, participantId)
                .putString(PARTICIPATION_STATUS, ParticipationStatus.ENROLLED.name)
                .remove(INTERACTION_POLICY_SNAPSHOT_KEY)
                .remove(KEY_STATE)
                .remove(KEY_ACKNOWLEDGED_SERVERS)
                .remove(KEY_SERVER_DELETION_NEEDS_SUPPORT)
                .remove(KEY_WITHDRAWAL_REQUEST_ID)
                .commit(),
        ) {
            "Failed to persist enrollment and reset participant withdrawal state"
        }
    }

    private companion object {
        private const val TAG = "WithdrawalStateStore"
        private const val KEY_STATE = "participant_withdrawal_state"
        private const val KEY_ACKNOWLEDGED_SERVERS = "participant_withdrawal_acknowledged_servers"
        private const val KEY_SERVER_DELETION_NEEDS_SUPPORT =
            "participant_withdrawal_server_deletion_needs_support"
        private const val KEY_WITHDRAWAL_REQUEST_ID = "participant_withdrawal_request_id"

        private fun canonicalUuid(value: String?): String? = value?.let { candidate ->
            runCatching { UUID.fromString(candidate).toString() }
                .getOrNull()
                ?.takeIf { canonical -> canonical == candidate }
        }
    }
}

public object ParticipantWithdrawalManager {
    public const val WORK_NAME: String = "participant_withdrawal"

    private data class RecoveryScope(val studyId: String, val participantId: String)

    /**
     * The server ended this enrollment: it erased the participant (status ERASED), or the study team cancelled the
     * attempt before its ENROLLMENT ack landed ([ended] CANCELLED). Ends it here with the hard stop and the
     * local clear, without any request: the server already holds the outcome.
     */
    public fun endEnrollmentEndedByServer(context: Context, expected: UploadServerEntity, responseGeneration: Long,
        ended: WithdrawalState = WithdrawalState.COMPLETE): Boolean {
        val appContext = context.applicationContext
        var applied = false
        ResearchPersistenceGate.stop(ResearchPersistenceGate.PrivacyOperation(ResearchPersistenceGate.PrivacyOperationKind.WITHDRAWAL)) {
            val fence = ResearchErasureFence(appContext)
            if (!ResearchPersistenceGate.isSameEnrolledOwner(appContext, expected) ||
                fence.settingsGeneration() != responseGeneration) return@stop
            UploadQueueSingleFlight.withExclusiveMutation {
                val stateStore = WithdrawalStateStore(appContext)
                stateStore.beginWithdrawal()
                stateStore.acknowledgeServer(expected.id)
                enqueue(appContext, cancelExistingWork = false)
                EnrollmentSettings(appContext).setParticipationStatus(ParticipationStatus.NOT_ENROLLED)
                enforceCollectionStopped(appContext)
                clearLocalEnrollment(appContext, LocalUploadDiagnosticsStore.of(appContext), listOf(expected), false, ended)
                WorkManager.getInstance(appContext).cancelAllWork()
                applied = true
            }
        }
        return applied
    }

    internal fun clearLocalEnrollment(appContext: Context, diagnostics: LocalUploadDiagnosticsStore,
        servers: List<UploadServerEntity>, needsSupport: Boolean, ended: WithdrawalState = WithdrawalState.COMPLETE) {
        val enrollmentSettings = EnrollmentSettings(appContext)
        val studyId = enrollmentSettings.getStudyId()
        ResearchErasureFence(appContext)
            .erase(CollectionModuleId.values().toSet(), durableIntent = false)
        CollectionAckRetryQueue.of(appContext).clearForWithdrawal()
        eraseResearchSourceState(appContext)
        diagnostics.clear()
        // Identity goes last (clearEnrollment, which also retires invitation trust): an interrupted
        // erasure must still know which enrollment it is withdrawing when the worker retries.
        ChronicleDb.getInstance(appContext).clearAllTables()
        val recoveryScopes = servers.mapTo(mutableSetOf()) {
            RecoveryScope(studyId = it.studyId, participantId = it.participantId)
        }
        if (studyId != INVALID_STUDY_ID &&
            enrollmentSettings.getParticipantId().isNotBlank()) {
            recoveryScopes += RecoveryScope(studyId = studyId.toString(), participantId = enrollmentSettings.getParticipantId())
        }
        recoveryScopes.forEach { scope ->
            LocalStoreRecoveryManager.eraseForEnrollment(appContext, scope.studyId, scope.participantId)
        }
        enrollmentSettings.clearEnrollment(eraseLocalData = true, diagnostics = diagnostics)
        StorageAdmission.clear(appContext)
        val fence = ResearchErasureFence(appContext)
        fence.pending().forEach(fence::completed)
        val stateStore = WithdrawalStateStore(appContext)
        stateStore.setState(if (needsSupport || stateStore.serverDeletionNeedsSupport())
            WithdrawalState.NEEDS_SUPPORT else ended)
    }

    public fun collectionMustRemainStopped(context: Context): Boolean =
        WithdrawalStateStore(context.applicationContext).state() != WithdrawalState.NONE

    /** Reasserts the immediate collection shutdown without cancelling the deletion worker. */
    public fun enforceCollectionStopped(context: Context) {
        val appContext = context.applicationContext
        CollectionLoopCoordinator(appContext).stopSensorServiceForWithdrawal()
        DeviceUnlockMonitoringService.stopService(appContext)
        if (BuildConfig.ALLOW_RESTRICTED_RESEARCH_PERMISSIONS) {
            DistributionRestrictedRuntime.unregisterActivityRecognition(appContext)
        }
        NotificationManagerCompat.from(appContext).cancel(
            IDENTIFY_USER_NOTIFICATION_TAG,
            appContext.resources.getInteger(R.integer.identify_user_notification_id),
        )
    }

    public fun begin(context: Context): Boolean {
        val appContext = context.applicationContext
        val stateStore = WithdrawalStateStore(appContext)
        var createdRequest = false
        try {
            ResearchPersistenceGate.stop(ResearchPersistenceGate.PrivacyOperation(ResearchPersistenceGate.PrivacyOperationKind.WITHDRAWAL)) {
                if (stateStore.state() == WithdrawalState.NONE) {
                    ResearchErasureFence(appContext)
                        .erase(CollectionModuleId.values().toSet(), durableIntent = false)
                    stateStore.beginWithdrawal()
                    createdRequest = true
                }
                val settings = EnrollmentSettings(appContext)
                if (settings.getParticipationStatus() != ParticipationStatus.NOT_ENROLLED) {
                    settings.setParticipationStatus(ParticipationStatus.NOT_ENROLLED)
                }
                CollectionAckRetryQueue.of(appContext).clearForWithdrawal()
            }
        } catch (error: Exception) {
            Log.e(TAG, "Unable to persist the withdrawal request", error)
            return false
        }
        enforceCollectionStopped(appContext)
        if (stateStore.state() == WithdrawalState.PENDING) {
            enqueue(appContext, cancelExistingWork = createdRequest)
        }
        return true
    }

    /** Reasserts and re-enqueues an unfinished durable request after process death. */
    public fun resumePending(context: Context) {
        val appContext = context.applicationContext
        ResearchPersistenceGate.executeAsync { resumePendingOnWorker(appContext) }
    }

    internal fun resumePendingOnWorker(appContext: Context) {
        if (!collectionMustRemainStopped(appContext)) return
        runCatching {
            ResearchPersistenceGate.stop(ResearchPersistenceGate.PrivacyOperation(ResearchPersistenceGate.PrivacyOperationKind.WITHDRAWAL)) {
                val settings = EnrollmentSettings(appContext)
                if (settings.getParticipationStatus() != ParticipationStatus.NOT_ENROLLED) {
                    settings.setParticipationStatus(ParticipationStatus.NOT_ENROLLED)
                }
            }
            enforceCollectionStopped(appContext)
            if (WithdrawalStateStore(appContext).state() == WithdrawalState.PENDING) {
                enqueue(appContext, cancelExistingWork = false)
            }
        }.onFailure { error ->
            Log.e(TAG, "Failed to resume pending withdrawal; collection remains blocked", error)
        }
    }

    private fun enqueue(context: Context, cancelExistingWork: Boolean) {
        val workManager = WorkManager.getInstance(context)
        if (cancelExistingWork) workManager.cancelAllWork()
        val request = OneTimeWorkRequestBuilder<ParticipantWithdrawalWorker>()
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .build()
        workManager.enqueueUniqueWork(
            WORK_NAME,
            if (cancelExistingWork) ExistingWorkPolicy.REPLACE else ExistingWorkPolicy.KEEP,
            request,
        )
    }

    private const val TAG = "ParticipantWithdrawal"
}

public class ParticipantWithdrawalWorker(
    context: Context,
    params: WorkerParameters,
) : Worker(context, params) {
    private data class AcknowledgedWithdrawal(val servers: List<UploadServerEntity>, val needsSupport: Boolean,
        val ended: WithdrawalState)

    override fun doWork(): Result {
        if (WithdrawalStateStore(applicationContext).state() != WithdrawalState.PENDING) return Result.success()
        ParticipantWithdrawalManager.enforceCollectionStopped(applicationContext)
        val diagnostics = try {
            LocalUploadDiagnosticsStore.of(applicationContext)
        } catch (error: Exception) {
            Log.e(TAG, "Unable to prepare withdrawal diagnostics erasure", error)
            return Result.retry()
        }
        return performWithdrawal(diagnostics)
    }

    private fun requestWithdrawalAcknowledgments(): AcknowledgedWithdrawal? {
        val appContext = applicationContext
        val stateStore = WithdrawalStateStore(appContext)
        if (stateStore.state() != WithdrawalState.PENDING) return null
        val db = ChronicleDb.getInstance(appContext)
        val servers = listOfNotNull(db.uploadServerDao().getConfiguredServer())
        val acknowledged = stateStore.acknowledgedServerIds()
        var needsSupport = servers.isEmpty() && acknowledged.isEmpty()
        var ended = WithdrawalState.COMPLETE
        if (needsSupport) stateStore.requireServerDeletionSupport()
        val withdrawalRequestId = try {
            stateStore.withdrawalRequestIdForRetry()
        } catch (error: RuntimeException) {
            Log.e(TAG, "Failed to persist the withdrawal request identity", error)
            return null
        }

        for (server in servers) {
            if (server.id in acknowledged) continue
            val apiKey = server.apiKey
            if (apiKey.isNullOrBlank()) {
                needsSupport = true
                stateStore.requireServerDeletionSupport()
                continue
            }
            when (requestWithdrawal(server, apiKey, withdrawalRequestId, stateStore) ?: return null) {
                WithdrawalState.NEEDS_SUPPORT -> {
                    needsSupport = true
                    stateStore.requireServerDeletionSupport()
                }
                WithdrawalState.CANCELLED -> ended = WithdrawalState.CANCELLED
                else -> Unit
            }
        }

        return AcknowledgedWithdrawal(servers = servers, needsSupport = needsSupport, ended = ended)
    }

    /**
     * One server's answer: COMPLETE (acknowledged and stored); CANCELLED (the study team cancelled this attempt and revoked
     * its key, the server holds the outcome as on a status poll, and a retry gets the same answer); NEEDS_SUPPORT (refused
     * for good); null: retried with the same request id.
     */
    private fun requestWithdrawal(server: UploadServerEntity, apiKey: String, requestId: String,
                                  stateStore: WithdrawalStateStore): WithdrawalState? = try {
        UploadWorker.getChronicleStudyApi(server.url).withdrawCurrentEnrollment(server.sourceRandomDeviceId, apiKey, requestId)
        stateStore.acknowledgeServer(server.id)
        WithdrawalState.COMPLETE
    } catch (error: Exception) {
        when {
            isEnrollmentCancelledRefusal(error) -> WithdrawalState.CANCELLED
                .also { Log.w(TAG, "The study team cancelled this enrollment attempt; ending it") }
            // Only 410 is permanent: the server answers it for a key or request it will never accept.
            // A 401 can be a signature or clock refusal, so it retries with the same request id.
            isPermanentWithdrawalRefusal(error) -> WithdrawalState.NEEDS_SUPPORT
                .also { Log.e(TAG, "Withdrawal credential was permanently refused; study support is required") }
            // Do not convert a temporary outage into an abandoned erasure request. The idempotency identity and
            // credential remain encrypted locally and WorkManager retries with backoff until the server acknowledges.
            else -> null.also { Log.w(TAG, "Withdrawal request failed (${error.javaClass.simpleName})") }
        }
    }

    private fun performWithdrawal(diagnostics: LocalUploadDiagnosticsStore): Result {
        // Release the upload lock before acquiring stop for acknowledged local erasure.
        val acknowledged = UploadQueueSingleFlight.withExclusiveMutation { requestWithdrawalAcknowledgments() }
            ?: return if (WithdrawalStateStore(applicationContext).state() == WithdrawalState.PENDING) Result.retry() else Result.success()
        var result: Result = Result.retry()
        return try {
            ResearchPersistenceGate.stop(ResearchPersistenceGate.PrivacyOperation(ResearchPersistenceGate.PrivacyOperationKind.WITHDRAWAL)) {
                result = UploadQueueSingleFlight.withExclusiveMutation {
                    finishAcknowledgedWithdrawal(diagnostics, acknowledged)
                }
            }
            result
        } catch (error: Exception) {
            Log.e(TAG, "Acknowledged withdrawal cleanup will retry", error)
            Result.retry()
        }
    }

    private fun finishAcknowledgedWithdrawal(diagnostics: LocalUploadDiagnosticsStore, acknowledged: AcknowledgedWithdrawal): Result {
        val servers = acknowledged.servers
        val appContext = applicationContext
        val stateStore = WithdrawalStateStore(appContext)
        if (stateStore.state() != WithdrawalState.PENDING) return Result.success()
        val db = ChronicleDb.getInstance(appContext)
        if (db.uploadServerDao().getConfiguredServer()?.let {
                ResearchErasureFence.enrollmentKey(it)
            } != servers.singleOrNull()?.let(ResearchErasureFence::enrollmentKey)) return Result.retry()
        return try {
            ParticipantWithdrawalManager.clearLocalEnrollment(appContext, diagnostics, servers, acknowledged.needsSupport,
                acknowledged.ended)
            Result.success()
        } catch (error: RuntimeException) {
            Log.e(TAG, "Failed to clear local data or persist final withdrawal state", error)
            Result.retry()
        }
    }

    private companion object {
        private const val TAG = "ParticipantWithdrawal"
    }
}
