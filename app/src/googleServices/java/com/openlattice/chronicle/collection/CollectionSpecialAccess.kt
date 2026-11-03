package com.openlattice.chronicle.collection

import android.content.Context
import androidx.fragment.app.Fragment

/** Access to services shipped only by the research artifact. */
internal interface CollectionSpecialAccess {
    fun interactionAccessibilityEnabled(context: Context): Boolean

    fun notificationListenerAccessGranted(context: Context): Boolean

    fun openInteractionAccessibilitySettings(context: Context)

    fun showInteractionAccessibilityDisclosure(fragment: Fragment)
}
