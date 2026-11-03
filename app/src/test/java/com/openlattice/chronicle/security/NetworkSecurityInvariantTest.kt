package com.openlattice.chronicle.security

import org.junit.Assert.assertFalse
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.w3c.dom.Element
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory

/**
 * MITM-hardening invariants for the Android app, enforced as a source-level regression guard
 * (the same style as the server's RlsCoverageTest). These would fail if a future change weakened
 * the app's transport security:
 *
 *  1. No production manifest opts into cleartext HTTP (`usesCleartextTraffic="true"`).
 *  2. The only file permitting cleartext is the **debug** network-security config (scoped to the
 *     local test backend); a release build never ships a cleartext allowance.
 *  3. Public self-host builds accept arbitrary public HTTPS study origins through Android's system
 *     trust store; controlled research builds may restrict the hostname without embedding a
 *     tenant certificate or infrastructure default.
 *  4. Release exported components and deep-link entry points stay on an explicit allowlist.
 */
class NetworkSecurityInvariantTest {

    private companion object {
        fun moduleDir(): File = sequenceOf(File("."), File("app"))
            .map { it.absoluteFile }
            .firstOrNull { File(it, "src/main/AndroidManifest.xml").isFile }
            ?: error("Could not locate the app module from cwd=${File(".").absolutePath}")

        fun read(relative: String): String {
            val f = File(moduleDir(), relative)
            require(f.isFile) { "Expected file not found: ${f.absolutePath}" }
            return f.readText()
        }
    }

    @Test
    fun productionManifestDoesNotPermitCleartext() {
        val manifest = read("src/main/AndroidManifest.xml")
        assertFalse(
            "Production manifest must not opt into cleartext HTTP (usesCleartextTraffic=true)",
            manifest.contains("usesCleartextTraffic=\"true\""),
        )
        assertFalse(
            "Production manifest must not ship a networkSecurityConfig override",
            manifest.contains("networkSecurityConfig="),
        )
        assertTrue(
            "Production manifest must disable adb/cloud backup for local encrypted app data",
            manifest.contains("android:allowBackup=\"false\""),
        )
    }

    @Test
    fun onlyDebugBuildPermitsCleartext() {
        val offenders = File(moduleDir(), "src").walkTopDown()
            .filter { it.isFile && (it.extension == "xml") }
            .filter { it.readText().contains("cleartextTrafficPermitted=\"true\"") }
            .map { it.relativeTo(moduleDir()).path.replace(File.separatorChar, '/') }
            .toList()
        // Cleartext is allowed ONLY in the debug source set, and only for the local test backend.
        assertTrue(
            "cleartext permitted outside the debug source set: $offenders",
            offenders.all { it.contains("/debug/") },
        )
    }

    @Test
    fun committedDebugTrustConfigContainsNoOperatorInfrastructure() {
        val config = read("src/debug/res/xml/debug_network_security_config.xml")
        assertFalse(config.contains(".ts.net"))
        assertFalse(config.contains("nip.io"))
        assertFalse(config.contains("@raw/isrg_root_x1"))
    }

    @Test
    fun releaseExportedComponentsStayOnExplicitAllowlist() {
        val exported = manifestElements("src/main/AndroidManifest.xml")
            .filter { it.androidAttr("exported") == "true" }
            .map { it.androidAttr("name").orEmpty() }
            .toSet()

        assertEquals(
            setOf(
                ".MainActivity",
                ".Enrollment",
                ".receivers.lifecycle.StartOnBoot",
                // Direct-boot bridge: exported like StartOnBoot for the system's
                // LOCKED_BOOT_COMPLETED delivery; guarded by an action check + the
                // device-protected snapshot (fail-closed), never by caller-supplied data.
                ".receivers.lifecycle.LockedBootReceiver",
            ),
            exported,
        )

        val amazonExported = manifestElements("src/amazon/AndroidManifest.xml")
            .filter { it.androidAttr("exported") == "true" }
            .map { it.androidAttr("name").orEmpty() }
            .toSet()
        assertEquals(
            emptySet<String>(),
            amazonExported,
        )
    }

    @Test
    fun enrollmentDeepLinksAreRestrictedToExpectedSchemesHostsAndPaths() {
        val enrollment = manifestElements("src/main/AndroidManifest.xml")
            .single { it.tagName == "activity" && it.androidAttr("name") == ".Enrollment" }
        val dataElements = enrollment.childElements("intent-filter")
            .filter { filter -> filter.childElements("category").any { it.androidAttr("name") == "android.intent.category.BROWSABLE" } }
            .flatMap { it.childElements("data") }

        val actual = dataElements.map {
            DeepLinkData(
                scheme = it.androidAttr("scheme").orEmpty(),
                host = it.androidAttr("host").orEmpty(),
                pathPattern = it.androidAttr("pathPattern"),
                pathPrefix = it.androidAttr("pathPrefix"),
            )
        }.toSet()

        assertEquals(
            setOf(DeepLinkData("chronicle", "enroll")),
            actual,
        )
        assertTrue("Enrollment deep links must not use http", actual.none { it.scheme == "http" })
        assertTrue(
            "Custom-scheme enrollment must stay scoped to chronicle://enroll",
            actual.filter { it.scheme == "chronicle" }.all { it.host == "enroll" && it.pathPattern == null && it.pathPrefix == null },
        )
    }

    private data class DeepLinkData(
        val scheme: String,
        val host: String,
        val pathPattern: String? = null,
        val pathPrefix: String? = null,
    )

    private fun manifestElements(relative: String): List<Element> {
        val file = File(moduleDir(), relative)
        val doc = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(file)
        val nodes = doc.getElementsByTagName("*")
        return (0 until nodes.length).map { nodes.item(it) as Element }
    }

    private fun Element.childElements(tag: String): List<Element> =
        (0 until childNodes.length)
            .map { childNodes.item(it) }
            .filterIsInstance<Element>()
            .filter { it.tagName == tag }

    private fun Element.androidAttr(name: String): String? =
        getAttribute("android:$name").ifBlank { null }
}
