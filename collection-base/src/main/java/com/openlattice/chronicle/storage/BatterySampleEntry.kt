package com.openlattice.chronicle.storage

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * Room row for one battery-telemetry sample, stored in the `battery_samples` table.
 *
 * The structured analogue of [SensorSampleEntry] for the `battery_telemetry` collection
 * module (see `docs/SENSING-EXPANSION-DESIGN.md` §5). Enum-valued fields ([chargingState],
 * [plugType], [health]) are persisted as their enum `name` strings — mirroring how
 * [SensorSampleEntry] stores `sensorType` as a string — so the table needs no Room
 * `TypeConverter`. [timestamp] is an ISO-8601 UTC string, ordered like `sensor_samples`.
 */
@Entity(tableName = "battery_samples")
data class BatterySampleEntry(
    @PrimaryKey val id: String,
    val timestamp: String,
    val timezone: String,
    val levelPercent: Int,
    val chargingState: String,
    val plugType: String,
    val temperatureDeciC: Int,
    val voltageMillivolts: Int,
    val health: String,
)
