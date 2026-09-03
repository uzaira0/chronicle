package com.openlattice.chronicle.storage

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LocalStoreStateTest {
    @Test
    fun recoveryExceptionCarriesStableFrameworkNeutralReason() {
        val error = LocalStoreRecoveryRequiredException(
            LocalStoreRecoveryReason.MISSING_KEY_MATERIAL
        )

        assertEquals(LocalStoreRecoveryReason.MISSING_KEY_MATERIAL, error.recoveryReason)
        assertTrue(error.message.orEmpty().contains("MISSING_KEY_MATERIAL"))
    }

    @Test
    fun recoveryStateDoesNotExposePersistenceExceptionDetails() {
        val state: LocalStoreState = LocalStoreState.RecoveryRequired(
            LocalStoreRecoveryReason.DATABASE_OPEN_FAILED
        )

        assertEquals(
            LocalStoreRecoveryReason.DATABASE_OPEN_FAILED,
            (state as LocalStoreState.RecoveryRequired).reason
        )
    }
}
