package com.openlattice.chronicle.storage

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction

/**
 * Room DAO for the `interaction_samples` table — the interaction-events analogue of
 * [BatterySampleDao]. `insertAll` uses [OnConflictStrategy.IGNORE], so a duplicate
 * primary-key write de-duplicates at the SQLite level rather than failing.
 */
@Dao
interface InteractionSampleDao : BoundedQueueWriter {
    @Query("SELECT * FROM interaction_samples WHERE id IN (:ids)")
    fun getByIds(ids: List<String>): List<InteractionSampleEntry>

    @Query("SELECT * FROM interaction_samples ORDER BY timestamp ASC LIMIT :limit")
    fun getOldest(limit: Int): List<InteractionSampleEntry>

    @Transaction
    fun insertAll(samples: List<InteractionSampleEntry>) = insertAllUnchecked(
        boundedRows(
            UploadQueueTable.INTERACTION, samples, InteractionSampleEntry::persistedPayloadBytes,
        ) { it.id },
    )

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    fun insertAllUnchecked(samples: List<InteractionSampleEntry>)

    @Query("DELETE FROM interaction_samples WHERE id IN (:ids)")
    fun deleteByIds(ids: List<String>)

    @Query("SELECT COUNT(*) FROM interaction_samples")
    fun count(): Int

    /** Drops every pending sample — used by the DISCARD_AND_STOP disable disposition. */
    @Query("DELETE FROM interaction_samples")
    fun deleteAll()
}
