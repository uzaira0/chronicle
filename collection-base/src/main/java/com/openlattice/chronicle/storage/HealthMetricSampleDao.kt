package com.openlattice.chronicle.storage

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

/** Room DAO for `health_metric_samples`. Duplicate ids de-duplicate (IGNORE). */
@Dao
interface HealthMetricSampleDao {
    @Query("SELECT * FROM health_metric_samples ORDER BY timestamp ASC LIMIT :limit")
    fun getOldest(limit: Int): List<HealthMetricSampleEntry>

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    fun insertAll(samples: List<HealthMetricSampleEntry>)

    @Query("DELETE FROM health_metric_samples WHERE id IN (:ids)")
    fun deleteByIds(ids: List<String>)

    @Query("SELECT COUNT(*) FROM health_metric_samples")
    fun count(): Int

    @Query("DELETE FROM health_metric_samples WHERE timestamp < :cutoffTimestamp")
    fun deleteOlderThan(cutoffTimestamp: String): Int

    @Query("DELETE FROM health_metric_samples")
    fun deleteAll()
}
