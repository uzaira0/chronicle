package com.openlattice.chronicle.collection.state

import android.content.Context
import android.util.Log
import com.openlattice.chronicle.collection.CollectionModuleId
import com.openlattice.chronicle.data.ParticipationStatus
import com.openlattice.chronicle.preferences.EnrollmentSettings
import com.openlattice.chronicle.services.upload.LocalOperationalIssue
import com.openlattice.chronicle.services.upload.LocalUploadDiagnosticsStore
import com.openlattice.chronicle.services.upload.LocalUploadModuleFamily
import com.openlattice.chronicle.services.upload.isExpectedProvisionalEnrollmentServer
import com.openlattice.chronicle.services.upload.recordForExpectedOwner
import com.openlattice.chronicle.services.withdrawal.WithdrawalState
import com.openlattice.chronicle.services.withdrawal.WithdrawalStateStore
import com.openlattice.chronicle.storage.ChronicleDb
import com.openlattice.chronicle.storage.UploadServerEntity

/**
 * Final persistence boundary shared by every research-data writer in the app process.
 *
 * Existing module gates decide whether a study requested a data type and whether the participant
 * accepted it. This boundary additionally proves that the one enrollment is still active and no
 * withdrawal has begun, immediately around the database mutation. Withdrawal closes the same
 * barrier under its write lock, eliminating the gate-check/insert race in framework callbacks.
 */
object ResearchPersistenceGate {
    private const val TAG = "ResearchPersistenceGate"
    private val barrier = ResearchPersistenceBarrier()

    /** Sink guard: write failures propagate so the sink returns Failed and its batch is retried. */
    fun guard(context: Context): CollectionPersistenceGuard {
        val appContext = context.applicationContext
        return CollectionPersistenceGuard { persist -> persistGuarded(appContext, null, persist) }
    }

    /** Final combined active-enrollment + module-consent check for a fixed-module sink. */
    fun guard(context: Context, moduleId: CollectionModuleId): CollectionPersistenceGuard {
        val appContext = context.applicationContext
        return CollectionPersistenceGuard { persist -> persistGuarded(appContext, moduleId, persist) }
    }

    /** Bind a direct-boot insertion to the same enrollment captured before the drain. */
    fun guardForExpectedOwner(
        context: Context,
        expectedOwner: String,
        ownerNow: () -> String?,
    ): CollectionPersistenceGuard {
        val appContext = context.applicationContext
        return expectedOwnerGuard(expectedOwner, ownerNow,
            allowed = { isActiveEnrollmentOrThrow(appContext) && StorageAdmission.allowed(appContext) })
    }

    internal fun expectedOwnerGuard(
        expectedOwner: String,
        ownerNow: () -> String?,
        allowed: () -> Boolean,
    ): CollectionPersistenceGuard = CollectionPersistenceGuard { persist ->
        barrier.persistIf(allowed = { allowed() && ownerNow() == expectedOwner }, persist = persist)
    }

    /**
     * Direct-writer convenience that does not invoke [persist] when either gate is closed. Direct
     * writers run in receivers and callbacks with no retry, so a failed write is caught (never
     * crashes the app) and its [records] are counted as LOCAL_WRITE_FAILED.
     */
    fun persistIfCollecting(
        context: Context,
        moduleId: CollectionModuleId,
        records: Int = 1,
        expectedOwner: UploadServerEntity? = captureOwner(context),
        onRefused: (Int) -> Unit = {},
        persist: () -> Unit,
    ): Boolean = persistCounted(context, moduleId, records, expectedOwner, onRefused, persist)

    fun persistIfActive(context: Context, records: Int = 1,
                        expectedOwner: UploadServerEntity? = captureOwner(context), persist: () -> Unit): Boolean =
        persistCounted(context, null, records, expectedOwner, {}, persist)

    private fun persistCounted(
        context: Context,
        moduleId: CollectionModuleId?,
        records: Int,
        expectedOwner: UploadServerEntity?,
        onRefused: (Int) -> Unit,
        persist: () -> Unit,
    ): Boolean {
        val owner = expectedOwner ?: return false
        val appContext = context.applicationContext
        var persisted = false
        try {
            barrier.persistIf(
                allowed = {
                    isSameActiveOwner(appContext, owner) && StorageAdmission.allowed(appContext) &&
                        (moduleId == null || CollectionLoopStore.of(appContext).collects(moduleId))
                },
                persist = {
                    try {
                        persist()
                        persisted = true
                    } catch (error: Exception) {
                        Log.e(TAG, "Research write failed; counting $records record(s) as lost", error)
                        if (records > 0) runCatching {
                            LocalUploadDiagnosticsStore.of(appContext).recordOperational(
                                LocalUploadModuleFamily.LOCAL_STORE,
                                LocalOperationalIssue.LOCAL_WRITE_FAILED, records,
                            )
                        }.onFailure { Log.e(TAG, "Unable to record lost research write", it) }
                    }
                },
                onRefused = {
                    if (records > 0 && isSameActiveOwner(appContext, owner)) {
                        runCatching { onRefused(records) }
                            .onFailure { Log.e(TAG, "Unable to record refused research write", it) }
                    }
                },
            )
        } catch (error: Exception) {
            Log.e(TAG, "Research write admission failed", error)
            recordForExpectedOwner(appContext, owner, LocalUploadModuleFamily.LOCAL_STORE,
                LocalOperationalIssue.LOCAL_WRITE_FAILED, records)
        }
        return persisted
    }

    /** See [ResearchPersistenceBarrier.withReadLease]; the direct-boot drain holds its buffer lock inside it. */
    fun <T> withReadLease(block: () -> T): T = barrier.withReadLease(block)

    /** Captured before a non-replayable sample is submitted; never substitutes a later server. */
    fun captureOwner(context: Context): UploadServerEntity? = runCatching {
        val appContext = context.applicationContext
        ChronicleDb.getInstance(appContext).uploadServerDao().getConfiguredServer()
            ?.takeIf { isSameActiveOwner(appContext, it) }
    }.onFailure { Log.e(TAG, "Unable to capture research sample owner", it) }.getOrNull()

    private fun isSameActiveOwner(context: Context, expected: UploadServerEntity): Boolean {
        if (!isActiveEnrollmentOrThrow(context)) return false
        val settings = EnrollmentSettings(context)
        if (settings.getStudyId().toString() != expected.studyId ||
            settings.getParticipantId() != expected.participantId) return false
        val current = ChronicleDb.getInstance(context).uploadServerDao().getConfiguredServer()
            ?: return false
        return current.id == expected.id && current.createdAt == expected.createdAt &&
            current.studyId == expected.studyId && current.participantId == expected.participantId &&
            current.sourceDeviceId == expected.sourceDeviceId
    }

    fun <T : Any> runIfExpectedOwner(context: Context, expectedOwner: UploadServerEntity,
                                     operation: () -> T): T? {
        val appContext = context.applicationContext
        var result: T? = null
        val admitted = barrier.persistIf(
            allowed = { isSameActiveOwner(appContext, expectedOwner) },
            persist = { result = operation() },
        )
        return if (admitted) checkNotNull(result) else null
    }

    private fun persistGuarded(
        context: Context,
        moduleId: CollectionModuleId?,
        persist: () -> Unit,
    ): Boolean {
        val appContext = context.applicationContext
        return barrier.persistIf(
                allowed = {
                    isActiveEnrollmentOrThrow(appContext) && StorageAdmission.allowed(appContext) &&
                        (moduleId == null || CollectionLoopStore.of(appContext).collects(moduleId))
                },
                persist = persist,
            )
    }

    private fun isActiveEnrollmentOrThrow(context: Context): Boolean {
        val settings = EnrollmentSettings(context)
        return settings.getParticipationStatus() == ParticipationStatus.ENROLLED &&
            settings.isEnrolledOrThrow() &&
            WithdrawalStateStore(context).stateOrThrow() == WithdrawalState.NONE &&
            MinimalPlayArtifactState.isReadyOrThrow(context)
    }

    /**
     * Runs one complete outbound research-data operation under the same read lease as a local
     * write. A withdrawal or policy stop takes the write side, so once that stop returns no
     * previously admitted network operation can still submit data. Exceptions propagate to the
     * worker so retry/failure behavior is preserved.
     */
    fun <T : Any> runIfActive(context: Context, operation: () -> T): T? {
        val appContext = context.applicationContext
        var result: T? = null
        val admitted = barrier.persistIf(
            allowed = { isActiveEnrollment(appContext) },
            persist = { result = operation() },
        )
        return if (admitted) checkNotNull(result) else null
    }

    /**
     * Initial enrollment acknowledgment lease. Setup is not complete yet, so [runIfActive]
     * intentionally rejects it; this narrower lease instead binds the exact issued row owner,
     * immutable enrollment identity, canonical origin, and credential while sharing withdrawal's
     * read/write barrier. Once withdrawal or row ownership changes, it admits neither HTTP nor a
     * retry-queue mutation.
     */
    fun <T : Any> runIfExpectedEnrollment(
        context: Context,
        expected: UploadServerEntity,
        operation: () -> T,
    ): T? {
        val appContext = context.applicationContext
        var result: T? = null
        val admitted = barrier.persistIf(
            allowed = {
                EnrollmentSettings(appContext).getParticipationStatus() == ParticipationStatus.ENROLLED &&
                    WithdrawalStateStore(appContext).state() == WithdrawalState.NONE &&
                    isExpectedProvisionalEnrollmentServer(
                        ChronicleDb.getInstance(appContext).uploadServerDao().getConfiguredServer(),
                        expected,
                    )
            },
            persist = { result = operation() },
        )
        return if (admitted) checkNotNull(result) else null
    }

    /** Fail-closed one-active-study predicate shared by callbacks and participant controls. */
    fun isActiveEnrollment(context: Context): Boolean = try {
        val appContext = context.applicationContext
        val settings = EnrollmentSettings(appContext)
        settings.getParticipationStatus() == ParticipationStatus.ENROLLED &&
            settings.isEnrolled() &&
            WithdrawalStateStore(appContext).state() == WithdrawalState.NONE &&
            MinimalPlayArtifactState.isReady(appContext)
    } catch (error: Exception) {
        Log.e(TAG, "Active-enrollment check failed", error)
        false
    }

    /** Runs a durable stop decision after all already-admitted persistence callbacks finish. */
    fun stop(stopAction: () -> Unit) {
        barrier.stop(stopAction)
    }
}
