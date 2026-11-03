package com.openlattice.chronicle.security

import com.openlattice.chronicle.BuildConfig
import com.openlattice.chronicle.utils.Utils
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BuildOperatorConfigInvariantTest {
    @Test
    fun distributedBuildContainsNoDeploymentSigningCredential() {
        assertFalse(BuildConfig::class.java.declaredFields.any { it.name == "MOBILE_SIGNING_SECRET" })
    }

    @Test
    fun publicStoreBuildsAcceptParticipantSelectedPublicHttpsChronicleServers() {
        if (BuildConfig.DISTRIBUTION_CHANNEL in setOf("OPEN", "AMAZON")) {
            assertTrue(BuildConfig.ALLOW_ANY_SERVER)
            assertEquals("", BuildConfig.CHRONICLE_PRODUCTION_HOST)
            assertEquals("https://participant.example.org:8443",
                Utils.normalizeTrustedServerUrl("https://participant.example.org:8443/"))
        } else {
            assertFalse(BuildConfig.ALLOW_ANY_SERVER)
        }
    }
}
