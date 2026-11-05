package com.openlattice.chronicle.ui

import android.content.Context
import android.content.res.Configuration
import android.graphics.Color
import android.view.ContextThemeWrapper
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.core.graphics.ColorUtils
import androidx.test.core.app.ApplicationProvider
import com.google.android.material.button.MaterialButton
import com.openlattice.chronicle.R
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class ConsentAccessibilityAuditTest {
    private fun context(night: Int = Configuration.UI_MODE_NIGHT_NO): Context {
        val base = ApplicationProvider.getApplicationContext<Context>()
        return ContextThemeWrapper(base.createConfigurationContext(Configuration(base.resources.configuration).apply {
            uiMode = Configuration.UI_MODE_TYPE_NORMAL or night
        }), R.style.AppTheme)
    }
    private fun layout(context: Context, id: Int) = LayoutInflater.from(context).inflate(id, null)
    private fun texts(view: View): List<TextView> = when (view) {
        is TextView -> listOf(view)
        is ViewGroup -> (0 until view.childCount).flatMap { texts(view.getChildAt(it)) }
        else -> emptyList()
    }

    @Test fun asynchronousEnrollmentAndRecoveryStatusesAreLiveAndScreenTitlesAreHeadings() {
        val context = context()
        val enrollment = layout(context, R.layout.activity_enrollment)
        assertEquals(View.ACCESSIBILITY_LIVE_REGION_POLITE, enrollment.findViewById<View>(R.id.statusMessage).accessibilityLiveRegion)
        assertTrue(enrollment.findViewById<View>(R.id.studyIdTextView).isAccessibilityHeading)
        val recovery = layout(context, R.layout.activity_local_store_recovery)
        assertEquals(View.ACCESSIBILITY_LIVE_REGION_POLITE, recovery.findViewById<View>(R.id.localStoreRecoveryStatus).accessibilityLiveRegion)
        assertTrue(texts(recovery).first().isAccessibilityHeading)
        assertTrue(layout(context, R.layout.activity_collection_orientation).findViewById<View>(R.id.orientationTitle).isAccessibilityHeading)
    }

    @Test fun compositedConsentBodyAndDataSharingIntroHaveReadableContrastInBothThemes() {
        for (night in listOf(Configuration.UI_MODE_NIGHT_NO, Configuration.UI_MODE_NIGHT_YES)) {
            val context = context(night)
            val orientation = layout(context, R.layout.activity_collection_orientation)
            val ids = listOf(R.id.orientationStep, R.id.orientationRequirementDetail)
            val views = ids.map { orientation.findViewById<TextView>(it) } + texts(layout(context, R.layout.fragment_data_sharing)).filter {
                it.text.toString() == context.getString(R.string.data_sharing_intro)
            }
            val background = context.getColor(R.color.chronicle_bg)
            views.forEach { view ->
                val color = ColorUtils.setAlphaComponent(view.currentTextColor,
                    (android.graphics.Color.alpha(view.currentTextColor) * view.alpha).toInt())
                val contrast = ColorUtils.calculateContrast(ColorUtils.compositeColors(color, background), background)
                assertTrue("${view.id} night=$night contrast=$contrast", contrast >= 4.5)
            }
        }
    }

    /**
     * H-NOT-NOW-CONTRAST: both consent actions, the filled Allow and the outlined secondary "Not now"/Don't allow, keep
     * readable text on their composited background in light and dark, and stay enabled full-size tap targets.
     */
    @Test fun consentActionsHaveReadableContrastAndFullTapTargetsInBothThemes() {
        for (night in listOf(Configuration.UI_MODE_NIGHT_NO, Configuration.UI_MODE_NIGHT_YES)) {
            val context = context(night)
            val orientation = layout(context, R.layout.activity_collection_orientation)
            val screen = context.getColor(R.color.chronicle_bg)
            val minimumTarget = (48 * context.resources.displayMetrics.density).toInt()
            for (id in listOf(R.id.orientationAccept, R.id.orientationDecline)) {
                val action = orientation.findViewById<MaterialButton>(id)
                val fill = ColorUtils.compositeColors(action.backgroundTintList?.defaultColor ?: Color.TRANSPARENT, screen)
                val contrast = ColorUtils.calculateContrast(ColorUtils.compositeColors(action.currentTextColor, fill), fill)
                assertTrue("${action.text} night=$night contrast=$contrast", contrast >= 4.5)
                assertTrue(action.isEnabled)
                assertTrue("${action.text} night=$night height=${action.minimumHeight}", action.minimumHeight >= minimumTarget)
            }
        }
    }
}
