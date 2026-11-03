package com.openlattice.chronicle.layout

import android.view.View
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.AppCompatTextView
import androidx.core.view.ViewCompat
import androidx.core.view.accessibility.AccessibilityNodeInfoCompat
import com.google.android.material.textfield.TextInputLayout
import com.openlattice.chronicle.R
import org.junit.Assert.assertFalse
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [26, 28])
class TitleHeadingCompatibilityTest {
    @Test
    @Config(sdk = [28])
    fun enrollmentUrlUsesItsTranslatedLabelAndDistinctHttpsHint() {
        for (locale in listOf("en", "es")) {
            RuntimeEnvironment.setQualifiers(locale)
            val controller = Robolectric.buildActivity(AppCompatActivity::class.java)
            controller.get().setTheme(R.style.AppTheme)
            controller.setup()
            try {
                val activity = controller.get()
                activity.setContentView(R.layout.activity_enrollment)
                val label = activity.findViewById<TextView>(R.id.serverUrlTextView).text.toString()
                val hint = activity.findViewById<TextInputLayout>(R.id.serverUrlTextLayout).hint.toString()
                assertEquals(activity.getString(R.string.server_url_hint), label)
                assertEquals(activity.getString(R.string.enrollment_server_hint), hint)
                assertNotEquals(label, hint)
            } finally {
                controller.pause().stop().destroy()
            }
        }
    }

    @Test fun appCompatInflationExposesEveryTitleAsAnAccessibilityHeading() {
        val controller = Robolectric.buildActivity(AppCompatActivity::class.java)
        controller.get().setTheme(R.style.AppTheme)
        controller.setup()
        try {
            val activity = controller.get()
            for ((layout, id) in listOf(
                R.layout.activity_user_identification to R.id.select_user_prompt,
                R.layout.activity_collection_orientation to R.id.orientationTitle,
                R.layout.activity_study_disclosure to R.id.studyDisclosureHeading,
            )) {
                activity.setContentView(layout)
                val title = activity.findViewById<View>(id)
                assertTrue("The production AppCompat inflater must own the title", title is AppCompatTextView)
                assertTrue(ViewCompat.isAccessibilityHeading(title))
                assertTrue(AccessibilityNodeInfoCompat.wrap(title.createAccessibilityNodeInfo()).isHeading)
            }
            activity.setContentView(R.layout.activity_user_identification)
            assertFalse(ViewCompat.isAccessibilityHeading(activity.findViewById(R.id.participant_user_btn)))
        } finally {
            controller.pause().stop().destroy()
        }
    }
}
