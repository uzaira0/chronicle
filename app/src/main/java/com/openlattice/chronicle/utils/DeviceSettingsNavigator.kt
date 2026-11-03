package com.openlattice.chronicle.utils

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.provider.Settings
import android.util.Log

/** Resolves OEM-specific Settings intents and falls back to the general Settings screen. */
public object DeviceSettingsNavigator {
    private const val TAG = "DeviceSettingsNav"

    /**
     * Whether a screen on this phone handles [intent]. `Intent.resolveActivity` returns an explicit intent's own
     * component without asking the package manager, so a vendor screen missing from this phone would pass it.
     */
    public fun canOpen(context: Context, intent: Intent): Boolean = runCatching {
        context.packageManager.resolveActivity(intent, PackageManager.MATCH_DEFAULT_ONLY) != null
    }.getOrDefault(false)

    public fun resolvedIntent(context: Context, preferred: Intent): Intent {
        val resolved = preferred.takeIf { canOpen(context, it) }
            ?: Intent(Settings.ACTION_SETTINGS)
        if (context !is Activity) resolved.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return resolved
    }

    public fun open(context: Context, preferred: Intent): Boolean {
        val resolved = resolvedIntent(context, preferred)
        return launch(context, resolved) || (resolved.action != Settings.ACTION_SETTINGS &&
            launch(context, resolvedIntent(context, Intent(Settings.ACTION_SETTINGS))))
    }

    private fun launch(context: Context, intent: Intent): Boolean = runCatching {
        context.startActivity(intent)
        true
    }.onFailure {
        Log.w(TAG, "Unable to open device settings (${it.javaClass.simpleName})")
    }.getOrDefault(false)
}
