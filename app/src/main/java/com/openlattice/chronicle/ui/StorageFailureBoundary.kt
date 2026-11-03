package com.openlattice.chronicle.ui

import android.app.Activity
import android.util.Log
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import com.openlattice.chronicle.LocalStoreRecoveryActivity
import com.openlattice.chronicle.R
import com.openlattice.chronicle.collection.state.ResearchPersistenceGate
import com.openlattice.chronicle.preferences.EnrollmentSettings
import com.openlattice.chronicle.storage.LocalStoreState
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Shared boundary for participant screens whose lifecycle jobs access the retained local store. */
internal fun Fragment.storageFailureHandler() = storageFailureHandler { activity?.takeIf { isAdded } }

internal fun Activity.storageFailureHandler() = storageFailureHandler { this }

internal fun Fragment.updateEnrollmentSettings(update: (EnrollmentSettings) -> Unit) {
    val host = requireActivity()
    host.lifecycleScope.launch(host.storageFailureHandler()) {
        withContext(Dispatchers.IO) { update(EnrollmentSettings(host.applicationContext)) }
    }
}

private fun storageFailureHandler(host: () -> Activity?) = CoroutineExceptionHandler { _, error ->
    val storageFailure = ResearchPersistenceGate.isStorageFailure(error)
    Log.e("StorageFailureBoundary", "Participant action failed (${error.javaClass.simpleName})")
    if (storageFailure) ResearchPersistenceGate.closeForPersistenceFailure()
    val current = host()
    if (current != null && !current.isFinishing && !current.isDestroyed) {
        if (!storageFailure) {
            Toast.makeText(current, R.string.dashboard_action_failed, Toast.LENGTH_LONG).show()
            return@CoroutineExceptionHandler
        }
        if (current.openStoreRecovery(error)) current.finish()
    }
}

/**
 * The one route to the store-recovery screen. A refused write (full disk) keeps the participant where they are with
 * the storage-paused notice and returns false; the reset screen is only for a store that cannot be read or trusted.
 */
internal fun Activity.openStoreRecovery(error: Throwable): Boolean {
    ResearchPersistenceGate.closeForPersistenceFailure()
    return when (val failure = LocalStoreState.of(error)) {
        LocalStoreState.WriteRefused -> {
            Toast.makeText(this, R.string.settings_control_storage_paused, Toast.LENGTH_LONG).show()
            false
        }
        is LocalStoreState.RecoveryRequired -> {
            startActivity(LocalStoreRecoveryActivity.intent(this, failure.reason))
            true
        }
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
