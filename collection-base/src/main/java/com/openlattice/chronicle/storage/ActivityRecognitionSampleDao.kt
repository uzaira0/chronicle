package com.openlattice.chronicle.storage

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

/** Room DAO for `activity_recognition_samples`. Duplicate ids de-duplicate (IGNORE). */
@Dao
interface ActivityRecognitionSampleDao {
    @Query("SELECT * FROM activity_recognition_samples ORDER BY timestamp ASC LIMIT :limit")
    fun getOldest(limit: Int): List<ActivityRecognitionSampleEntry>

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    fun insertAll(samples: List<ActivityRecognitionSampleEntry>)

    @Query("DELETE FROM activity_recognition_samples WHERE id IN (:ids)")
    fun deleteByIds(ids: List<String>)

    @Query("SELECT COUNT(*) FROM activity_recognition_samples")
    fun count(): Int

    @Query("DELETE FROM activity_recognition_samples WHERE timestamp < :cutoffTimestamp")
    fun deleteOlderThan(cutoffTimestamp: String): Int

    @Query("DELETE FROM activity_recognition_samples")
    fun deleteAll()
}
