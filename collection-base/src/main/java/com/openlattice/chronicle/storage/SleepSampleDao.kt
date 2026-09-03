package com.openlattice.chronicle.storage

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

/** Room DAO for `sleep_samples`. Duplicate ids de-duplicate at SQLite level (IGNORE). */
@Dao
interface SleepSampleDao {
    @Query("SELECT * FROM sleep_samples ORDER BY timestamp ASC LIMIT :limit")
    fun getOldest(limit: Int): List<SleepSampleEntry>

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    fun insertAll(samples: List<SleepSampleEntry>)

    @Query("DELETE FROM sleep_samples WHERE id IN (:ids)")
    fun deleteByIds(ids: List<String>)

    @Query("SELECT COUNT(*) FROM sleep_samples")
    fun count(): Int

    @Query("DELETE FROM sleep_samples WHERE timestamp < :cutoffTimestamp")
    fun deleteOlderThan(cutoffTimestamp: String): Int

    @Query("DELETE FROM sleep_samples")
    fun deleteAll()
}
