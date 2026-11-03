package com.openlattice.chronicle.storage

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction

/** Room DAO for `app_network_usage_samples`. Duplicate ids de-duplicate (IGNORE). */
@Dao
interface AppNetworkUsageSampleDao : BoundedQueueWriter {
    @Query("SELECT * FROM app_network_usage_samples WHERE id IN (:ids)")
    fun getByIds(ids: List<String>): List<AppNetworkUsageSampleEntry>

    @Query("SELECT * FROM app_network_usage_samples ORDER BY timestamp ASC LIMIT :limit")
    fun getOldest(limit: Int): List<AppNetworkUsageSampleEntry>

    @Transaction
    fun insertAll(samples: List<AppNetworkUsageSampleEntry>) = insertAllUnchecked(
        boundedRows(
            UploadQueueTable.APP_NETWORK_USAGE, samples, AppNetworkUsageSampleEntry::persistedPayloadBytes,
        ) { it.id },
    )

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    fun insertAllUnchecked(samples: List<AppNetworkUsageSampleEntry>)

    @Query("DELETE FROM app_network_usage_samples WHERE id IN (:ids)")
    fun deleteByIds(ids: List<String>)

    @Query("SELECT COUNT(*) FROM app_network_usage_samples")
    fun count(): Int

    @Query("DELETE FROM app_network_usage_samples")
    fun deleteAll(): Int
}
