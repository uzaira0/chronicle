package com.openlattice.chronicle

import android.app.Dialog
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import androidx.annotation.RequiresApi
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.DialogFragment
import com.openlattice.chronicle.preferences.EnrollmentSettings
import com.openlattice.chronicle.utils.DeviceSettingsNavigator

/**
 * Asks the participant to exempt Chronicle from app hibernation / permission auto-reset
 * (Android 11+, the "Pause app activity if unused" toggle). Without the exemption, a
 * device whose Chronicle UI is never opened — the normal usage pattern for a passive
 * collection app — is force-stopped and stripped of its runtime permissions after a few
 * months of "unused" time, silently and permanently ending data collection until a human
 * re-opens the app and re-grants everything.
 */
class AppHibernationExemptionDialog : DialogFragment() {
    @RequiresApi(Build.VERSION_CODES.R)
    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        return activity?.let {
            AlertDialog.Builder(it)
                .setTitle(R.string.hibernation_exemption_header)
                .setMessage(R.string.hibernation_exemption)
                .setPositiveButton(R.string.settings) { dialog, _ ->
                    dialog.cancel()
                    val intent = Intent(
                        Intent.ACTION_AUTO_REVOKE_PERMISSIONS,
                        Uri.fromParts("package", requireContext().packageName, null)
                    )
                    DeviceSettingsNavigator.open(requireContext(), intent)
                }
                .setNegativeButton(android.R.string.cancel) { dialog, _ ->
                    EnrollmentSettings(requireContext()).toggleHibernationExemptionDialog(false)
                    dialog.cancel()
                }
                .create()
        } ?: throw IllegalStateException("Activity cannot be null")
    }
}
