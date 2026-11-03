package com.openlattice.chronicle.storage

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction

/**
 * Room DAO for the `audio_content_samples` table (the opt-in `audio_content` media-metadata layer).
 * Mirrors [AudioActivitySampleDao].
 */
@Dao
interface AudioContentSampleDao : BoundedQueueWriter {
    @Query("SELECT * FROM audio_content_samples WHERE id IN (:ids)")
    fun getByIds(ids: List<String>): List<AudioContentSampleEntry>

    @Query("SELECT * FROM audio_content_samples ORDER BY timestamp ASC LIMIT :limit")
    fun getOldest(limit: Int): List<AudioContentSampleEntry>

    @Transaction
    fun insertAll(samples: List<AudioContentSampleEntry>) = insertAllUnchecked(
        boundedRows(
            UploadQueueTable.AUDIO_CONTENT, samples, AudioContentSampleEntry::persistedPayloadBytes,
        ) { it.id },
    )

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    fun insertAllUnchecked(samples: List<AudioContentSampleEntry>)

    @Query("DELETE FROM audio_content_samples WHERE id IN (:ids)")
    fun deleteByIds(ids: List<String>)

    @Query("SELECT COUNT(*) FROM audio_content_samples")
    fun count(): Int

    /** Drops every pending sample — used by the DISCARD_AND_STOP disable disposition. */
    @Query("DELETE FROM audio_content_samples")
    fun deleteAll()
}
