package com.openlattice.chronicle.storage

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Transaction

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

    /** Highest write cursor currently queued, or null when the queue is empty. */
    @Query("SELECT MAX(writeTimestamp) FROM dataQueue")
    fun maxWriteTimestamp(): Long?
    
    @Query("SELECT MAX(lastUploadedTimestamp) FROM upload_servers")
    fun maxUploadedTimestamp(): Long?

    @Transaction
    fun insertEntry(entry: QueueEntry) = insertEntries(listOf(entry))

    /** Allocate at insertion time, under the same Room write transaction as the batch. */
    @Transaction
    fun insertEntries(entries: List<QueueEntry>) {
        if (entries.isEmpty()) return
        val first = entries.minOf { it.writeTimestamp }
        val highWater = listOfNotNull(maxWriteTimestamp(), maxUploadedTimestamp()).maxOrNull()
        val cursor = monotonicQueueWriteTimestamp(first, highWater)
        val shift = cursor - first
        insertAllocatedEntries(entries.map { QueueEntry(it.writeTimestamp + shift, it.id, it.data) })
    }

    @Insert
    fun insertAllocatedEntries(entries: List<QueueEntry>)

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

    /** Low-storage eviction: removes the [count] oldest rows, returning how many went. */
    @Query("DELETE FROM dataQueue WHERE rowid IN (SELECT rowid FROM dataQueue ORDER BY writeTimestamp ASC, id ASC LIMIT :count)")
    fun deleteOldest(count: Int): Int

    /** Privacy-first fallback for untagged shared usage/lifecycle rows. */
    @Query("DELETE FROM dataQueue")
    fun deleteAll()
}
