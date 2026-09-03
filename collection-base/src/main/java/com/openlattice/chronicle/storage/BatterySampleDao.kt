package com.openlattice.chronicle.storage

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

/**
 * Room DAO for the `battery_samples` table — the battery-telemetry analogue of
 * [SensorSampleDao]. `insertAll` uses [OnConflictStrategy.IGNORE], so a duplicate
 * primary-key write de-duplicates at the SQLite level rather than failing.
 */
@Dao
interface BatterySampleDao {
    @Query("SELECT * FROM battery_samples ORDER BY timestamp ASC LIMIT :limit")
    fun getOldest(limit: Int): List<BatterySampleEntry>

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    fun insertAll(samples: List<BatterySampleEntry>)

    @Query("DELETE FROM battery_samples WHERE id IN (:ids)")
    fun deleteByIds(ids: List<String>)

    @Query("SELECT COUNT(*) FROM battery_samples")
    fun count(): Int

    @Query("DELETE FROM battery_samples WHERE timestamp < :cutoffTimestamp")
    fun deleteOlderThan(cutoffTimestamp: String): Int

    @Query("SELECT * FROM battery_samples WHERE timestamp > :cursorTimestamp ORDER BY timestamp ASC LIMIT :limit")
    fun getEntriesAfterTimestamp(cursorTimestamp: String, limit: Int): List<BatterySampleEntry>

    @Query("DELETE FROM battery_samples WHERE timestamp <= :maxTimestamp")
    fun deleteEntriesBeforeTimestamp(maxTimestamp: String)

    /** Drops every pending sample — used by the DISCARD_AND_STOP disable disposition. */
    @Query("DELETE FROM battery_samples")
    fun deleteAll()
}
