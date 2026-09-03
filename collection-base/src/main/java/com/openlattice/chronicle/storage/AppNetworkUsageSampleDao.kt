package com.openlattice.chronicle.storage

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

/** Room DAO for `app_network_usage_samples`. Duplicate ids de-duplicate (IGNORE). */
@Dao
interface AppNetworkUsageSampleDao {
    @Query("SELECT * FROM app_network_usage_samples ORDER BY timestamp ASC LIMIT :limit")
    fun getOldest(limit: Int): List<AppNetworkUsageSampleEntry>

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    fun insertAll(samples: List<AppNetworkUsageSampleEntry>)

    @Query("DELETE FROM app_network_usage_samples WHERE id IN (:ids)")
    fun deleteByIds(ids: List<String>)

    @Query("SELECT COUNT(*) FROM app_network_usage_samples")
    fun count(): Int

    @Query("DELETE FROM app_network_usage_samples WHERE timestamp < :cutoffTimestamp")
    fun deleteOlderThan(cutoffTimestamp: String): Int

    @Query("DELETE FROM app_network_usage_samples")
    fun deleteAll()
}
