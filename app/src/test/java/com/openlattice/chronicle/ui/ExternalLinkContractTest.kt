package com.openlattice.chronicle.ui

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Privacy and consent links must open a browser on Android 11+ (package visibility).
 * A resolveActivity() gate returns null when no browser is visible to the app, so the button
 * silently did nothing; the links now start the activity and catch ActivityNotFoundException.
 */
class ExternalLinkContractTest {
    private val manifest = File("src/main/AndroidManifest.xml").readText()
    private val settings = File("src/main/java/com/openlattice/chronicle/ui/SettingsHomeFragment.kt").readText()
    private val disclosure = File("src/main/java/com/openlattice/chronicle/StudyDisclosureActivity.kt").readText()

    @Test
    fun manifestDeclaresBrowserVisibility() {
        val queries = manifest.substringAfter("<queries>").substringBefore("</queries>")
        assertTrue(queries.contains("""<action android:name="android.intent.action.VIEW" />"""))
        assertTrue(queries.contains("""<data android:scheme="https" />"""))
    }

    @Test
    fun privacyAndConsentLinksUseTheGuardedOpener() {
        assertFalse(settings.contains("privacy.resolveActivity"))
        assertTrue(settings.contains("ExternalLinks.openHttps(requireContext(), getString(R.string.platform_privacy_policy_url))"))
        assertTrue(settings.contains("ExternalLinks.openHttps(requireContext(), url)"))
        assertTrue(disclosure.contains("ExternalLinks.openHttps(this, url)"))
    }
}
