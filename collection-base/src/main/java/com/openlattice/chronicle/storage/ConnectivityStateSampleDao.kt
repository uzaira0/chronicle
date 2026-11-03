package com.openlattice.chronicle.storage

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction

/** Room DAO for `connectivity_state_samples`. Duplicate ids de-duplicate (IGNORE). */
@Dao
interface ConnectivityStateSampleDao : BoundedQueueWriter {
    @Query("SELECT * FROM connectivity_state_samples WHERE id IN (:ids)")
    fun getByIds(ids: List<String>): List<ConnectivityStateSampleEntry>

    @Query("SELECT * FROM connectivity_state_samples ORDER BY timestamp ASC LIMIT :limit")
    fun getOldest(limit: Int): List<ConnectivityStateSampleEntry>

    @Transaction
    fun insertAll(samples: List<ConnectivityStateSampleEntry>) = insertAllUnchecked(
        boundedRows(
            UploadQueueTable.CONNECTIVITY_STATE, samples, ConnectivityStateSampleEntry::persistedPayloadBytes,
        ) { it.id },
    )

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    fun insertAllUnchecked(samples: List<ConnectivityStateSampleEntry>)

    @Query("DELETE FROM connectivity_state_samples WHERE id IN (:ids)")
    fun deleteByIds(ids: List<String>)

    @Query("SELECT COUNT(*) FROM connectivity_state_samples")
    fun count(): Int

    @Query("DELETE FROM connectivity_state_samples")
    fun deleteAll()
}
