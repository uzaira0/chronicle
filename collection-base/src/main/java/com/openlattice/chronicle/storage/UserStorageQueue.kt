package com.openlattice.chronicle.storage

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Query
import androidx.room.Transaction
import java.util.TreeMap

/*
 * Since database will return sorted elements, we use a list to preserve order, even though items
 * are technically a set. We could have used LinkedHashSet, but there's no need as we have no
 * plans of performing set operations on returned data.
 */
@Dao
interface UserStorageQueue {
    /** Newest decision first. */
    @Query("SELECT * FROM userQueue ORDER BY seq DESC")
    fun getUserTimestamps() : List<UserQueueEntry>

    @Query("SELECT COUNT(*) FROM userQueue")
    fun count(): Int

    /** Each selection takes the next decision number, whatever the wall clock says. */
    @Query("INSERT INTO userQueue (writeTimestamp, user, seq) VALUES (:writeTimestamp, :user, " +
        "(SELECT COALESCE(MAX(seq), 0) + 1 FROM userQueue))")
    fun insertSelection(writeTimestamp: Long, user: String)

    @Transaction
    fun insertEntries(entries: List<UserQueueEntry>) = entries.forEach { insertSelection(it.writeTimestamp, it.user) }

    @Delete
    fun deleteEntry( entry : UserQueueEntry)

    @Delete
    fun deleteEntries( entries : List<UserQueueEntry> )

    /**
     * Retain the selection that labels the committed poll boundary for the next poll: the latest decision stamped before
     * it. Every earlier decision is superseded in [labelTimeline], so it goes.
     */
    @Query("DELETE FROM userQueue WHERE seq < (SELECT MAX(seq) FROM userQueue WHERE writeTimestamp < :timestamp)")
    fun deleteEntriesWithLowerTimestamp(timestamp: Long)

    /**
     * Clears the entire user-identification queue. Used by the collection-loop DISCARD_AND_STOP
     * disposition (design §7) when `user_identification` is disabled mid-study — `userQueue`
     * is a dedicated per-module queue, so this drops only this module's pending data.
     */
    @Query("DELETE FROM userQueue")
    fun deleteAll()
}

fun UserStorageQueue.currentUserOr(default: String): String = getUserTimestamps().firstOrNull()?.user ?: default

/**
 * Selection time -> label, the map usage labelling reads. Decisions apply in seq order; a later decision stamped
 * earlier (the clock moved back) supersedes every selection stamped at or after it.
 */
fun UserStorageQueue.labelTimeline(): TreeMap<Long, String> = TreeMap<Long, String>().also { timeline ->
    getUserTimestamps().asReversed().forEach { selection ->
        timeline.tailMap(selection.writeTimestamp, true).clear()
        timeline[selection.writeTimestamp] = selection.user
    }
}
