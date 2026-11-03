package com.openlattice.chronicle.collection.activity

import android.content.Context

/** Distribution boundary for Google Play Services sleep/activity registration. */
public interface ActivityRecognitionRegistration {
    public fun isAvailable(context: Context): Boolean
    public fun ensureRegistration(context: Context)
    public fun unregisterAll(context: Context)
}

public object ActivityRecognitionIntegration {
    private val registration: ActivityRecognitionRegistration = DistributionActivityRecognitionRegistration()

    public fun isAvailable(context: Context): Boolean = registration.isAvailable(context)
    public fun ensureRegistration(context: Context): Unit = registration.ensureRegistration(context)
    public fun unregisterAll(context: Context): Unit = registration.unregisterAll(context)
}
