package com.openlattice.chronicle

import android.content.Context
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner

/**
 * The app-authored text around the data-choice steps says each fact once: the study-information
 * screen has one title, its enabled-functions intro neither repeats its heading nor the data
 * destination already listed above it, and the enrollment-complete message names each place in
 * the app once, by the name the app shows for it.
 */
@RunWith(RobolectricTestRunner::class)
class ParticipantCopyOnceTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()

    @Test fun studyInformationScreenHasOneTitle() {
        val controller = Robolectric.buildActivity(StudyDisclosureActivity::class.java,
            StudyDisclosureActivity.intent(context, EnrollmentTestStores.enrollmentPreview())).setup()
        try {
            val activity = controller.get()
            val title = activity.getString(R.string.disclosure_before_you_join)
            assertEquals(title, activity.title.toString())
            val shown = visibleTexts(activity.findViewById(android.R.id.content))
            assertFalse("screen title repeated in the page: $shown", shown.any { it == title })
            assertFalse("second screen title: $shown", (shown + activity.title.toString()).any { it == "Study information" })
        } finally {
            controller.pause().stop().destroy()
        }
    }

    @Test fun enabledFunctionsIntroSaysOnlyWhatItsHeadingAndTheDestinationLineDoNot() {
        val intro = context.getString(R.string.disclosure_enabled_functions_body)
        assertFalse(intro, intro.contains("enabled functions", ignoreCase = true))
        assertFalse(intro, intro.contains("study server named above"))
        assertTrue(intro, intro.contains("next screen"))
    }

    @Test fun enrollmentCompleteNamesEachPlaceOnceByItsOnScreenName() {
        val message = context.getString(R.string.device_enroll_success)
        assertEquals(message, 1, Regex("Settings").findAll(message).count())
        assertTrue(message, message.contains(context.getString(R.string.nav_data_sharing)))
        assertTrue(message, message.contains(context.getString(R.string.server_delete)))
        assertFalse(message, message.contains("Chronicle Settings"))
    }

    private fun visibleTexts(view: View): List<String> = when {
        !view.isShown -> emptyList()
        view is TextView -> listOf(view.text.toString()).filter(String::isNotBlank)
        view is ViewGroup -> (0 until view.childCount).flatMap { visibleTexts(view.getChildAt(it)) }
        else -> emptyList()
    }
}
