package com.openlattice.chronicle.ui

import android.app.Application
import android.content.Intent
import android.net.Uri
import android.view.View
import androidx.test.core.app.ApplicationProvider
import com.openlattice.chronicle.EnrollmentTestStores
import com.openlattice.chronicle.R
import com.openlattice.chronicle.StudyDisclosureActivity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowToast
import java.io.File

/**
 * Privacy and consent links must open a browser on Android 11+ (package visibility).
 * A resolveActivity() gate returns null when no browser is visible to the app, so the button
 * silently did nothing; the links now start the activity and catch ActivityNotFoundException.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [34])
class ExternalLinkContractTest {
    private val manifest = File("src/main/AndroidManifest.xml").readText()

    @Test
    fun manifestDeclaresBrowserVisibility() {
        val queries = manifest.substringAfter("<queries>").substringBefore("</queries>")
        assertTrue(queries.contains("""<action android:name="android.intent.action.VIEW" />"""))
        assertTrue(queries.contains("""<data android:scheme="https" />"""))
    }

    @Test
    fun disclosureLinksLaunchWithoutPackageVisibilityAndReportAMissingBrowser() {
        val context = ApplicationProvider.getApplicationContext<Application>()
        val baseline = EnrollmentTestStores.enrollmentPreview()
        val preview = baseline.copy(manifest = baseline.manifest.copy(participantPolicy =
            baseline.manifest.participantPolicy.copy(privacyPolicyUrl = "https://study.example/privacy",
                consentDocumentUrl = "https://study.example/consent")))
        val controller = Robolectric.buildActivity(StudyDisclosureActivity::class.java,
            StudyDisclosureActivity.intent(context, preview)).setup()
        try {
            val activity = controller.get()
            val links = mapOf(R.id.studyPrivacyButton to "https://study.example/privacy",
                R.id.studyConsentDocumentButton to "https://study.example/consent")
            for ((id, url) in links) {
                assertTrue(activity.findViewById<View>(id).performClick())
                val opened = shadowOf(activity).nextStartedActivity
                assertEquals(Intent.ACTION_VIEW, opened?.action)
                assertEquals(Uri.parse(url), opened?.data)
            }
            shadowOf(context).checkActivities(true)
            for (id in links.keys) {
                ShadowToast.reset()
                assertTrue(activity.findViewById<View>(id).performClick())
                assertEquals(context.getString(R.string.no_browser_for_link), ShadowToast.getTextOfLatestToast())
            }
        } finally {
            shadowOf(context).checkActivities(false)
            ShadowToast.reset()
            controller.pause().stop().destroy()
        }
    }
}
