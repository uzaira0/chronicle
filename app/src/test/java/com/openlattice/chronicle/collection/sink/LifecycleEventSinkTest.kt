package com.openlattice.chronicle.collection.sink

import com.openlattice.chronicle.collection.core.ModuleResult
import com.openlattice.chronicle.collection.core.NoOpCollectionLog
import com.openlattice.chronicle.storage.QueueEntry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LifecycleEventSinkTest {

    private fun entry(ts: Long, id: Long): QueueEntry =
        QueueEntry(writeTimestamp = ts, id = id, data = byteArrayOf(4, 5, 6))

    @Test
    fun lifecycleBatchWriteSharesTheUsageDataQueuePath() {
        val dao = FakeStorageQueue()
        val sink = LifecycleEventSink(dao, NoOpCollectionLog)

        val result = sink.write(listOf(entry(200, 1), entry(201, 2)))

        // Lifecycle events are usage-style rows: they land in the same dataQueue table.
        assertEquals(ModuleResult.Ok(2), result)
        assertEquals(2, dao.getSize())
        assertEquals(2, sink.queueDepth())
    }

    @Test
    fun emptyLifecycleWriteIsIdempotentOkZero() {
        val dao = FakeStorageQueue()
        val sink = LifecycleEventSink(dao, NoOpCollectionLog)
        assertEquals(ModuleResult.Ok(0), sink.write(emptyList()))
        assertEquals(0, dao.getSize())
    }

    @Test
    fun lifecycleWriteFailureSurfacesAsFailed() {
        val dao = FakeStorageQueue().apply { failNextInsert = true }
        val sink = LifecycleEventSink(dao, NoOpCollectionLog)
        assertTrue(sink.write(listOf(entry(200, 1))) is ModuleResult.Failed)
    }

    @Test
    fun lifecycleAndUsageRowsCoexistInOneQueue() {
        val dao = FakeStorageQueue()
        val usageSink = UsageEventSink(dao, NoOpCollectionLog)
        val lifecycleSink = LifecycleEventSink(dao, NoOpCollectionLog)

        usageSink.write(listOf(entry(300, 1)))
        lifecycleSink.write(listOf(entry(301, 2)))

        assertEquals(2, dao.getSize())
    }
}
