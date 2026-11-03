package com.openlattice.chronicle.serialization

import com.openlattice.chronicle.collection.BatteryChargingState
import com.openlattice.chronicle.collection.BatteryHealth
import com.openlattice.chronicle.collection.BatteryPlugType
import com.openlattice.chronicle.collection.BatterySample
import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.OffsetDateTime

class BatteryProvenanceWireTest {
    @Test fun moshiSendsTheCapturedVersionAndPreservesUnknownLegacyVersions() {
        val adapter = ChronicleJson.adapter<BatterySample>(BatterySample::class.java)
        val fields = ChronicleJson.moshi.adapter(Map::class.java)
        for (version in listOf(null, "2026.10.01 (80)")) {
            val sample = BatterySample("battery-1", OffsetDateTime.parse("2026-10-01T12:00:00Z"), "UTC", 42,
                BatteryChargingState.DISCHARGING, BatteryPlugType.UNPLUGGED, 220, 4000, BatteryHealth.GOOD,
                producerVersion = version)
            val json = adapter.toJson(sample)
            assertEquals(version, fields.fromJson(json)?.get("producerVersion"))
            assertEquals(sample, adapter.fromJson(json))
        }
    }
}
