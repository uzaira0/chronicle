package com.openlattice.chronicle.storage

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "sensor_samples",
    indices = [Index(value = ["timestamp", "id"], name = "index_sensor_samples_timestamp_id")],
)
data class SensorSampleEntry(
    @PrimaryKey val id: String,
    val sensorType: String,
    val timestamp: String,
    val timezone: String,
    val x: Float?,
    val y: Float?,
    val z: Float?,
    val w: Float?,
    val accuracy: Int?,
    val valuesJson: String? = null
)
