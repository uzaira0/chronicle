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
    fun publicAndControlledBuildsUseTheirDeclaredTlsTrustModels() {
        val utils = read("src/main/java/com/openlattice/chronicle/utils/Utils.kt")
        val buildGradle = read("build.gradle")
        assertTrue("the production host must come from BuildConfig", utils.contains("BuildConfig.CHRONICLE_PRODUCTION_HOST"))
        assertTrue("the production host must be emitted as a BuildConfig field", buildGradle.contains("CHRONICLE_PRODUCTION_HOST"))
        assertTrue(
            "controlled builds must restrict HTTPS to their operator-configured host",
            utils.contains("host in TRUSTED_SERVER_HOSTS"),
        )
        assertTrue(
            "Play must support arbitrary public HTTPS study servers through system trust",
            Regex("play\\s*\\{[\\s\\S]*?ALLOW_ANY_SERVER\", \"true\"").containsMatchIn(buildGradle),
        )
        assertFalse("distributed code must not embed tenant SPKI pins", utils.contains("CertificatePinner"))
        assertFalse("distributed code must not embed tenant certificate hashes", utils.contains("sha256/"))
    }

    @Test
    fun retiredTestInfrastructureTrustIsAbsentFromDistributedSources() {
        val buildGradle = read("build.gradle")
        val utils = read("src/main/java/com/openlattice/chronicle/utils/Utils.kt")
        assertFalse(buildGradle.contains("CHRONICLE_TESTPROD"))
        assertFalse(buildGradle.contains("ALLOW_TESTPROD_SERVER"))
        assertFalse(utils.contains("TESTPROD", ignoreCase = true))
        assertFalse(utils.contains("nip.io"))
    }

    @Test
    fun releaseBuildRejectsAndroidDebugSigningMaterial() {
        val buildGradle = read("build.gradle")
        assertTrue(
            "Release signing guard must reject the Android debug key alias",
            buildGradle.contains("androiddebugkey"),
        )
        assertTrue(
            "Release signing guard must reject Android debug keystore paths",
            buildGradle.contains("debug.keystore"),
        )
        assertTrue(
            "Release signing guard must fail only release signing tasks",
            buildGradle.contains("requiresReleaseSigning"),
        )
        assertTrue(
            "Release signing guard must use a redacted error that does not print keystore passwords",
            buildGradle.contains("Chronicle release builds must not use Android debug signing material"),
        )
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
                "com.openlattice.chronicle.services.notifications.NotificationPermissionListener",
                ".receivers.lifecycle.StartOnBoot",
                // Direct-boot bridge: exported like StartOnBoot for the system's
                // LOCKED_BOOT_COMPLETED delivery; guarded by an action check + the
                // device-protected snapshot (fail-closed), never by caller-supplied data.
                ".receivers.lifecycle.LockedBootReceiver",
                ".receivers.lifecycle.DeviceLifecycleReceiver",
                ".collection.interaction.InteractionCollectionService",
            ),
            exported,
        )

        val playExported = manifestElements("src/play/AndroidManifest.xml")
            .filter { it.androidAttr("exported") == "true" }
            .map { it.androidAttr("name").orEmpty() }
            .toSet()
        assertEquals(
            emptySet<String>(),
            playExported,
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
