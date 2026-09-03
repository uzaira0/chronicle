package com.openlattice.chronicle

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class EnrollmentCredentialPolicyTest {
    @Test
    fun everyPublicDistributionRejectsAMissingDeviceApiKey() {
        listOf("PLAY", "OPEN", "AMAZON").forEach { channel ->
            assertFalse(enrollmentCredentialMeetsDistributionContract(channel, null))
            assertFalse(enrollmentCredentialMeetsDistributionContract(channel, "  "))
            assertTrue(enrollmentCredentialMeetsDistributionContract(channel, "issued-key"))
        }
    }

    @Test
    fun controlledResearchDistributionRetainsLegacyDeviceIdCompatibility() {
        assertTrue(enrollmentCredentialMeetsDistributionContract("RESEARCH", null))
    }
}
