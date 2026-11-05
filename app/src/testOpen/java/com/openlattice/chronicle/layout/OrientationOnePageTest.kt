package com.openlattice.chronicle.layout

import android.app.Activity
import android.content.Context
import android.graphics.Rect
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.widget.ScrollView
import android.widget.TextView
import androidx.core.graphics.Insets
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import com.google.android.material.button.MaterialButton
import com.openlattice.chronicle.R
import com.openlattice.chronicle.audit.AuditStores
import com.openlattice.chronicle.collection.CollectionModuleId
import com.openlattice.chronicle.collection.capability.DistributionModulePolicy
import com.openlattice.chronicle.collection.state.CollectionConsentCopy
import com.openlattice.chronicle.collection.state.CollectionOrientationActivity
import com.openlattice.chronicle.collection.state.CollectionStateMachine
import com.openlattice.chronicle.collection.state.ConsentPlan
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.ParameterizedRobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * One data-choice step of the enrollment wizard, for every data type this artifact can ask about,
 * required and optional: on a 1080x2400 phone (420 dpi, the Play demo emulator) at the default font
 * size the whole step, Allow and Don't allow included, fits without scrolling; each fact is said
 * once ("1 of 1 · Optional" above the data type name, one line saying what declining means, no
 * step plan, no "source study", no internal privacy category); at font scale 2.0 it may scroll but
 * nothing is clipped. Measured with real text (native graphics), status and navigation bars as the
 * API 36 emulator reports them (132 px and 63 px).
 */
@RunWith(ParameterizedRobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36])
class OrientationOnePageTest(private val module: CollectionModuleId, private val required: Boolean) {
    private val context = ApplicationProvider.getApplicationContext<Context>()

    @Test fun stepFitsOneScreenAtDefaultFontAndSaysEachFactOnce() = show(fontScale = 1.0f) { activity, root ->
        val scroll = activity.findViewById<ScrollView>(R.id.orientationScrollView)
        val content = scroll.getChildAt(0)
        val viewport = scroll.height - scroll.paddingTop - scroll.paddingBottom
        assertTrue("$module required=$required scrolls: content ${content.height}px > viewport ${viewport}px",
            content.height <= viewport)
        val decline = activity.findViewById<MaterialButton>(R.id.orientationDecline)
        val bottom = IntArray(2).also(decline::getLocationInWindow)[1] + decline.height
        assertTrue("$module required=$required: Don't allow ends at $bottom px, under the navigation bar",
            bottom <= root.height - NAV_BAR_PX)

        val label = CollectionConsentCopy.label(activity, module)
        val requirement = activity.getString(if (required) R.string.orientation_required else R.string.orientation_optional)
        assertEquals("1 of 1 · $requirement", text(activity, R.id.orientationStep))
        assertEquals(label, text(activity, R.id.orientationTitle))
        assertEquals(activity.getString(if (required) R.string.orientation_required_detail else R.string.orientation_optional_detail),
            text(activity, R.id.orientationRequirementDetail))
        assertEquals(activity.getString(R.string.allow), activity.findViewById<MaterialButton>(R.id.orientationAccept).text.toString())

        val shown = visibleTexts(root)
        shown.forEach { assertTrue("jargon on screen: '$it'", !it.contains("source study", ignoreCase = true)) }
        assertEquals("'$requirement' must appear once: $shown", 1, shown.count { it.contains(requirement, ignoreCase = true) })
        assertEquals("'$label' must appear once: $shown", 1, shown.count { it.contains(label) })
        val privacyClass = CollectionConsentCopy.template(activity, module).privacyClass
        assertTrue("internal privacy category shown: $shown", shown.none { it.contains(privacyClass) })
    }

    @Test fun stepMayScrollButClipsNothingAtFontScaleTwo() = show(fontScale = 2.0f) { _, root ->
        val issues = LayoutChecks.run(root, Rect(0, STATUS_BAR_PX, 0, NAV_BAR_PX))
        assertTrue("$module required=$required at 2.0:\n  " + issues.joinToString("\n  "), issues.isEmpty())
    }

    private fun show(fontScale: Float, check: (Activity, View) -> Unit) {
        RuntimeEnvironment.setQualifiers("en-w411dp-h914dp-port-420dpi")
        RuntimeEnvironment.setFontScale(fontScale)
        AuditStores.install(context, enrolled = false)
        val plan = if (required) ConsentPlan(listOf(module), emptyList()) else ConsentPlan(emptyList(), listOf(module))
        ActivityScenario.launch<Activity>(CollectionOrientationActivity.intent(context, plan)).use { scenario ->
            shadowOf(Looper.getMainLooper()).idle()
            scenario.onActivity { activity ->
                val root = activity.window.decorView
                ViewCompat.dispatchApplyWindowInsets(root, WindowInsetsCompat.Builder()
                    .setInsets(WindowInsetsCompat.Type.statusBars(), Insets.of(0, STATUS_BAR_PX, 0, 0))
                    .setInsets(WindowInsetsCompat.Type.navigationBars(), Insets.of(0, 0, 0, NAV_BAR_PX))
                    .build())
                root.measure(View.MeasureSpec.makeMeasureSpec(root.width, View.MeasureSpec.EXACTLY),
                    View.MeasureSpec.makeMeasureSpec(root.height, View.MeasureSpec.EXACTLY))
                root.layout(root.left, root.top, root.right, root.bottom)
                check(activity, root)
            }
        }
    }

    private fun text(activity: Activity, id: Int) = activity.findViewById<TextView>(id).text.toString()

    private fun visibleTexts(view: View): List<String> = when {
        !view.isShown -> emptyList()
        view is TextView -> listOf(view.text.toString()).filter(String::isNotBlank)
        view is ViewGroup -> (0 until view.childCount).flatMap { visibleTexts(view.getChildAt(it)) }
        else -> emptyList()
    }

    companion object {
        /** The Chronicle_FGS_Video_API36 emulator's bars (`dumpsys window`, 1080x2400 at 420 dpi). */
        private const val STATUS_BAR_PX = 132
        private const val NAV_BAR_PX = 63

        /** Every data type this artifact's wizard can ask about, each as required and as optional. */
        @JvmStatic
        @ParameterizedRobolectricTestRunner.Parameters(name = "{0}-required={1}")
        fun parameters(): List<Array<Any>> =
            CollectionStateMachine.ACK_GATED_MODULES.filter(DistributionModulePolicy::supports)
                .flatMap { module -> listOf(true, false).map { arrayOf<Any>(module, it) } }
    }
}
