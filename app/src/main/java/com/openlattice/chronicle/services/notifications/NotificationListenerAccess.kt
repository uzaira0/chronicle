package com.openlattice.chronicle.services.notifications

import android.content.Context
import com.openlattice.chronicle.collection.DistributionRestrictedRuntime

/** Check this listener's grant, rather than a different enabled service in the same package. */
internal fun hasNotificationListenerAccess(context: Context): Boolean =
    DistributionRestrictedRuntime.notificationListenerAccessGranted(context)
