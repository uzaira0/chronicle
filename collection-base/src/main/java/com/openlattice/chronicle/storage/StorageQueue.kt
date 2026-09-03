package com.openlattice.chronicle.storage

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.Query

/*
 * Since database will return sorted elements, we use a list to preserve order, even though items
 * are technically a set. We could have used LinkedHashSet, but there's no need as we have no
 * plans of performing set operations on returned data.
 */
@Dao
interface StorageQueue {
    @Query("SELECT * FROM dataQueue ORDER BY writeTimestamp ASC LIMIT :size")
    fun getNextEntries( size : Int ) : List<QueueEntry>

    @Query("SELECT count(*) FROM dataQueue")
    fun getSize(): Int
    
    @Insert
    fun insertEntry( entry: QueueEntry)

    @Insert
    fun insertEntries( entries: List<QueueEntry> )

    @Delete
    fun deleteEntry( entry : QueueEntry)

    @Delete
    fun deleteEntries( entries : List<QueueEntry> )

    @Query("SELECT * FROM dataQueue WHERE writeTimestamp > :cursor ORDER BY writeTimestamp ASC LIMIT :limit")
    fun getEntriesAfter(cursor: Long, limit: Int): List<QueueEntry>

    @Query(
        """
        SELECT * FROM dataQueue
        WHERE writeTimestamp > :cursorTimestamp
            OR (writeTimestamp = :cursorTimestamp AND id > :cursorId)
        ORDER BY writeTimestamp ASC, id ASC
        LIMIT :limit
        """
    )
    fun getEntriesAfter(cursorTimestamp: Long, cursorId: Long, limit: Int): List<QueueEntry>

    @Query("DELETE FROM dataQueue WHERE writeTimestamp <= :maxTimestamp")
    fun deleteEntriesBefore(maxTimestamp: Long)

    @Query(
        """
        DELETE FROM dataQueue
        WHERE writeTimestamp < :maxTimestamp
            OR (writeTimestamp = :maxTimestamp AND id <= :maxId)
        """
    )
    fun deleteEntriesBeforeOrAt(maxTimestamp: Long, maxId: Long)

    /** Privacy-first fallback for untagged shared usage/lifecycle rows. */
    @Query("DELETE FROM dataQueue")
    fun deleteAll()
}
