package com.openlattice.chronicle.collection

import android.content.Context
import androidx.fragment.app.Fragment

internal object DistributionSpecialAccess : CollectionSpecialAccess {
    override fun interactionAccessibilityEnabled(context: Context): Boolean = false

    override fun notificationListenerAccessGranted(context: Context): Boolean = false

    override fun openInteractionAccessibilitySettings(context: Context) = Unit

    override fun showInteractionAccessibilityDisclosure(fragment: Fragment) = Unit
}
