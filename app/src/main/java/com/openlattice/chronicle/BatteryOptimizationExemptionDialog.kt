package com.openlattice.chronicle

import android.app.Dialog
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import androidx.annotation.RequiresApi
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.DialogFragment
import com.openlattice.chronicle.preferences.EnrollmentSettings
import com.openlattice.chronicle.utils.DeviceSettingsNavigator

class BatteryOptimizationExemptionDialog : DialogFragment() {
    @RequiresApi(Build.VERSION_CODES.M)
    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {

        return activity?.let {
            val builder = AlertDialog.Builder(it)
            builder.setTitle(R.string.battery_optimization_header)
                    .setMessage(R.string.battery_optimization_exemption)
                    .setPositiveButton(R.string.settings
                    ) { dialog, _ ->
                        dialog.cancel()
                        val intent = Intent().apply {
                            action = Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS
                        }
                        DeviceSettingsNavigator.open(requireContext(), intent)
                    }
                    .setNegativeButton(android.R.string.cancel
                    ) { dialog, _ ->
                        EnrollmentSettings(requireContext()).toggleBatteryOptimizationDialog(false)
                        dialog.cancel()
                    }

            builder.create()
        } ?: throw IllegalStateException("Activity cannot be null")
    }

}
