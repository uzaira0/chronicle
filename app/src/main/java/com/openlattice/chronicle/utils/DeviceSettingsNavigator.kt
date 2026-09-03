package com.openlattice.chronicle.utils

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.provider.Settings
import android.util.Log

/** Resolves OEM-specific Settings intents and falls back to the general Settings screen. */
public object DeviceSettingsNavigator {
    private const val TAG = "DeviceSettingsNav"

    public fun resolvedIntent(context: Context, preferred: Intent): Intent {
        val resolved = preferred.takeIf { it.resolveActivity(context.packageManager) != null }
            ?: Intent(Settings.ACTION_SETTINGS)
        if (context !is Activity) resolved.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return resolved
    }

    public fun open(context: Context, preferred: Intent): Boolean = runCatching {
        context.startActivity(resolvedIntent(context, preferred))
        true
    }.onFailure {
        Log.w(TAG, "Unable to open device settings (${it.javaClass.simpleName})")
    }.getOrDefault(false)
}
