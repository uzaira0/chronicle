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
interface StorageQueue : BoundedQueueWriter {
    @Query("SELECT * FROM dataQueue ORDER BY writeTimestamp ASC LIMIT :size")
    fun getNextEntries( size : Int ) : List<QueueEntry>

    @Query("SELECT * FROM dataQueue WHERE (CAST(writeTimestamp AS TEXT) || ':' || CAST(id AS TEXT)) IN (:keys)")
    fun getByKeys(keys: List<String>): List<QueueEntry>

    @Query("SELECT count(*) FROM dataQueue")
    fun getSize(): Int

    /** Keys of the rows queued at or after [writeTimestamp], including rows a bounded insert split off. */
    @Query("SELECT CAST(writeTimestamp AS TEXT) || ':' || CAST(id AS TEXT) FROM dataQueue WHERE writeTimestamp >= :writeTimestamp")
    fun keysFrom(writeTimestamp: Long): List<String>

    /** Highest write cursor currently queued, or null when the queue is empty. */
    @Query("SELECT MAX(writeTimestamp) FROM dataQueue")
    fun maxWriteTimestamp(): Long?

    @Transaction
    fun insertEntry(entry: QueueEntry) = insertEntries(listOf(entry))

    /** Allocate at insertion time, under the same Room write transaction as the batch. */
    @Transaction
    fun insertEntries(entries: List<QueueEntry>) {
        if (entries.isEmpty()) return
        val first = entries.minOf { it.writeTimestamp }
        val cursor = nextWriteTimestamp(first)
        val shift = cursor - first
        insertAllocatedEntries(entries.map { QueueEntry(it.writeTimestamp + shift, it.id, it.data) })
    }

    fun nextWriteTimestamp(wallClockMillis: Long): Long = monotonicQueueWriteTimestamp(wallClockMillis, maxWriteTimestamp())

    @Query("UPDATE dataQueue SET data = :data WHERE writeTimestamp = :writeTimestamp AND id = :id")
    fun updateBoundedData(writeTimestamp: Long, id: Long, data: ByteArray)

    @Transaction
    fun insertAllocatedEntries(entries: List<QueueEntry>) {
        if (entries.all { it.data.size <= MAX_PERSISTED_QUEUE_PAYLOAD_BYTES }) { insertBoundedEntries(entries); return }
        val bounded = boundedUsageRows(entries)
        var nextTimestamp = bounded.minOfOrNull { it.writeTimestamp } ?: return
        insertBoundedEntries(bounded.map { row ->
            nextTimestamp = maxOf(nextTimestamp, row.writeTimestamp)
            QueueEntry(nextTimestamp++, row.id, row.data)
        })
    }

    @Insert
    fun insertBoundedEntries(entries: List<QueueEntry>)

    @Delete
    fun deleteEntry( entry : QueueEntry)

    @Delete
    fun deleteEntries( entries : List<QueueEntry> )

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

    /** Privacy-first fallback for untagged shared usage/lifecycle rows. */
    @Query("DELETE FROM dataQueue")
    fun deleteAll()
}
