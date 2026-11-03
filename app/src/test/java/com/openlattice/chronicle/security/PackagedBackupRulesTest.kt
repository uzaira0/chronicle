package com.openlattice.chronicle.security

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import com.openlattice.chronicle.ANDROID_NS
import com.openlattice.chronicle.mergedMainManifest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.w3c.dom.Element
import org.xmlpull.v1.XmlPullParser

/**
 * The backup and transfer rules the variant actually packages, after every library manifest and
 * flavor resource is merged, keep enrollment credentials and participant data on the phone. Runs once per flavor.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class PackagedBackupRulesTest {
    private val app = ApplicationProvider.getApplicationContext<Application>()

    private fun mergedApplication(): Element {
        val applications = mergedMainManifest().documentElement.getElementsByTagName("application")
        assertEquals(1, applications.length)
        return applications.item(0) as Element
    }

    @Test fun theMergedManifestDisablesBackupAndPointsAtTheExcludingRules() {
        val application = mergedApplication()
        fun attribute(name: String) = application.getAttributeNS(ANDROID_NS, name)
        assertEquals("false", attribute("allowBackup"))
        assertEquals("@xml/backup_rules", attribute("fullBackupContent"))
        assertEquals("@xml/data_extraction_rules", attribute("dataExtractionRules"))
        assertEquals("", attribute("backupAgent"))
    }

    @Test fun thePackagedRuleResourcesExcludeEveryPrivateDomain() {
        assertEquals(mapOf("full-backup-content" to PRIVATE_DOMAINS), excluded("backup_rules"))
        assertEquals(mapOf("cloud-backup" to PRIVATE_DOMAINS, "device-transfer" to PRIVATE_DOMAINS),
            excluded("data_extraction_rules"))
    }

    /** Excluded domains per section of the packaged (merged, compiled) XML resource; any include fails the test. */
    private fun excluded(resource: String): Map<String, Set<String>> {
        val id = app.resources.getIdentifier(resource, "xml", app.packageName)
        assertTrue("packaged @xml/$resource", id != 0)
        val sections = linkedMapOf<String, MutableSet<String>>()
        var section = ""
        app.resources.getXml(id).use { parser ->
            while (parser.next() != XmlPullParser.END_DOCUMENT) {
                if (parser.eventType != XmlPullParser.START_TAG) continue
                when (parser.name) {
                    "full-backup-content", "cloud-backup", "device-transfer" -> {
                        section = parser.name
                        sections[section] = mutableSetOf()
                    }
                    "exclude" -> {
                        assertEquals(".", parser.getAttributeValue(null, "path"))
                        sections.getValue(section) += parser.getAttributeValue(null, "domain")
                    }
                    "include" -> error("$resource includes app data in <$section>")
                }
            }
        }
        return sections
    }

    private companion object {
        /** Every storage domain Android's backup rules name (developer.android.com/identity/data/autobackup). */
        val PRIVATE_DOMAINS = setOf("root", "file", "database", "sharedpref", "external",
            "device_root", "device_file", "device_database", "device_sharedpref")
    }
}
