package com.openlattice.chronicle

import android.app.Dialog
import android.content.DialogInterface
import android.os.Bundle
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import androidx.fragment.app.DialogFragment

/**
 * Says what notifications are for before Android's one-time notification permission dialog. It is the first startup
 * dialog: "Not now" (or back) lets the background-access dialogs follow; Continue hands over to Android's dialog,
 * whose answer does the same ([MainActivity.askNextAfterNotifications]).
 */
class NotificationRationaleDialog : DialogFragment() {
    private var permissionRequested = false

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog =
        MaterialAlertDialogBuilder(requireActivity())
            .setTitle(R.string.notification_rationale_title)
            .setMessage(R.string.notification_rationale_body)
            .setPositiveButton(R.string.continue_action) { _, _ ->
                permissionRequested = true
                (activity as? MainActivity)?.requestNotificationPermission()
            }
            .setNegativeButton(R.string.notification_rationale_not_now, null)
            .create()

    /** Not called when the dialog is only torn down for a configuration change (DialogFragment drops its listener). */
    override fun onDismiss(dialog: DialogInterface) {
        super.onDismiss(dialog)
        if (!permissionRequested) (activity as? MainActivity)?.askNextAfterNotifications()
    }

    companion object {
        const val TAG = "notificationRationale"
    }
}
