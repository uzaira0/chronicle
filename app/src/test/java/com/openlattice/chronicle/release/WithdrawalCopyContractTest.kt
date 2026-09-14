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
    fun noFlavorAdvertisesAnInAppWithdrawalControl() {
        sources.forEach { (flavor, source) ->
            listOf(
                "Withdraw from study",
                "withdraw from Chronicle Settings",
                "choose Withdraw",
                "withdrawFromStudy",
            ).forEach { prohibited ->
                assertFalse("$flavor contains stale withdrawal copy: $prohibited", source.contains(prohibited))
            }
        }
    }

    @Test
    fun policiesAndParticipantGuidanceExplainUninstalling() {
        sources.forEach { (flavor, source) ->
            val keys = if (flavor == "main") {
                listOf("platform_privacy_policy_full", "device_enroll_success", "data_sharing_intro")
            } else {
                listOf("platform_privacy_policy_full")
            }
            keys.forEach { key ->
                val value = Regex(
                    """<string\s+name="$key"[^>]*>(.*?)</string>""",
                    RegexOption.DOT_MATCHES_ALL,
                ).find(source)?.groupValues?.get(1).orEmpty()
                assertTrue("$flavor/$key must explain withdrawal by uninstalling", value.contains("uninstall the app"))
            }
        }
    }
}
