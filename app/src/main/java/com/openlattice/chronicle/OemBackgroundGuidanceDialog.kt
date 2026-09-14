package com.openlattice.chronicle

import android.app.Dialog
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.DialogFragment
import com.openlattice.chronicle.preferences.EnrollmentSettings
import com.openlattice.chronicle.utils.DeviceSettingsNavigator

class OemBackgroundGuidanceDialog : DialogFragment() {
    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        return activity?.let { context ->
            val vendorLabel = when (OemBackgroundGuidance.vendorKey(Build.MANUFACTURER)) {
                "xiaomi" -> R.string.oem_background_vendor_xiaomi
                "huawei" -> R.string.oem_background_vendor_huawei
                "oppo" -> R.string.oem_background_vendor_oppo
                "vivo" -> R.string.oem_background_vendor_vivo
                "samsung" -> R.string.oem_background_vendor_samsung
                else -> throw IllegalStateException("Unsupported manufacturer")
            }
            AlertDialog.Builder(context)
                .setTitle(R.string.oem_background_guidance_title)
                .setMessage(getString(R.string.oem_background_guidance_body, getString(vendorLabel)))
                .setPositiveButton(R.string.oem_background_guidance_open_settings) { dialog, _ ->
                    EnrollmentSettings(context).toggleOemGuidanceDialog(false)
                    dialog.cancel()
                    val preferred = OemBackgroundGuidance.preferredIntents(
                        Build.MANUFACTURER,
                        context.packageName,
                        context.applicationInfo.loadLabel(context.packageManager).toString(),
                    ).firstOrNull { it.resolveActivity(context.packageManager) != null }
                        ?: Intent(Settings.ACTION_SETTINGS)
                    DeviceSettingsNavigator.open(context, preferred)
                }
                .setNegativeButton(android.R.string.cancel) { dialog, _ ->
                    EnrollmentSettings(context).toggleOemGuidanceDialog(false)
                    dialog.cancel()
                }
                .create()
        } ?: throw IllegalStateException("Activity cannot be null")
    }
}
