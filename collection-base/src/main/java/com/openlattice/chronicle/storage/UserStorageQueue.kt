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
interface UserStorageQueue {
    @Query("SELECT * FROM userQueue ORDER BY writeTimestamp DESC")
    fun getUserTimestamps() : List<UserQueueEntry>

    @Query("SELECT COUNT(*) FROM userQueue")
    fun count(): Int

    @Insert
    suspend fun insertEntry( entry: UserQueueEntry)

    @Insert
    fun insertEntries( entries: List<UserQueueEntry> )

    @Delete
    fun deleteEntry( entry : UserQueueEntry)

    @Delete
    fun deleteEntries( entries : List<UserQueueEntry> )

    @Query("DELETE FROM userQueue WHERE writeTimestamp < :timestamp")
    fun deleteEntriesWithLowerTimestamp(timestamp: Long)

    /**
     * Clears the entire user-identification queue. Used by the collection-loop DISCARD_AND_STOP
     * disposition (design §7) when `user_identification` is disabled mid-study — `userQueue`
     * is a dedicated per-module queue, so this drops only this module's pending data.
     */
    @Query("DELETE FROM userQueue")
    fun deleteAll()
}
