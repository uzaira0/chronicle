package com.openlattice.chronicle.storage

import org.junit.Assert.assertThrows
import org.junit.Test

class LocalStoreRecoveryManagerTest {
    @Test
    fun resetRequiresBothIndependentConfirmations() {
        assertThrows(IllegalArgumentException::class.java) {
            LocalStoreResetConfirmation(
                preserveEncryptedRecoveryBundle = true,
                understandsReenrollmentRequired = false,
            ).requireExplicitApproval()
        }
        assertThrows(IllegalArgumentException::class.java) {
            LocalStoreResetConfirmation(
                preserveEncryptedRecoveryBundle = false,
                understandsReenrollmentRequired = true,
            ).requireExplicitApproval()
        }
    }

    @Test
    fun resetAllowsOnlyCompleteExplicitApproval() {
        LocalStoreResetConfirmation(
            preserveEncryptedRecoveryBundle = true,
            understandsReenrollmentRequired = true,
        ).requireExplicitApproval()
    }
}
