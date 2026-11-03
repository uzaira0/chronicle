package com.openlattice.chronicle

import android.widget.TextView
import android.app.Application
import org.robolectric.annotation.Config
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Robolectric
import org.robolectric.Shadows.shadowOf

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class PlatformPolicySourceTest {
    @Test @Config(qualifiers = "xhdpi") fun policySpacingUsesDisplayDensity() {
        Robolectric.buildActivity(PlatformPolicyActivity::class.java).setup().use { host ->
            val activity = host.get()
            assertEquals(activity.resources.getDimensionPixelSize(R.dimen.eq_space_5),
                activity.findViewById<TextView>(android.R.id.text1).paddingLeft)
        }
    }

    @Test fun dashboardValuesHaveAnInitialLoadingState() {
        Robolectric.buildActivity(PlatformPolicyActivity::class.java).setup().use { host ->
            val activity = host.get()
            val layouts = mapOf(R.layout.fragment_overview to listOf(R.id.overviewStudyId, R.id.overviewParticipantId,
                R.id.overviewLastUpload, R.id.overviewLatestTimestamp, R.id.overviewCollectionStatus, R.id.overviewServerHealth),
                R.layout.fragment_uploads to listOf(R.id.uploadsRemaining),
                R.layout.fragment_data_sharing to listOf(R.id.sensorsSummary),
                R.layout.fragment_settings_home to listOf(R.id.settingsServerSummary))
            for ((layout, ids) in layouts) {
                val view = activity.layoutInflater.inflate(layout, null)
                for (id in ids) assertEquals(activity.getString(R.string.dashboard_loading), view.findViewById<TextView>(id).text.toString())
            }
        }
    }

    @Test fun policyRendersTheFlavorResourceWithoutLeavingTheApp() {
        val controller = Robolectric.buildActivity(PlatformPolicyActivity::class.java).setup()
        try {
            val activity = controller.get()
            assertFalse(activity.isFinishing)
            assertNull(shadowOf(activity).nextStartedActivity)
            assertEquals(activity.getString(R.string.platform_privacy_policy_full),
                activity.findViewById<TextView>(android.R.id.text1).text.toString())
        } finally { controller.pause().stop().destroy() }
    }
}
