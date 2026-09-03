package com.openlattice.chronicle.storage

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

/** Room DAO for `connectivity_state_samples`. Duplicate ids de-duplicate (IGNORE). */
@Dao
interface ConnectivityStateSampleDao {
    @Query("SELECT * FROM connectivity_state_samples ORDER BY timestamp ASC LIMIT :limit")
    fun getOldest(limit: Int): List<ConnectivityStateSampleEntry>

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    fun insertAll(samples: List<ConnectivityStateSampleEntry>)

    @Query("DELETE FROM connectivity_state_samples WHERE id IN (:ids)")
    fun deleteByIds(ids: List<String>)

    @Query("SELECT COUNT(*) FROM connectivity_state_samples")
    fun count(): Int

    @Query("DELETE FROM connectivity_state_samples WHERE timestamp < :cutoffTimestamp")
    fun deleteOlderThan(cutoffTimestamp: String): Int

    @Query("DELETE FROM connectivity_state_samples")
    fun deleteAll()
}
