package com.openlattice.chronicle.collection.interaction

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.provider.Settings
import android.accessibilityservice.AccessibilityServiceInfo
import android.view.accessibility.AccessibilityManager
import com.openlattice.chronicle.utils.DeviceSettingsNavigator

/**
 * Onboarding primitive for the `interaction_events` module (`docs/SENSING-EXPANSION-DESIGN.md`
 * §6, §10): the participant must enable [InteractionCollectionService] in system Accessibility
 * settings — Android does not let an app grant itself an accessibility service.
 *
 * This exposes the two operations a consent/Data-Sharing surface needs:
 *  - [isServiceEnabled] — whether the participant has turned the service on, so the UI can show
 *    "needs setup" when the module is accepted but the service is still off;
 *  - [openAccessibilitySettings] — deep-link the participant to the system settings screen.
 *
 * NOTE (follow-up): the Data Sharing tab should surface a "Enable in Settings" affordance for
 * interaction_events using these, and re-check [isServiceEnabled] on resume. Wiring that into
 * the fragment + verifying the flow is part of the module's on-device QA.
 */
object InteractionAccessibilityOnboarding {

    /** True if the participant has enabled [InteractionCollectionService] in Accessibility settings. */
    fun isServiceEnabled(context: Context): Boolean {
        val manager = context.getSystemService(AccessibilityManager::class.java) ?: return false
        if (!manager.isEnabled) return false
        val expected = ComponentName(context, InteractionCollectionService::class.java)
        return manager.getEnabledAccessibilityServiceList(AccessibilityServiceInfo.FEEDBACK_ALL_MASK).any {
            val service = it.resolveInfo.serviceInfo
            ComponentName(service.packageName, service.name) == expected
        }
    }

    /** Opens the system Accessibility settings so the participant can enable the service. */
    fun openAccessibilitySettings(context: Context) {
        val intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        DeviceSettingsNavigator.open(context, intent)
    }
}
