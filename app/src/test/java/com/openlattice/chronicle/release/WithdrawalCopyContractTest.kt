package com.openlattice.chronicle.release

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class WithdrawalCopyContractTest {
    private val sources = listOf("main", "googleServices", "play").associateWith { flavor ->
        File("src/$flavor/res/values/strings.xml").readText()
    }

    @Test
    fun withdrawalCopyMatchesEachFlavorsReachableControl() {
        sources.forEach { (flavor, source) ->
            val text = policy(source)
            if (flavor == "play") {
                assertTrue(text.contains("uninstall the app"))
                assertTrue(text.contains("no in-app withdrawal"))
            } else {
                assertTrue("$flavor must describe the withdrawal control", text.contains("Withdraw from study in Chronicle Settings"))
                assertFalse(text.contains("no in-app withdrawal"))
            }
        }
        val minimal = File("src/minimal/res/values/strings.xml").readText()
        assertTrue(minimal.contains("uninstall the app"))
        assertFalse(minimal.contains("Withdraw from study"))
        for (key in listOf("device_enroll_success", "data_sharing_intro")) {
            val value = Regex("""<string name="$key"[^>]*>(.*?)</string>""").find(sources.getValue("main"))!!.groupValues[1]
            assertTrue(value.contains("Withdraw from study in Chronicle Settings"))
        }
    }

    private fun policy(source: String): String = Regex(
        """<string\s+name="platform_privacy_policy_full"[^>]*>(.*?)</string>""",
        RegexOption.DOT_MATCHES_ALL,
    ).find(source)?.groupValues?.get(1).orEmpty()

    @Test
    fun policiesDiscloseDeviceFieldsAndRedactedDiagnosticsWithoutStoredIpClaims() {
        sources.forEach { (flavor, source) ->
            val text = policy(source)
            listOf("device model", "manufacturer", "Android version", "diagnostic", "HTTP status", "exception class").forEach { phrase ->
                assertTrue("$flavor policy must disclose $phrase", text.contains(phrase))
            }
            assertFalse("$flavor must not claim stored IP addresses", text.contains("IP address"))
            assertTrue(text.contains("October 7, 2026"))
            assertTrue(text.contains("parent or guardian permission"))
        }
    }

    @Test
    fun openFlavorPolicyDisclosesAudioPlaybackStateAndGooglePlayServices() {
        val text = policy(sources.getValue("googleServices"))
        assertTrue("googleServices policy must disclose audio playback state", text.contains("audio playback state"))
        assertTrue("googleServices policy must name Google Play services", text.contains("Google Play services"))
    }
}
