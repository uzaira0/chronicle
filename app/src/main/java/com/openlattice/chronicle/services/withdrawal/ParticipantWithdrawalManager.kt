package com.openlattice.chronicle.services.withdrawal

import android.content.Context
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
import com.openlattice.chronicle.BuildConfig
import com.openlattice.chronicle.R
import com.openlattice.chronicle.collection.DistributionRestrictedRuntime
import com.openlattice.chronicle.collection.state.CollectionAckRetryQueue
import com.openlattice.chronicle.collection.state.CollectionLoopCoordinator
import com.openlattice.chronicle.collection.state.ResearchPersistenceGate
import com.openlattice.chronicle.data.ParticipationStatus
import com.openlattice.chronicle.preferences.EncryptedPrefsHelper
import com.openlattice.chronicle.preferences.EnrollmentSettings
import com.openlattice.chronicle.preferences.INTERACTION_POLICY_SNAPSHOT_KEY
import com.openlattice.chronicle.preferences.InteractionPolicySettings
import com.openlattice.chronicle.preferences.PARTICIPANT_ID
import com.openlattice.chronicle.preferences.PARTICIPATION_STATUS
import com.openlattice.chronicle.preferences.STUDY_ID
import com.openlattice.chronicle.services.crypto.EncryptionSettingStore
import com.openlattice.chronicle.services.notifications.DeviceUnlockMonitoringService
import com.openlattice.chronicle.services.notifications.IDENTIFY_USER_NOTIFICATION_TAG
import com.openlattice.chronicle.services.upload.UploadWorker
import com.openlattice.chronicle.services.upload.UploadQueueSingleFlight
import com.openlattice.chronicle.services.upload.LocalUploadDiagnosticsStore
import com.openlattice.chronicle.storage.ChronicleDb
import java.util.UUID

public enum class WithdrawalState {
    NONE,
    PENDING,
    COMPLETE,
    NEEDS_SUPPORT,
}

public class WithdrawalStateStore internal constructor(
    private val prefs: SharedPreferences,
) {
    public constructor(context: Context) : this(
        EncryptedPrefsHelper.getEncryptedPrefs(context.applicationContext),
    )

    public fun state(): WithdrawalState = try {
        WithdrawalState.valueOf(prefs.getString(KEY_STATE, WithdrawalState.NONE.name).orEmpty())
    } catch (error: RuntimeException) {
        Log.e(TAG, "Invalid participant withdrawal state; requiring study support", error)
        WithdrawalState.NEEDS_SUPPORT
    }

    public fun setState(state: WithdrawalState) {
        val editor = prefs.edit().putString(KEY_STATE, state.name)
        if (state == WithdrawalState.COMPLETE) editor.remove(KEY_WITHDRAWAL_REQUEST_ID)
        check(editor.commit()) {
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
        check(
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
        check(prefs.edit().putString(KEY_WITHDRAWAL_REQUEST_ID, generated).commit()) {
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
        check(
            prefs.edit()
                .putStringSet(KEY_ACKNOWLEDGED_SERVERS, updated.mapTo(linkedSetOf(), Long::toString))
                .commit(),
        ) {
            "Failed to persist participant withdrawal acknowledgment"
        }
    }

    public fun resetAcknowledgments() {
        check(
            prefs.edit()
                .remove(KEY_ACKNOWLEDGED_SERVERS)
                .remove(KEY_SERVER_DELETION_NEEDS_SUPPORT)
                .commit(),
        ) {
            "Failed to clear participant withdrawal acknowledgments"
        }
    }

    public fun requireServerDeletionSupport() {
        check(prefs.edit().putBoolean(KEY_SERVER_DELETION_NEEDS_SUPPORT, true).commit()) {
            "Failed to persist the server-deletion support requirement"
        }
    }

    public fun serverDeletionNeedsSupport(): Boolean =
        prefs.getBoolean(KEY_SERVER_DELETION_NEEDS_SUPPORT, false)

    public fun resetForReenrollment() {
        check(
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
        check(
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
            ResearchPersistenceGate.stop {
                if (stateStore.state() == WithdrawalState.NONE) {
                    stateStore.beginWithdrawal()
                    createdRequest = true
                }
                val settings = EnrollmentSettings(appContext)
                if (settings.getParticipationStatus() != ParticipationStatus.NOT_ENROLLED) {
                    settings.setParticipationStatus(ParticipationStatus.NOT_ENROLLED)
                }
                CollectionAckRetryQueue.of(appContext).clearForWithdrawal()
                LocalUploadDiagnosticsStore.of(appContext).clear()
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
        if (!collectionMustRemainStopped(appContext)) return
        runCatching {
            ResearchPersistenceGate.stop {
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
    override fun doWork(): Result = UploadQueueSingleFlight.withExclusiveMutation {
        performWithdrawal()
    }

    private fun performWithdrawal(): Result {
        val appContext = applicationContext
        val stateStore = WithdrawalStateStore(appContext)
        if (stateStore.state() != WithdrawalState.PENDING) return Result.success()
        ParticipantWithdrawalManager.enforceCollectionStopped(appContext)
        val db = ChronicleDb.getInstance(appContext)
        val servers = listOfNotNull(db.uploadServerDao().getConfiguredServer())
        val acknowledged = stateStore.acknowledgedServerIds()
        var needsSupport = servers.isEmpty()
        if (needsSupport) stateStore.requireServerDeletionSupport()
        val withdrawalRequestId = try {
            stateStore.withdrawalRequestIdForRetry()
        } catch (error: RuntimeException) {
            Log.e(TAG, "Failed to persist the withdrawal request identity", error)
            return Result.retry()
        }

        for (server in servers) {
            if (server.id in acknowledged) continue
            val apiKey = server.apiKey
            if (apiKey.isNullOrBlank()) {
                needsSupport = true
                stateStore.requireServerDeletionSupport()
                continue
            }
            try {
                UploadWorker.getChronicleStudyApi(server.url, server.mobileSigningSecretOverride)
                    .withdrawCurrentEnrollment(
                        server.sourceDeviceId,
                        apiKey,
                        withdrawalRequestId,
                    )
                stateStore.acknowledgeServer(server.id)
            } catch (error: Exception) {
                Log.w(TAG, "Withdrawal request failed for server ${server.id}: ${error.javaClass.simpleName}")
                // Do not convert a temporary outage into an abandoned erasure request. The
                // idempotency identity and credential remain encrypted locally and WorkManager
                // retries with backoff until the authoritative server acknowledges withdrawal.
                return Result.retry()
            }
        }

        return try {
            val enrollmentSettings = EnrollmentSettings(appContext)
            val studyId = enrollmentSettings.getStudyId()
            if (studyId != com.openlattice.chronicle.preferences.INVALID_STUDY_ID) {
                EncryptionSettingStore.of(appContext).evict(studyId)
            }
            CollectionAckRetryQueue.of(appContext).clearForWithdrawal()
            db.clearAllTables()
            enrollmentSettings.clearEnrollment()
            stateStore.setState(
                if (needsSupport || stateStore.serverDeletionNeedsSupport()) {
                    WithdrawalState.NEEDS_SUPPORT
                } else {
                    WithdrawalState.COMPLETE
                },
            )
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
