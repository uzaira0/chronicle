package com.openlattice.chronicle.collection.battery

import android.app.Application
import android.content.Intent
import android.os.BatteryManager
import androidx.test.core.app.ApplicationProvider
import com.openlattice.chronicle.collection.BatteryPlugType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [28])
class AndroidBatterySampleSourceTest {
    private val context = ApplicationProvider.getApplicationContext<Application>()
    private fun measured() = Intent(Intent.ACTION_BATTERY_CHANGED)
        .putExtra(BatteryManager.EXTRA_LEVEL, 50).putExtra(BatteryManager.EXTRA_SCALE, 100)
        .putExtra(BatteryManager.EXTRA_PLUGGED, BatteryManager.BATTERY_PLUGGED_USB)
        .putExtra(BatteryManager.EXTRA_TEMPERATURE, 0).putExtra(BatteryManager.EXTRA_VOLTAGE, 4000)

    // Android publishes its battery sample as a sticky broadcast.
    @Suppress("DEPRECATION")
    @Test fun absentMeasurementsAndUnknownPlugStateAreUnavailable() {
        val source = AndroidBatterySampleSource(context)
        for (extra in listOf(BatteryManager.EXTRA_TEMPERATURE, BatteryManager.EXTRA_VOLTAGE, BatteryManager.EXTRA_PLUGGED)) {
            context.sendStickyBroadcast(measured().apply { removeExtra(extra) })
            assertNull("Missing measurement must not become a measured zero: $extra", source.read())
        }
        context.sendStickyBroadcast(measured().putExtra(BatteryManager.EXTRA_PLUGGED, Int.MAX_VALUE))
        assertNull(source.read())
        context.sendStickyBroadcast(measured())
        val reading = requireNotNull(source.read())
        assertEquals(0, reading.temperatureDeciC)
        assertEquals(4000, reading.voltageMillivolts)
        assertEquals(BatteryPlugType.USB, reading.plugType)
    }
}
