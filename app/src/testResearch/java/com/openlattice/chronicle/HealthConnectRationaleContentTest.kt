package com.openlattice.chronicle

import android.app.Application
import android.content.Intent
import android.net.Uri
import android.view.ViewGroup
import android.widget.Button
import android.widget.TextView
import androidx.core.view.descendants
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [34], qualifiers = "en")
class HealthConnectRationaleContentTest {
    @Test fun theOfflinePolicyIsDisplayedAndItsPublicLinkOpensInTheBrowser() {
        val controller = Robolectric.buildActivity(HealthConnectRationaleActivity::class.java).setup()
        try {
            val activity = controller.get()
            val content = activity.findViewById<ViewGroup>(android.R.id.content)
            assertTrue(content.descendants.filterIsInstance<TextView>().any {
                it.text.toString() == activity.getString(R.string.platform_privacy_policy_full)
            })
            val button = content.descendants.filterIsInstance<Button>().single()
            assertTrue(button.performClick())
            val intent = shadowOf(activity).nextStartedActivity
            assertEquals(Intent.ACTION_VIEW, intent?.action)
            assertEquals(Uri.parse(activity.getString(R.string.platform_privacy_policy_url)), intent?.data)
        } finally { controller.pause().stop().destroy() }
    }
}
