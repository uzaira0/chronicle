package com.openlattice.chronicle.collection.interaction

import android.accessibilityservice.AccessibilityServiceInfo
import android.app.Application
import android.content.pm.ResolveInfo
import android.content.pm.ServiceInfo
import android.view.accessibility.AccessibilityManager
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.util.ReflectionHelpers

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class AccessibilityPlatformGateTest {
    @Test @Config(sdk = [26, 36])
    fun accessibilityRequiresMasterSwitchAndOurEnabledService() {
        val app = ApplicationProvider.getApplicationContext<Application>()
        val manager = shadowOf(requireNotNull(app.getSystemService(AccessibilityManager::class.java)) { "accessibility manager available" })
        manager.setEnabled(true)
        manager.setEnabledAccessibilityServiceList(emptyList())
        assertFalse(InteractionAccessibilityOnboarding.isServiceEnabled(app))
        val info = AccessibilityServiceInfo()
        ReflectionHelpers.setField(info, "mResolveInfo", ResolveInfo().apply {
            serviceInfo = ServiceInfo().apply {
                packageName = app.packageName
                name = InteractionCollectionService::class.java.name
            }
        })
        manager.setEnabledAccessibilityServiceList(listOf(info))
        assertTrue(InteractionAccessibilityOnboarding.isServiceEnabled(app))
        manager.setEnabled(false)
        assertFalse(InteractionAccessibilityOnboarding.isServiceEnabled(app))
    }
}
