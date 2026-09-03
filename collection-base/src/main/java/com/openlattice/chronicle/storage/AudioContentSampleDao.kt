package com.openlattice.chronicle.storage

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

/**
 * Room DAO for the `audio_content_samples` table (the opt-in `audio_content` media-metadata layer).
 * Mirrors [AudioActivitySampleDao].
 */
@Dao
interface AudioContentSampleDao {
    @Query("SELECT * FROM audio_content_samples ORDER BY timestamp ASC LIMIT :limit")
    fun getOldest(limit: Int): List<AudioContentSampleEntry>

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    fun insertAll(samples: List<AudioContentSampleEntry>)

    @Query("DELETE FROM audio_content_samples WHERE id IN (:ids)")
    fun deleteByIds(ids: List<String>)

    @Query("SELECT COUNT(*) FROM audio_content_samples")
    fun count(): Int

    @Query("DELETE FROM audio_content_samples WHERE timestamp < :cutoffTimestamp")
    fun deleteOlderThan(cutoffTimestamp: String): Int

    /** Drops every pending sample — used by the DISCARD_AND_STOP disable disposition. */
    @Query("DELETE FROM audio_content_samples")
    fun deleteAll()
}
