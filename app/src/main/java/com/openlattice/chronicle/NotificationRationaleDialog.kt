package com.openlattice.chronicle

import android.app.Dialog
import android.os.Bundle
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import androidx.fragment.app.DialogFragment

/** Says what notifications are for before Android's one-time notification permission dialog. */
class NotificationRationaleDialog : DialogFragment() {
    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog =
        MaterialAlertDialogBuilder(requireActivity())
            .setTitle(R.string.notification_rationale_title)
            .setMessage(R.string.notification_rationale_body)
            .setPositiveButton(R.string.continue_action) { _, _ ->
                (activity as? MainActivity)?.requestNotificationPermission()
            }
            .setNegativeButton(R.string.notification_rationale_not_now, null)
            .create()

    companion object {
        const val TAG = "notificationRationale"
    }
}
