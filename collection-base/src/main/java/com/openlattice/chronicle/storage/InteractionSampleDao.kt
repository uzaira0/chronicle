package com.openlattice.chronicle.storage

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

/**
 * Room DAO for the `interaction_samples` table — the interaction-events analogue of
 * [BatterySampleDao]. `insertAll` uses [OnConflictStrategy.IGNORE], so a duplicate
 * primary-key write de-duplicates at the SQLite level rather than failing.
 */
@Dao
interface InteractionSampleDao {
    @Query("SELECT * FROM interaction_samples ORDER BY timestamp ASC LIMIT :limit")
    fun getOldest(limit: Int): List<InteractionSampleEntry>

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    fun insertAll(samples: List<InteractionSampleEntry>)

    @Query("DELETE FROM interaction_samples WHERE id IN (:ids)")
    fun deleteByIds(ids: List<String>)

    @Query("SELECT COUNT(*) FROM interaction_samples")
    fun count(): Int

    @Query("DELETE FROM interaction_samples WHERE timestamp < :cutoffTimestamp")
    fun deleteOlderThan(cutoffTimestamp: String): Int

    @Query("SELECT * FROM interaction_samples WHERE timestamp > :cursorTimestamp ORDER BY timestamp ASC LIMIT :limit")
    fun getEntriesAfterTimestamp(cursorTimestamp: String, limit: Int): List<InteractionSampleEntry>

    @Query("DELETE FROM interaction_samples WHERE timestamp <= :maxTimestamp")
    fun deleteEntriesBeforeTimestamp(maxTimestamp: String)

    /** Drops every pending sample — used by the DISCARD_AND_STOP disable disposition. */
    @Query("DELETE FROM interaction_samples")
    fun deleteAll()
}
