package com.openlattice.chronicle.collection.state

import android.content.Context
import android.util.Log
import com.openlattice.chronicle.collection.CollectionModuleId
import com.openlattice.chronicle.data.ParticipationStatus
import com.openlattice.chronicle.preferences.EnrollmentSettings
import com.openlattice.chronicle.services.upload.isExpectedProvisionalEnrollmentServer
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

    fun guard(context: Context): CollectionPersistenceGuard {
        val appContext = context.applicationContext
        return CollectionPersistenceGuard { persist -> persistIfActive(appContext, persist) }
    }

    /** Final combined active-enrollment + module-consent check for a fixed-module sink. */
    fun guard(context: Context, moduleId: CollectionModuleId): CollectionPersistenceGuard {
        val appContext = context.applicationContext
        return CollectionPersistenceGuard { persist -> persistIfCollecting(appContext, moduleId, persist) }
    }

    /** Direct-writer convenience that does not invoke [persist] when either gate is closed. */
    fun persistIfCollecting(
        context: Context,
        moduleId: CollectionModuleId,
        persist: () -> Unit,
    ): Boolean {
        var invoked = false
        val active = persistIfActive(context) {
            if (CollectionLoopStore.of(context.applicationContext).collects(moduleId)) {
                persist()
                invoked = true
            }
        }
        return active && invoked
    }

    fun persistIfActive(context: Context, persist: () -> Unit): Boolean = try {
        val appContext = context.applicationContext
        barrier.persistIf(
            allowed = { isActiveEnrollment(appContext) },
            persist = persist,
        )
    } catch (error: Exception) {
        Log.e(TAG, "Active-enrollment persistence check failed; dropping research write", error)
        false
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
