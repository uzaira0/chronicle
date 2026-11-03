package com.openlattice.chronicle.collection.battery

import android.os.BatteryManager
import com.openlattice.chronicle.collection.BatteryChargingState
import com.openlattice.chronicle.collection.BatteryHealth
import com.openlattice.chronicle.collection.BatteryPlugType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** What researchers see for each code a phone's battery broadcast carries. */
class BatteryBroadcastCodesTest {

    @Test fun everyChargingStatusIsExportedAsItsState() {
        assertEquals(BatteryChargingState.CHARGING, chargingStateOf(BatteryManager.BATTERY_STATUS_CHARGING))
        assertEquals(BatteryChargingState.DISCHARGING, chargingStateOf(BatteryManager.BATTERY_STATUS_DISCHARGING))
        assertEquals(BatteryChargingState.NOT_CHARGING, chargingStateOf(BatteryManager.BATTERY_STATUS_NOT_CHARGING))
        assertEquals(BatteryChargingState.FULL, chargingStateOf(BatteryManager.BATTERY_STATUS_FULL))
        assertEquals(BatteryChargingState.UNKNOWN, chargingStateOf(BatteryManager.BATTERY_STATUS_UNKNOWN))
    }

    /** An unknown plug code (an OEM extension) drops the sample rather than exporting a guess. */
    @Test fun everyPlugTypeIsExportedAndAnUnknownCodeDropsTheSample() {
        assertEquals(BatteryPlugType.UNPLUGGED, plugTypeOf(0))
        assertEquals(BatteryPlugType.AC, plugTypeOf(BatteryManager.BATTERY_PLUGGED_AC))
        assertEquals(BatteryPlugType.USB, plugTypeOf(BatteryManager.BATTERY_PLUGGED_USB))
        assertEquals(BatteryPlugType.WIRELESS, plugTypeOf(BatteryManager.BATTERY_PLUGGED_WIRELESS))
        assertEquals(BatteryPlugType.DOCK, plugTypeOf(BatteryManager.BATTERY_PLUGGED_DOCK))
        assertNull(plugTypeOf(64))
    }

    @Test fun everyHealthCodeIsExportedAsItsHealth() {
        assertEquals(BatteryHealth.GOOD, healthOf(BatteryManager.BATTERY_HEALTH_GOOD))
        assertEquals(BatteryHealth.OVERHEAT, healthOf(BatteryManager.BATTERY_HEALTH_OVERHEAT))
        assertEquals(BatteryHealth.DEAD, healthOf(BatteryManager.BATTERY_HEALTH_DEAD))
        assertEquals(BatteryHealth.OVER_VOLTAGE, healthOf(BatteryManager.BATTERY_HEALTH_OVER_VOLTAGE))
        assertEquals(BatteryHealth.UNSPECIFIED_FAILURE, healthOf(BatteryManager.BATTERY_HEALTH_UNSPECIFIED_FAILURE))
        assertEquals(BatteryHealth.COLD, healthOf(BatteryManager.BATTERY_HEALTH_COLD))
        assertEquals(BatteryHealth.UNKNOWN, healthOf(BatteryManager.BATTERY_HEALTH_UNKNOWN))
    }
}
