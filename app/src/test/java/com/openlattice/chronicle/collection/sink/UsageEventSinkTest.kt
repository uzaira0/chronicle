package com.openlattice.chronicle.collection.sink

import com.openlattice.chronicle.collection.core.ModuleResult
import com.openlattice.chronicle.collection.core.NoOpCollectionLog
import com.openlattice.chronicle.collection.core.RecordingCollectionLog
import com.openlattice.chronicle.storage.QueueEntry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class UsageEventSinkTest {

    private fun entry(ts: Long, id: Long): QueueEntry =
        QueueEntry(writeTimestamp = ts, id = id, data = byteArrayOf(1, 2, 3))

    @Test
    fun successfulWriteReturnsOkWithCount() {
        val dao = FakeStorageQueue()
        val sink = UsageEventSink(dao, NoOpCollectionLog)

        val result = sink.write(listOf(entry(100, 1), entry(101, 2)))

        assertEquals(ModuleResult.Ok(2), result)
        assertEquals(2, dao.getSize())
    }

    @Test
    fun emptyWriteIsIdempotentOkZeroAndTouchesNothing() {
        val dao = FakeStorageQueue()
        val sink = UsageEventSink(dao, NoOpCollectionLog)

        val result = sink.write(emptyList())

        assertEquals(ModuleResult.Ok(0), result)
        assertEquals(0, dao.getSize())
    }

    @Test
    fun persistenceFailureReturnsFailedAndIsLoggedNotSwallowed() {
        val dao = FakeStorageQueue().apply { failNextInsert = true }
        val log = RecordingCollectionLog()
        val sink = UsageEventSink(dao, log)

        val result = sink.write(listOf(entry(100, 1)))

        assertTrue("expected Failed, got $result", result is ModuleResult.Failed)
        assertTrue(log.problems.isNotEmpty())
        assertTrue(log.problems.any { it.level == RecordingCollectionLog.Level.ERROR })
    }

    @Test
    fun duplicateWriteSurfacesAsFailedForDataQueueCompositeKey() {
        // dataQueue has a composite PK (writeTimestamp, id) and no OnConflict strategy:
        // a duplicate insert throws — the sink must surface that as Failed, not swallow it.
        val dao = FakeStorageQueue()
        val sink = UsageEventSink(dao, NoOpCollectionLog)

        assertEquals(ModuleResult.Ok(1), sink.write(listOf(entry(100, 1))))
        val duplicate = sink.write(listOf(entry(100, 1)))

        assertTrue("expected Failed on duplicate key, got $duplicate", duplicate is ModuleResult.Failed)
        assertEquals(1, dao.getSize())
    }

    @Test
    fun queueDepthReflectsRowCount() {
        val dao = FakeStorageQueue()
        val sink = UsageEventSink(dao, NoOpCollectionLog)
        assertEquals(0, sink.queueDepth())
        sink.write(listOf(entry(100, 1), entry(101, 2), entry(102, 3)))
        assertEquals(3, sink.queueDepth())
    }

    @Test
    fun queueEntrySerializationIsPreservedByteForByte() {
        val dao = FakeStorageQueue()
        val sink = UsageEventSink(dao, NoOpCollectionLog)
        val payload = byteArrayOf(9, 8, 7, 6)
        sink.write(listOf(QueueEntry(writeTimestamp = 500, id = 42, data = payload)))

        val stored = dao.getNextEntries(1).single()
        assertEquals(500L, stored.writeTimestamp)
        assertEquals(42L, stored.id)
        assertTrue(payload.contentEquals(stored.data))
    }
}
