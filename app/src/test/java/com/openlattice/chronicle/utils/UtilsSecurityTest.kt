package com.openlattice.chronicle.utils

import com.openlattice.chronicle.BuildConfig
import okhttp3.OkHttpClient
import org.junit.Assert.assertFalse
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class UtilsSecurityTest {

    @Test
    fun requireHttpsUrlAcceptsOnlyHttpsUrls() {
        val arbitraryStudyUrl = "https://research.example.org"
        val arbitraryStudyIsConfigured = BuildConfig.CHRONICLE_PRODUCTION_HOST.equals(
            "research.example.org",
            ignoreCase = true,
        )
        assertEquals(
            BuildConfig.ALLOW_ANY_SERVER || arbitraryStudyIsConfigured,
            Utils.requireHttpsUrl(arbitraryStudyUrl),
        )
        assertEquals(
            BuildConfig.ALLOW_ANY_SERVER || arbitraryStudyIsConfigured,
            Utils.requireHttpsUrl(arbitraryStudyUrl.replace("https", "HTTPS")),
        )
        if (BuildConfig.ALLOW_ANY_SERVER) {
            assertTrue(Utils.requireHttpsUrl("https://chronicle.example"))
        } else {
            assertFalse(Utils.requireHttpsUrl("https://chronicle.example"))
        }
        assertFalse(Utils.requireHttpsUrl("http://chronicle.example"))
        assertFalse(Utils.requireHttpsUrl("javascript:alert(1)"))
        assertFalse(Utils.requireHttpsUrl("file:///tmp/chronicle"))
        assertFalse(Utils.requireHttpsUrl("https://research.example.org/tenant"))
        assertFalse(Utils.requireHttpsUrl("https://user@research.example.org"))
        assertFalse(Utils.requireHttpsUrl("https://research.example.org?server=https://evil.example"))
    }

    @Test
    fun normalizeTrustedServerUrlRemovesDefaultPortAndSlash() {
        val trustedHost = if (BuildConfig.ALLOW_ANY_SERVER) {
            "chronicle.example"
        } else {
            BuildConfig.CHRONICLE_PRODUCTION_HOST.trim().lowercase()
        }
        if (trustedHost.isBlank()) {
            assertNull(Utils.normalizeTrustedServerUrl("https://chronicle.example:443/"))
        } else {
            assertEquals(
                "https://$trustedHost",
                Utils.normalizeTrustedServerUrl("https://$trustedHost:443/"),
            )
        }
    }

    @Test
    fun publicHttpsSelfHostMayUseAnExplicitValidPort() {
        if (BuildConfig.ALLOW_ANY_SERVER) {
            assertEquals(
                "https://chronicle.example:8443",
                Utils.normalizeTrustedServerUrl("https://chronicle.example:8443/"),
            )
        } else {
            assertNull(Utils.normalizeTrustedServerUrl("https://chronicle.example:8443/"))
        }
        assertFalse(Utils.requireHttpsUrl("https://chronicle.example:0"))
        assertFalse(Utils.requireHttpsUrl("https://chronicle.example:65536"))
    }

    @Test
    fun effectiveMobileSigningSecretPrefersPerServerOverride() {
        assertEquals(
            "server-override-secret",
            Utils.effectiveMobileSigningSecret("server-override-secret"),
        )
    }

    @Test
    fun blankMobileSigningOverrideFallsBackToApkDefaultSecret() {
        assertEquals(
            Utils.effectiveMobileSigningSecret(null),
            Utils.effectiveMobileSigningSecret("  "),
        )
    }

    @Test
    fun chronicleClientNeverFollowsServerRedirects() {
        val trustedHost = if (BuildConfig.ALLOW_ANY_SERVER) {
            "research.example.org"
        } else {
            BuildConfig.CHRONICLE_PRODUCTION_HOST.trim().lowercase()
        }
        if (trustedHost.isBlank()) return

        val client = Utils.createRetrofitAdapter("https://$trustedHost").callFactory() as OkHttpClient
        assertFalse(client.followRedirects)
        assertFalse(client.followSslRedirects)
    }
}
