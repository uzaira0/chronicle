package com.openlattice.chronicle.storage

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class QueueWriteTransactionTest {
    @Test fun delayedWriterCannotLandBehindAcknowledgedCursor() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val db = Room.inMemoryDatabaseBuilder(context, ChronicleDb::class.java).allowMainThreadQueries().build()
        try {
            val paused = CountDownLatch(1)
            val resume = CountDownLatch(1)
            val done = CountDownLatch(1)
            var failure: Throwable? = null
            val thread = Thread {
                try {
                    val entry = QueueEntry(db.nextQueueWriteTimestamp(100), 1, byteArrayOf(1))
                    paused.countDown()
                    check(resume.await(5, TimeUnit.SECONDS))
                    db.queueEntryData().insertEntry(entry)
                } catch (e: Throwable) { failure = e } finally { done.countDown() }
            }
            thread.start()
            assertTrue(paused.await(5, TimeUnit.SECONDS))
            db.queueEntryData().insertEntry(QueueEntry(db.nextQueueWriteTimestamp(200), 2, byteArrayOf(2)))
            val server = db.uploadServerDao().insert(UploadServerEntity(name="test", url="https://localhost",
                studyId="s", participantId="p", sourceDeviceId="d", createdAt="epoch"))
            db.openHelper.writableDatabase.execSQL("UPDATE upload_servers SET lastUploadedTimestamp = 200 WHERE id = ?", arrayOf(server))
            db.queueEntryData().deleteEntriesBeforeOrAt(200, 2)
            resume.countDown()
            assertTrue(done.await(5, TimeUnit.SECONDS))
            failure?.let { throw it }
            val pending = db.queueEntryData().getEntriesAfter(200, 10)
            assertEquals(1, pending.size)
            assertEquals(1L, pending.single().id)
            db.queueEntryData().deleteEntriesBeforeOrAt(200, 2)
            assertEquals(1, db.queueEntryData().getSize())
        } finally { db.close() }
    }
}
