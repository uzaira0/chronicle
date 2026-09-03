package com.openlattice.chronicle.storage

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * Buffered `health_connect` record read from the system Health Connect store, one row per
 * AndroidHealthMetricEvent before upload. HEALTH_METRICS-class. value is stored as REAL
 * (Double); metricType is the enum name.
 */
@Entity(tableName = "health_metric_samples")
data class HealthMetricSampleEntry(
    @PrimaryKey val id: String,
    val timestamp: String,
    val timezone: String,
    val metricType: String,
    val value: Double,
    val unit: String,
    val startMillis: Long,
    val endMillis: Long,
    val sourcePackage: String?,
)
