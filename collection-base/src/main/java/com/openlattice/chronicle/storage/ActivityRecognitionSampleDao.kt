package com.openlattice.chronicle.storage

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction

/** Room DAO for `activity_recognition_samples`. Duplicate ids de-duplicate (IGNORE). */
@Dao
interface ActivityRecognitionSampleDao : BoundedQueueWriter {
    @Query("SELECT * FROM activity_recognition_samples WHERE id IN (:ids)")
    fun getByIds(ids: List<String>): List<ActivityRecognitionSampleEntry>

    @Query("SELECT * FROM activity_recognition_samples ORDER BY timestamp ASC LIMIT :limit")
    fun getOldest(limit: Int): List<ActivityRecognitionSampleEntry>

    @Transaction
    fun insertAll(samples: List<ActivityRecognitionSampleEntry>) = insertAllUnchecked(
        boundedRows(
            UploadQueueTable.ACTIVITY_RECOGNITION, samples, ActivityRecognitionSampleEntry::persistedPayloadBytes,
        ) { it.id },
    )

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    fun insertAllUnchecked(samples: List<ActivityRecognitionSampleEntry>)

    @Query("DELETE FROM activity_recognition_samples WHERE id IN (:ids)")
    fun deleteByIds(ids: List<String>)

    @Query("SELECT COUNT(*) FROM activity_recognition_samples")
    fun count(): Int

    @Query("DELETE FROM activity_recognition_samples")
    fun deleteAll()
}
