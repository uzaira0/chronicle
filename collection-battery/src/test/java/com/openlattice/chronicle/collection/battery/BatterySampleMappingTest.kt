package com.openlattice.chronicle.collection.battery

import com.openlattice.chronicle.collection.BatteryChargingState
import com.openlattice.chronicle.collection.BatteryHealth
import com.openlattice.chronicle.collection.BatteryPlugType
import com.openlattice.chronicle.storage.BatterySampleEntry
import org.junit.Assert.assertEquals
import org.junit.Assert.fail
import org.junit.Test

/** JVM unit tests for [toBatterySample] — the stored-row → wire-DTO conversion used by upload. */
class BatterySampleMappingTest {

    private fun entry(
        timestamp: String = "2026-05-22T12:00:00Z",
        chargingState: String = "DISCHARGING",
        plugType: String = "UNPLUGGED",
        health: String = "GOOD",
    ): BatterySampleEntry = BatterySampleEntry(
        id = "battery-row-1",
        timestamp = timestamp,
        timezone = "UTC",
        levelPercent = 64,
        chargingState = chargingState,
        plugType = plugType,
        temperatureDeciC = 298,
        voltageMillivolts = 4012,
        health = health,
    )

    @Test fun testHappyMappingRoundTrips() {
        val sample = entry().toBatterySample()
        assertEquals("battery-row-1", sample.id)
        assertEquals(64, sample.levelPercent)
        assertEquals(BatteryChargingState.DISCHARGING, sample.chargingState)
        assertEquals(BatteryPlugType.UNPLUGGED, sample.plugType)
        assertEquals(BatteryHealth.GOOD, sample.health)
        assertEquals(298, sample.temperatureDeciC)
        assertEquals(4012, sample.voltageMillivolts)
        assertEquals("UTC", sample.timezone)
    }

    @Test fun testCorruptTimestampThrows() {
        try {
            entry(timestamp = "not-a-timestamp").toBatterySample()
            fail("Expected a corrupt timestamp to throw")
        } catch (e: Exception) { /* expected — caller skips the row */ }
    }

    @Test fun testCorruptChargingStateThrows() {
        try {
            entry(chargingState = "NOT_AN_ENUM").toBatterySample()
            fail("Expected a corrupt chargingState to throw")
        } catch (e: IllegalArgumentException) { /* expected */ }
    }

    @Test fun testCorruptHealthThrows() {
        try {
            entry(health = "MELTED").toBatterySample()
            fail("Expected a corrupt health value to throw")
        } catch (e: IllegalArgumentException) { /* expected */ }
    }
}
