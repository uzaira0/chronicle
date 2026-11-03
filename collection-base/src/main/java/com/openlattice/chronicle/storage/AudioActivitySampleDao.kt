package com.openlattice.chronicle.storage

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction

/**
 * Room DAO for the `audio_activity_samples` table — the audio analogue of [InteractionSampleDao].
 * `insertAll` uses [OnConflictStrategy.IGNORE], so a duplicate primary-key write de-duplicates at
 * the SQLite level rather than failing.
 */
@Dao
interface AudioActivitySampleDao : BoundedQueueWriter {
    @Query("SELECT * FROM audio_activity_samples WHERE id IN (:ids)")
    fun getByIds(ids: List<String>): List<AudioActivitySampleEntry>

    @Query("SELECT * FROM audio_activity_samples ORDER BY timestamp ASC LIMIT :limit")
    fun getOldest(limit: Int): List<AudioActivitySampleEntry>

    @Transaction
    fun insertAll(samples: List<AudioActivitySampleEntry>) = insertAllUnchecked(
        boundedRows(
            UploadQueueTable.AUDIO_ACTIVITY, samples, AudioActivitySampleEntry::persistedPayloadBytes,
        ) { it.id },
    )

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    fun insertAllUnchecked(samples: List<AudioActivitySampleEntry>)

    @Query("DELETE FROM audio_activity_samples WHERE id IN (:ids)")
    fun deleteByIds(ids: List<String>)

    @Query("SELECT COUNT(*) FROM audio_activity_samples")
    fun count(): Int

    /** Drops every pending sample — used by the DISCARD_AND_STOP disable disposition. */
    @Query("DELETE FROM audio_activity_samples")
    fun deleteAll()
}
