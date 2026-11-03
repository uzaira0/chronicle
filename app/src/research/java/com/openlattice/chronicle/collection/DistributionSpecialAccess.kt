package com.openlattice.chronicle.collection

import android.content.Context
import androidx.fragment.app.Fragment
import android.app.NotificationManager
import android.content.ComponentName
import android.os.Build
import androidx.appcompat.app.AlertDialog
import androidx.core.app.NotificationManagerCompat
import com.openlattice.chronicle.R
import com.openlattice.chronicle.collection.interaction.InteractionAccessibilityOnboarding
import com.openlattice.chronicle.services.notifications.NotificationListener

internal object DistributionSpecialAccess : CollectionSpecialAccess {
    override fun interactionAccessibilityEnabled(context: Context): Boolean =
        InteractionAccessibilityOnboarding.isServiceEnabled(context)

    override fun notificationListenerAccessGranted(context: Context): Boolean =
        // Component-specific grants start at API 27: https://developer.android.com/reference/android/app/NotificationManager#isNotificationListenerAccessGranted(android.content.ComponentName)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            context.getSystemService(NotificationManager::class.java)?.isNotificationListenerAccessGranted(
                ComponentName(context, NotificationListener::class.java)) == true
        } else {
            NotificationManagerCompat.getEnabledListenerPackages(context).contains(context.packageName)
        }

    override fun openInteractionAccessibilitySettings(context: Context) =
        InteractionAccessibilityOnboarding.openAccessibilitySettings(context)

    override fun showInteractionAccessibilityDisclosure(fragment: Fragment) {
        AlertDialog.Builder(fragment.requireContext())
            .setTitle(R.string.accessibility_disclosure_title)
            .setMessage(R.string.accessibility_disclosure_body)
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(R.string.continue_action) { _, _ ->
                openInteractionAccessibilitySettings(fragment.requireContext())
            }
            .show()
    }
}
