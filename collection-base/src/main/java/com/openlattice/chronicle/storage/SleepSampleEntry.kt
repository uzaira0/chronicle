package com.openlattice.chronicle.storage

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * Buffered `sleep` sample (Play Services Sleep API), one row per AndroidSleepEvent before
 * upload. Enums (eventType / segmentStatus) are stored as the enum name; timestamp is the
 * ISO-8601 UTC string. HEALTH_METRICS-class, content-free.
 */
@Entity(tableName = "sleep_samples")
data class SleepSampleEntry(
    @PrimaryKey val id: String,
    val timestamp: String,
    val timezone: String,
    val eventType: String,
    val segmentStartMillis: Long?,
    val segmentEndMillis: Long?,
    val segmentStatus: String?,
    val confidence: Int?,
    val light: Int?,
    val motion: Int?,
)
