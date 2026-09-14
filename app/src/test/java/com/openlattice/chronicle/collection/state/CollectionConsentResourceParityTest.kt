package com.openlattice.chronicle.collection.state

import com.openlattice.chronicle.BuildConfig
import com.openlattice.chronicle.collection.CollectionModuleId
import com.openlattice.chronicle.collection.capability.DistributionChannel
import com.openlattice.chronicle.collection.capability.DistributionModulePolicy
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The per-module consent copy is authored once, in [CollectionConsentCopy], and mirrored into
 * base string resources so a translator can discover every `consent_<module>_*` key that
 * `CollectionConsentLocalization` resolves (see `res/I18N.md`). Play-supported modules land in
 * `src/main/res`; everything else lands in `src/googleServices/res`, so the Play artifact never
 * carries restricted-module copy. After editing the Kotlin copy, regenerate the XML with:
 *
 *   CONSENT_COPY_REGENERATE=1 ./gradlew :app:testOpenDebugUnitTest --tests '*ConsentResourceParity*'
 */
class CollectionConsentResourceParityTest {

    private val fullBuild = BuildConfig.ALLOW_RESTRICTED_RESEARCH_PERMISSIONS &&
        BuildConfig.HAS_HEALTH_CONNECT &&
        BuildConfig.HAS_APP_NETWORK_USAGE

    @Test fun baseResourcesMirrorTheKotlinCopy() {
        val (play, restricted) = CollectionConsentCopy.templates.entries
            .partition { DistributionModulePolicy.supports(DistributionChannel.PLAY, it.key) }
        check(File("src/main/res/values/consent_copy.xml"), render(play))
        // A minimal build compiles none of the restricted templates, so only a full build can
        // vouch for the googleServices file.
        if (fullBuild) check(File("src/googleServices/res/values/consent_copy.xml"), render(restricted))
    }

    private fun check(file: File, expected: String) {
        if (System.getenv("CONSENT_COPY_REGENERATE") == "1") file.writeText(expected)
        assertEquals("${file.path} is stale; regenerate with CONSENT_COPY_REGENERATE=1", expected, file.readText())
    }

    private fun render(
        entries: List<Map.Entry<CollectionModuleId, CollectionConsentCopy.ModuleTemplate>>,
    ): String = buildString {
        appendLine("""<?xml version="1.0" encoding="utf-8"?>""")
        appendLine(
            "<!-- Generated from CollectionConsentCopy.kt by CollectionConsentResourceParityTest. " +
                "Do not edit: change the Kotlin copy and regenerate. Translations override these keys per locale. -->",
        )
        appendLine("<resources>")
        entries.sortedBy { it.key.id }.forEach { (id, template) ->
            val key = "consent_" + id.id.replace('-', '_')
            string("${key}_label", template.label)
            string("${key}_privacy_class", template.privacyClass)
            array("${key}_collects", template.whatItCollects)
            array("${key}_not_collects", template.whatItDoesNotCollect)
            if (template.caveats.isNotEmpty()) array("${key}_caveats", template.caveats)
            if (id == CollectionModuleId.HEALTH_CONNECT) {
                string("${key}_collects_trailer", CollectionConsentCopy.HEALTH_CONNECT_COLLECTS_TRAILER)
            }
        }
        appendLine("</resources>")
    }

    private fun StringBuilder.string(name: String, value: String) {
        appendLine("""    <string name="$name">${escape(value)}</string>""")
    }

    private fun StringBuilder.array(name: String, items: List<String>) {
        appendLine("""    <string-array name="$name">""")
        items.forEach { appendLine("        <item>${escape(it)}</item>") }
        appendLine("    </string-array>")
    }

    /** Android resource escaping: XML entities plus backslash-escaped quotes. */
    private fun escape(value: String): String = value
        .replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")
        .replace("'", "\\'")
        .replace("\"", "\\\"")
}
