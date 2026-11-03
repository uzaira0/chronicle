package com.openlattice.chronicle.storage

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction

/** Room DAO for `health_metric_samples`. Duplicate ids de-duplicate (IGNORE). */
@Dao
interface HealthMetricSampleDao : BoundedQueueWriter {
    @Query("SELECT * FROM health_metric_samples WHERE id IN (:ids)")
    fun getByIds(ids: List<String>): List<HealthMetricSampleEntry>

    @Query("SELECT * FROM health_metric_samples ORDER BY timestamp ASC LIMIT :limit")
    fun getOldest(limit: Int): List<HealthMetricSampleEntry>

    @Transaction
    fun insertAll(samples: List<HealthMetricSampleEntry>) = insertAllUnchecked(
        boundedRows(
            UploadQueueTable.HEALTH_CONNECT, samples, HealthMetricSampleEntry::persistedPayloadBytes,
        ) { it.id },
    )

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    fun insertAllUnchecked(samples: List<HealthMetricSampleEntry>)

    @Query("DELETE FROM health_metric_samples WHERE id IN (:ids)")
    fun deleteByIds(ids: List<String>)

    @Query("SELECT COUNT(*) FROM health_metric_samples")
    fun count(): Int

    @Query("DELETE FROM health_metric_samples")
    fun deleteAll()
}
