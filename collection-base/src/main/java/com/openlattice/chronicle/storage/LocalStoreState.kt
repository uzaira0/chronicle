package com.openlattice.chronicle.storage

import android.database.sqlite.SQLiteFullException

/**
 * Stable, UI-safe state for Chronicle's encrypted local store.
 *
 * Keep this contract independent of Room and SQLCipher so collection workers and a
 * future Rust/Tauri shell can react to storage failures without depending on the
 * current persistence implementation.
 */
sealed interface LocalStoreState {
    data object Ready : LocalStoreState

    /** What a failed store access means for the participant; [of] is the one classification. */
    sealed interface Failure : LocalStoreState

    /**
     * SQLite rolled back a write because the disk is full. Admission stays closed until a successful retry.
     * A false preference commit is different: Android retains the new values in memory without durable backing.
     */
    data object WriteRefused : Failure

    /** The store cannot be read or trusted (key, schema or file damage); only the recovery screen helps. */
    data class RecoveryRequired(val reason: LocalStoreRecoveryReason) : Failure

    companion object {
        fun of(error: Throwable): Failure {
            val chain = generateSequence(error) { it.cause }
            if (chain.any { it is SQLiteFullException }) return WriteRefused
            return RecoveryRequired(chain.filterIsInstance<LocalStoreRecoveryRequiredException>().firstOrNull()?.recoveryReason
                ?: LocalStoreRecoveryReason.DATABASE_OPEN_FAILED)
        }
    }
}

enum class LocalStoreRecoveryReason {
    MISSING_KEY_MATERIAL,
    INVALID_KEY_MATERIAL,
    KEY_PERSISTENCE_FAILED,
    DATABASE_OPEN_FAILED,
    /** The store decrypts but holds another app build's schema, as after an update across the schema reset. */
    LOCAL_SCHEMA_REPLACED,
    INVALID_PERSISTED_STATE,
}

class LocalStoreRecoveryRequiredException(
    val recoveryReason: LocalStoreRecoveryReason,
    cause: Throwable? = null
) : IllegalStateException("Encrypted local store requires explicit recovery: ${recoveryReason.name}", cause)

/**
 * A false preference commit does not roll back Android's in-memory map (and can also mean an interrupted wait).
 * Without owner-specific reconciliation, retrying from that map can skip an undurable identity or consent write.
 * Keep the store in recovery; only a transaction known to have rolled back can be classified as WriteRefused.
 */
fun checkLocalStoreWrite(success: Boolean, message: () -> String = { "Local store write failed" }) {
    if (!success) throw LocalStoreRecoveryRequiredException(
        LocalStoreRecoveryReason.KEY_PERSISTENCE_FAILED, IllegalStateException(message()),
    )
}
