package com.openlattice.chronicle.collection.battery

import com.openlattice.chronicle.collection.BatteryChargingState
import com.openlattice.chronicle.collection.BatteryHealth
import com.openlattice.chronicle.collection.BatteryPlugType
import com.openlattice.chronicle.collection.BatterySample
import com.openlattice.chronicle.storage.BatterySampleEntry
import java.time.OffsetDateTime

/**
 * Converts a stored [BatterySampleEntry] row into the [BatterySample] wire DTO for upload
 * (see `docs/SENSING-EXPANSION-DESIGN.md` §5).
 *
 * Throws if the row is corrupt — an unparseable [BatterySampleEntry.timestamp], or an
 * enum-name string that is not a known [BatteryChargingState] / [BatteryPlugType] /
 * [BatteryHealth]. The upload path catches this per row (via `mapNotNull`) so one corrupt
 * row never aborts a batch, mirroring the sensor upload's malformed-sample quarantine.
 */
public fun BatterySampleEntry.toBatterySample(): BatterySample = BatterySample(
    id = id,
    timestamp = OffsetDateTime.parse(timestamp),
    timezone = timezone,
    levelPercent = levelPercent,
    chargingState = BatteryChargingState.valueOf(chargingState),
    plugType = BatteryPlugType.valueOf(plugType),
    temperatureDeciC = temperatureDeciC,
    voltageMillivolts = voltageMillivolts,
    health = BatteryHealth.valueOf(health),
)
