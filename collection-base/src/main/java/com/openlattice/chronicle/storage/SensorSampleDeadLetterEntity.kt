package com.openlattice.chronicle.storage

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * Encrypted local quarantine for a sensor row that could not be mapped to the wire contract.
 *
 * Keeping the original fields makes the data diagnosable/recoverable without pretending it was
 * delivered. [reason] is a redaction-safe exception class name, never an exception message.
 */
@Entity(tableName = "sensor_sample_dead_letters")
data class SensorSampleDeadLetterEntity(
    @PrimaryKey val sampleId: String,
    val sensorType: String,
    val timestamp: String,
    val timezone: String,
    val x: Float?,
    val y: Float?,
    val z: Float?,
    val w: Float?,
    val accuracy: Int?,
    val valuesJson: String?,
    val quarantinedAt: String,
    val reason: String,
)
