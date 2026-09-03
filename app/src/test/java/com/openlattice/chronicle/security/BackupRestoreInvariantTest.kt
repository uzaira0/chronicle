package com.openlattice.chronicle.security

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.w3c.dom.Element
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory

class BackupRestoreInvariantTest {

    private companion object {
        val REQUIRED_BACKUP_DOMAINS = setOf(
            "root",
            "file",
            "database",
            "sharedpref",
            "external",
            "device_root",
            "device_file",
            "device_database",
            "device_sharedpref",
        )

        fun moduleDir(): File = sequenceOf(File("."), File("app"))
            .map { it.absoluteFile }
            .firstOrNull { File(it, "src/main/AndroidManifest.xml").isFile }
            ?: error("Could not locate the app module from cwd=${File(".").absolutePath}")

        fun read(relative: String): String {
            val file = File(moduleDir(), relative)
            require(file.isFile) { "Expected file not found: ${file.absolutePath}" }
            return file.readText()
        }

        fun parse(relative: String): Element {
            val file = File(moduleDir(), relative)
            require(file.isFile) { "Expected file not found: ${file.absolutePath}" }
            return DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(file).documentElement
        }
    }

    @Test
    fun manifestDisablesBackupAndDeclaresBothBackupRuleFormats() {
        val manifest = read("src/main/AndroidManifest.xml")

        assertTrue(manifest.contains("android:allowBackup=\"false\""))
        assertTrue(manifest.contains("android:fullBackupContent=\"@xml/backup_rules\""))
        assertTrue(manifest.contains("android:dataExtractionRules=\"@xml/data_extraction_rules\""))
    }

    @Test
    fun legacyBackupRulesExcludeAllPrivateStorageDomains() {
        val root = parse("src/main/res/xml/backup_rules.xml")

        assertEquals("full-backup-content", root.tagName)
        assertEquals(REQUIRED_BACKUP_DOMAINS, excludedDomains(root))
        assertTrue("Legacy backup rules must not include any app data", root.childElements("include").isEmpty())
    }

    @Test
    fun androidTwelvePlusDataExtractionRulesExcludeCloudAndDeviceTransfer() {
        val root = parse("src/main/res/xml/data_extraction_rules.xml")

        assertEquals("data-extraction-rules", root.tagName)

        val cloudBackup = root.singleChild("cloud-backup")
        assertEquals("true", cloudBackup.getAttribute("disableIfNoEncryptionCapabilities"))
        assertEquals(REQUIRED_BACKUP_DOMAINS, excludedDomains(cloudBackup))
        assertTrue("Cloud backup rules must not include any app data", cloudBackup.childElements("include").isEmpty())

        val deviceTransfer = root.singleChild("device-transfer")
        assertEquals(REQUIRED_BACKUP_DOMAINS, excludedDomains(deviceTransfer))
        assertTrue("Device-transfer rules must not include any app data", deviceTransfer.childElements("include").isEmpty())
    }

    private fun excludedDomains(parent: Element): Set<String> =
        parent.childElements("exclude")
            .onEach { exclude ->
                assertEquals(".", exclude.getAttribute("path"))
            }
            .map { it.getAttribute("domain") }
            .toSet()

    private fun Element.singleChild(tag: String): Element =
        childElements(tag).singleOrNull()
            ?: error("Expected exactly one <$tag> under <$tagName>")

    private fun Element.childElements(tag: String): List<Element> =
        (0 until childNodes.length)
            .map { childNodes.item(it) }
            .filterIsInstance<Element>()
            .filter { it.tagName == tag }
}
