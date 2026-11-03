package com.openlattice.chronicle.collection.lifecycle

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.openlattice.chronicle.serialization.JsonSerializer
import com.openlattice.chronicle.services.lifecycle.RoomLifecycleDedupeStore
import com.openlattice.chronicle.services.lifecycle.persistLifecycleEntries
import com.openlattice.chronicle.storage.ChronicleDb
import com.openlattice.chronicle.storage.QueueEntry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class LifecycleClockCorrectionTest {
    @Test fun futureDedupeTimestampIsReplacedAfterClockCorrection() {
        val context: Context = ApplicationProvider.getApplicationContext()
        val prefs = context.getSharedPreferences(LIFECYCLE_RECORDER_PREFS_NAME, Context.MODE_PRIVATE)
        val event = LifecycleEventMapper.buildEvent("android.intent.action.BATTERY_LOW", "Battery Low", 1L)
        val key = "last:${event.interactionType}:${event.activityClass ?: ""}"
        val now = 1_700_000_000_000L
        prefs.edit().clear().putLong(key, now + 60 * 60 * 1_000L).commit()
        val store = PrefsLifecycleDedupeStore(context)

        assertTrue(store.shouldPersist(event, now))
        assertEquals(now, prefs.getLong(key, Long.MIN_VALUE))
        assertFalse(store.shouldPersist(event, now + 500L))
    }

    /** The store production uses (Room, written in the same transaction as the queued row) after the clock moves back an hour. */
    @Test fun theRoomStoreStartsANewWindowAfterTheClockMovesBack() {
        val context: Context = ApplicationProvider.getApplicationContext()
        val db = Room.inMemoryDatabaseBuilder(context, ChronicleDb::class.java).allowMainThreadQueries().build()
        fun event(at: Long) = LifecycleEventMapper.buildEvent("android.intent.action.BATTERY_LOW", "Battery Low", at)
        fun persist(id: Long, at: Long) = persistLifecycleEntries(db,
            listOf(QueueEntry(id, id, JsonSerializer.serializeQueueEntry(listOf(event(at))))))
        try {
            val now = 1_700_000_000_000L
            val store = RoomLifecycleDedupeStore(db)
            persist(1L, now + 60 * 60 * 1_000L)

            assertTrue(store.shouldPersist(event(now), now))
            persist(2L, now)
            assertFalse(store.shouldPersist(event(now + 500L), now + 500L))
            assertTrue(store.shouldPersist(event(now + 2_000L), now + 2_000L))
            assertEquals(2, db.queueEntryData().getSize())
        } finally { db.close() }
    }
}
