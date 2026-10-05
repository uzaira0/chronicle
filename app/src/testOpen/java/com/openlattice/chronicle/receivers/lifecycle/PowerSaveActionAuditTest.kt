package com.openlattice.chronicle.receivers.lifecycle

import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.os.PowerManager
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class PowerSaveActionAuditTest {
    @Test fun unrelatedActionNeverObservesPowerStateOrStartsTransitionWork() {
        val base = ApplicationProvider.getApplicationContext<Context>()
        var observations = 0
        val context = object : ContextWrapper(base) {
            override fun getSystemService(name: String): Any? {
                if (name == Context.POWER_SERVICE) observations++
                return super.getSystemService(name)
            }
        }
        val receiver = PowerSaveModeReceiver()
        receiver.onReceive(context, Intent("example.unrelated"))
        assertEquals(0, observations)
        receiver.onReceive(context, Intent(PowerManager.ACTION_POWER_SAVE_MODE_CHANGED))
        assertEquals(1, observations)
    }
}
