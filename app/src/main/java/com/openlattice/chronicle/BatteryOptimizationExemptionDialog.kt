package com.openlattice.chronicle

import android.app.Dialog
import android.content.Intent
import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import androidx.fragment.app.DialogFragment
import com.openlattice.chronicle.ui.updateEnrollmentSettings
import com.openlattice.chronicle.utils.DeviceSettingsNavigator

class BatteryOptimizationExemptionDialog : DialogFragment() {
    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {

        return activity?.let {
            val builder = MaterialAlertDialogBuilder(it)
            builder.setTitle(R.string.battery_optimization_header)
                    .setMessage(R.string.battery_optimization_exemption)
                    .setPositiveButton(R.string.settings
                    ) { dialog, _ ->
                        dialog.cancel()
                        DeviceSettingsNavigator.open(requireContext(), batteryOptimizationSettingsIntent(requireContext()))
                    }
                    .setNegativeButton(android.R.string.cancel
                    ) { dialog, _ ->
                        updateEnrollmentSettings { it.toggleBatteryOptimizationDialog(false) }
                        dialog.cancel()
                    }

            builder.create()
        } ?: error("Activity cannot be null")
    }

}

internal fun batteryOptimizationSettingsIntent(context: Context): Intent {
    if (hasIgnoreBatteryOptimization(context) || !BuildConfig.DIRECT_BATTERY_EXEMPTION_REQUEST ||
        context.checkSelfPermission(android.Manifest.permission.REQUEST_IGNORE_BATTERY_OPTIMIZATIONS) !=
        PackageManager.PERMISSION_GRANTED) return Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
    return Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS)
        .setData(Uri.parse("package:${context.packageName}"))
}
