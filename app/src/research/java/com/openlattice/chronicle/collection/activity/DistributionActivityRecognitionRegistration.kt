package com.openlattice.chronicle.collection.activity

import android.content.Context

public class DistributionActivityRecognitionRegistration : ActivityRecognitionRegistration {
    override fun isAvailable(context: Context): Boolean = SleepActivityCaptureController.isAvailable(context)
    override fun ensureRegistration(context: Context): Unit =
        SleepActivityCaptureController.ensureRegistration(context)
    override fun unregisterAll(context: Context): Unit = SleepActivityCaptureController.unregisterAll(context)
}
