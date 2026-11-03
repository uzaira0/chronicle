package com.openlattice.chronicle

import android.app.Dialog
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import androidx.fragment.app.DialogFragment
import com.openlattice.chronicle.ui.updateEnrollmentSettings
import com.openlattice.chronicle.utils.DeviceSettingsNavigator

/**
 * Asks the participant to turn background data back on. With it off (or Data Saver on without an
 * exemption), Android blocks Chronicle on metered networks whenever it is not in the foreground,
 * so every scheduled upload fails and the data stays on the device.
 */
class BackgroundDataRestrictionDialog : DialogFragment() {
    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        return activity?.let {
            MaterialAlertDialogBuilder(it)
                .setTitle(R.string.background_data_restricted_header)
                .setMessage(R.string.background_data_restricted)
                .setPositiveButton(R.string.settings) { dialog, _ ->
                    dialog.cancel()
                    val intent = Intent(
                        Settings.ACTION_IGNORE_BACKGROUND_DATA_RESTRICTIONS_SETTINGS,
                        Uri.fromParts("package", requireContext().packageName, null)
                    )
                    DeviceSettingsNavigator.open(requireContext(), intent)
                }
                .setNegativeButton(android.R.string.cancel) { dialog, _ ->
                    updateEnrollmentSettings { it.toggleBackgroundDataDialog(false) }
                    dialog.cancel()
                }
                .create()
        } ?: error("Activity cannot be null")
    }
}
