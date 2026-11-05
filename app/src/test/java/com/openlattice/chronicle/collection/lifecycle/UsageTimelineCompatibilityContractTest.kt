package com.openlattice.chronicle.collection.lifecycle

import android.app.Application
import com.openlattice.chronicle.services.lifecycle.deviceLifecycleIntentFilter
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Guards the original single UsageStats timeline against duplicate lifecycle injection. */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class UsageTimelineCompatibilityContractTest {

    @Test
    fun `broadcast subscriptions exclude every event owned by UsageStats`() {
        val filter = deviceLifecycleIntentFilter()
        assertEquals(setOf("android.intent.action.ACTION_POWER_CONNECTED",
            "android.intent.action.ACTION_POWER_DISCONNECTED", "android.intent.action.BATTERY_LOW",
            "android.intent.action.BATTERY_OKAY", "android.net.conn.CONNECTIVITY_CHANGE",
            "android.os.action.POWER_SAVE_MODE_CHANGED"),
            (0 until filter.countActions()).map(filter::getAction).toSet())
    }
}
