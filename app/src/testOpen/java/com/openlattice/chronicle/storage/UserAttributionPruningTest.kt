package com.openlattice.chronicle.storage

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class UserAttributionPruningTest {
    @Test fun consecutivePollsKeepCurrentTargetBaselineAndPruneOnlyOlderLabels() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val db = Room.inMemoryDatabaseBuilder(context, ChronicleDb::class.java).allowMainThreadQueries().build()
        try {
            val queue = db.userQueueEntryData()
            queue.insertEntries(listOf(UserQueueEntry(50, "Old"), UserQueueEntry(100, "Target")))
            // Both module and legacy delegates prune through this DAO after committing a poll.
            queue.deleteEntriesWithLowerTimestamp(200)
            assertEquals(listOf("Target"), queue.getUserTimestamps().map { it.user })
            queue.deleteEntriesWithLowerTimestamp(300)
            assertEquals(listOf("Target"), queue.getUserTimestamps().map { it.user })
            queue.insertEntries(listOf(UserQueueEntry(350, "Next")))
            queue.deleteEntriesWithLowerTimestamp(400)
            assertEquals(listOf("Next"), queue.getUserTimestamps().map { it.user })
        } finally { db.close() }
    }
}
