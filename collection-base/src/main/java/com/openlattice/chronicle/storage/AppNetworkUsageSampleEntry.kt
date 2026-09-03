package com.openlattice.chronicle.storage

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * Buffered `app_network_usage` bucket (NetworkStatsManager), one row per
 * AndroidAppNetworkUsageEvent before upload. BEHAVIORAL_METADATA-class: per-app byte counts
 * only — never payloads, destinations, domains, or URLs.
 */
@Entity(tableName = "app_network_usage_samples")
data class AppNetworkUsageSampleEntry(
    @PrimaryKey val id: String,
    val timestamp: String,
    val timezone: String,
    val packageName: String,
    val networkType: String,
    val rxBytes: Long,
    val txBytes: Long,
    val bucketStartMillis: Long,
    val bucketEndMillis: Long,
)
