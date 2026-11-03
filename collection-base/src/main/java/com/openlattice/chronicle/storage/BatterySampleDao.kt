package com.openlattice.chronicle.storage

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction

/**
 * Room DAO for the `battery_samples` table — the battery-telemetry analogue of
 * [SensorSampleDao]. `insertAll` uses [OnConflictStrategy.IGNORE], so a duplicate
 * primary-key write de-duplicates at the SQLite level rather than failing.
 */
@Dao
interface BatterySampleDao : BoundedQueueWriter {
    @Query("SELECT * FROM battery_samples WHERE id IN (:ids)")
    fun getByIds(ids: List<String>): List<BatterySampleEntry>

    /** Oldest first: [BatterySampleEntry.timestamp] is a [storedInstant], so text order is time order. */
    @Query("SELECT * FROM battery_samples ORDER BY timestamp ASC LIMIT :limit")
    fun getOldest(limit: Int): List<BatterySampleEntry>

    @Transaction
    fun insertAll(samples: List<BatterySampleEntry>) = insertAllUnchecked(
        boundedRows(
            UploadQueueTable.BATTERY, samples, BatterySampleEntry::persistedPayloadBytes,
        ) { it.id },
    )

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    fun insertAllUnchecked(samples: List<BatterySampleEntry>)

    @Query("DELETE FROM battery_samples WHERE id IN (:ids)")
    fun deleteByIds(ids: List<String>)

    @Query("SELECT COUNT(*) FROM battery_samples")
    fun count(): Int

    /** Drops every pending sample — used by the DISCARD_AND_STOP disable disposition. */
    @Query("DELETE FROM battery_samples")
    fun deleteAll()
}
