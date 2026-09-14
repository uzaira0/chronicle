package com.openlattice.chronicle.release

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.w3c.dom.Element
import org.xml.sax.InputSource
import java.io.File
import java.io.StringReader
import java.net.URI
import java.util.Properties
import javax.xml.parsers.DocumentBuilderFactory

class StorePrivacyUrlParityTest {
    @Test
    fun storePrivacyUrlsMatchAppPrivacyUrlAndUseHttps() {
        val urls = mutableListOf<Pair<String, String>>()
        for (store in listOf("play", "amazon")) {
            val propertiesPath = "../store/$store/privacy.properties"
            val properties = Properties().apply {
                load(StringReader(File(propertiesPath).readText()))
            }
            val privacyUrl = properties.getProperty("privacy_url")
            assertTrue("$propertiesPath must define privacy_url", !privacyUrl.isNullOrBlank())
            urls += "$propertiesPath:privacy_url" to privacyUrl.orEmpty().trim()

            val listing = File("../store/$store/listing.md")
            if (store == "amazon" || listing.exists()) {
                val privacyLines = Regex("(?m)^\\s*Privacy:[ \\t]*([^\\r\\n]*)")
                    .findAll(listing.readText()).toList()
                if (store == "amazon") {
                    assertTrue("${listing.path} must contain a Privacy: line", privacyLines.isNotEmpty())
                }
                privacyLines.forEachIndexed { index, match ->
                    urls += "${listing.path}:Privacy:[$index]" to match.groupValues[1].trim()
                }
            }
        }

        val storeUrlCount = urls.size
        val parser = DocumentBuilderFactory.newInstance().newDocumentBuilder()
        for (sourceSet in listOf("main", "play", "googleServices")) {
            val path = "src/$sourceSet/res/values/strings.xml"
            val document = parser.parse(InputSource(StringReader(File(path).readText())))
            val strings = document.getElementsByTagName("string")
            for (index in 0 until strings.length) {
                val element = strings.item(index) as Element
                if (element.getAttribute("name") == "platform_privacy_policy_url") {
                    urls += "$path:platform_privacy_policy_url[$index]" to element.textContent.trim()
                }
            }
        }
        assertTrue("App resources must define platform_privacy_policy_url", urls.size > storeUrlCount)

        val appPrivacyUrl = urls[storeUrlCount].second
        val collected = urls.joinToString("\n") { (source, url) -> "$source = $url" }
        for ((source, url) in urls) {
            val uri = URI(url)
            assertTrue("$source must be an HTTPS URL: $url", uri.scheme == "https" && !uri.host.isNullOrBlank())
            assertEquals("$source differs from the app privacy URL:\n$collected", appPrivacyUrl, url)
        }
    }
}
