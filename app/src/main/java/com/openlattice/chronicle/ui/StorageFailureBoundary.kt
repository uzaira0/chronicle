package com.openlattice.chronicle.ui

import android.util.Log
import androidx.fragment.app.Fragment
import com.openlattice.chronicle.LocalStoreRecoveryActivity
import com.openlattice.chronicle.collection.state.ResearchPersistenceGate
import com.openlattice.chronicle.storage.LocalStoreRecoveryReason
import com.openlattice.chronicle.storage.LocalStoreRecoveryRequiredException
import kotlinx.coroutines.CoroutineExceptionHandler

/** Shared boundary for participant screens whose lifecycle jobs access the retained local store. */
internal fun Fragment.storageFailureHandler() = CoroutineExceptionHandler { _, error ->
    Log.e("StorageFailureBoundary", "Participant storage operation failed; collection closed for recovery", error)
    if (ResearchPersistenceGate.isStorageFailure(error)) ResearchPersistenceGate.closeForPersistenceFailure()
    val current = activity
    if (isAdded && current != null && !current.isFinishing && !current.isDestroyed) {
        startActivity(LocalStoreRecoveryActivity.intent(current,
            (error as? LocalStoreRecoveryRequiredException)?.recoveryReason
                ?: LocalStoreRecoveryReason.DATABASE_OPEN_FAILED))
        current.finish()
    }
}

/** A failed privacy mutation must close admission before the worker returns to MAIN. */
internal suspend fun <T> participantStorageWrite(action: suspend () -> T): Result<T> = try {
    Result.success(action())
} catch (error: kotlinx.coroutines.CancellationException) {
    throw error
} catch (error: Exception) {
    if (ResearchPersistenceGate.isStorageFailure(error)) ResearchPersistenceGate.closeForPersistenceFailure()
    Log.e("StorageFailureBoundary", "Participant change could not be persisted", error)
    Result.failure(error)
}
