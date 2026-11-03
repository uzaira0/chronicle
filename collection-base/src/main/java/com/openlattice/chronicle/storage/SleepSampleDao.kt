package com.openlattice.chronicle.storage

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction

/** Room DAO for `sleep_samples`. Duplicate ids de-duplicate at SQLite level (IGNORE). */
@Dao
interface SleepSampleDao : BoundedQueueWriter {
    @Query("SELECT * FROM sleep_samples WHERE id IN (:ids)")
    fun getByIds(ids: List<String>): List<SleepSampleEntry>

    @Query("SELECT * FROM sleep_samples ORDER BY timestamp ASC LIMIT :limit")
    fun getOldest(limit: Int): List<SleepSampleEntry>

    @Transaction
    fun insertAll(samples: List<SleepSampleEntry>) = insertAllUnchecked(
        boundedRows(
            UploadQueueTable.SLEEP, samples, SleepSampleEntry::persistedPayloadBytes,
        ) { it.id },
    )

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    fun insertAllUnchecked(samples: List<SleepSampleEntry>)

    @Query("DELETE FROM sleep_samples WHERE id IN (:ids)")
    fun deleteByIds(ids: List<String>)

    @Query("SELECT COUNT(*) FROM sleep_samples")
    fun count(): Int

    @Query("DELETE FROM sleep_samples")
    fun deleteAll()
}
