package com.openlattice.chronicle.storage

/**
 * Stable, UI-safe state for Chronicle's encrypted local store.
 *
 * Keep this contract independent of Room and SQLCipher so collection workers and a
 * future Rust/Tauri shell can react to storage failures without depending on the
 * current persistence implementation.
 */
sealed interface LocalStoreState {
    data object Ready : LocalStoreState
    data class TemporarilyUnavailable(val reason: String) : LocalStoreState
    data class RecoveryRequired(val reason: LocalStoreRecoveryReason) : LocalStoreState
}

enum class LocalStoreRecoveryReason {
    MISSING_KEY_MATERIAL,
    INVALID_KEY_MATERIAL,
    KEY_PERSISTENCE_FAILED,
    DATABASE_OPEN_FAILED,
    DATABASE_MIGRATION_FAILED
}

class LocalStoreRecoveryRequiredException(
    val recoveryReason: LocalStoreRecoveryReason,
    cause: Throwable? = null
) : IllegalStateException("Encrypted local store requires explicit recovery: ${recoveryReason.name}", cause)
