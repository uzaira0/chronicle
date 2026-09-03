package com.openlattice.chronicle.collection.interaction

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.provider.Settings
import android.text.TextUtils
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
        val expected = ComponentName(context.packageName, InteractionCollectionService::class.java.name)
            .flattenToString()
        val enabled = Settings.Secure.getString(
            context.contentResolver,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
        ) ?: return false
        val splitter = TextUtils.SimpleStringSplitter(':').apply { setString(enabled) }
        for (component in splitter) {
            if (component.equals(expected, ignoreCase = true)) return true
        }
        return false
    }

    /** Opens the system Accessibility settings so the participant can enable the service. */
    fun openAccessibilitySettings(context: Context) {
        val intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        DeviceSettingsNavigator.open(context, intent)
    }
}
