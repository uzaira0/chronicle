package com.openlattice.chronicle.storage

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction

/**
 * Room DAO for the `notification_activity_samples` table. Mirrors [AudioActivitySampleDao].
 */
@Dao
interface NotificationActivitySampleDao : BoundedQueueWriter {
    @Query("SELECT * FROM notification_activity_samples WHERE id IN (:ids)")
    fun getByIds(ids: List<String>): List<NotificationActivitySampleEntry>

    @Query("SELECT * FROM notification_activity_samples ORDER BY timestamp ASC LIMIT :limit")
    fun getOldest(limit: Int): List<NotificationActivitySampleEntry>

    @Transaction
    fun insertAll(samples: List<NotificationActivitySampleEntry>) = insertAllUnchecked(
        boundedRows(
            UploadQueueTable.NOTIFICATION_ACTIVITY, samples, NotificationActivitySampleEntry::persistedPayloadBytes,
        ) { it.id },
    )

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    fun insertAllUnchecked(samples: List<NotificationActivitySampleEntry>)

    @Query("DELETE FROM notification_activity_samples WHERE id IN (:ids)")
    fun deleteByIds(ids: List<String>)

    @Query("SELECT COUNT(*) FROM notification_activity_samples")
    fun count(): Int

    /** Drops every pending sample — used by the DISCARD_AND_STOP disable disposition. */
    @Query("DELETE FROM notification_activity_samples")
    fun deleteAll()
}
