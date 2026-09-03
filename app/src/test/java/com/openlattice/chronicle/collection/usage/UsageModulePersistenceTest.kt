package com.openlattice.chronicle.collection.usage

import com.openlattice.chronicle.collection.core.NoOpCollectionLog
import com.openlattice.chronicle.collection.sink.FakeStorageQueue
import com.openlattice.chronicle.collection.sink.UsageEventSink
import com.openlattice.chronicle.storage.QueueEntry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * JVM unit coverage for the Phase 4B module-manager usage path's write+checkpoint step
 * ([UsageModulePersistence]).
 *
 * Covers the worker-path obligations from refactor plan §7.2: queue cursor, retry/
 * failure, idempotency on a duplicate worker execution, and crash recovery (a poll
 * failure / write failure before the checkpoint commit must not advance the cursor).
 * The real `ChronicleDb.runInTransaction` is replaced by a fake transaction runner that
 * reproduces commit/rollback semantics.
 */
class UsageModulePersistenceTest {

    /** Transaction runner that mirrors Room: a thrown body aborts and the throw escapes. */
    private class FakeTransactionRunner {
        var commits = 0
        var rollbacks = 0
        fun run(body: () -> Unit) {
            try {
                body()
                commits++
            } catch (e: Throwable) {
                rollbacks++
                throw e
            }
        }
    }

    private fun entry(ts: Long, id: Long): QueueEntry =
        QueueEntry(writeTimestamp = ts, id = id, data = byteArrayOf(1, 2, 3))

    @Test
    fun successfulWriteCommitsQueueRowsAndAdvancesCheckpointInOneTransaction() {
        val queue = FakeStorageQueue()
        val sink = UsageEventSink(queue, NoOpCollectionLog)
        val store = FakeUsagePollCheckpointStore(storedTimestamp = 1_000L)
        val tx = FakeTransactionRunner()

        UsageModulePersistence.persist(
            entries = listOf(entry(100, 1), entry(101, 2)),
            currentPollTimestamp = 9_000L,
            sink = sink,
            commitCheckpoint = store::commitPollTimestamp,
            transaction = tx::run,
            log = NoOpCollectionLog,
        )

        assertEquals(2, queue.getSize())
        assertEquals(9_000L, store.storedTimestamp)
        assertEquals(1, store.commitCount)
        assertEquals(1, tx.commits)
        assertEquals(0, tx.rollbacks)
    }

    @Test
    fun emptyWriteStillAdvancesCheckpointSoThePollWindowMovesForward() {
        // Empty-result behaviour: nothing to persist, but the checkpoint must advance.
        val queue = FakeStorageQueue()
        val sink = UsageEventSink(queue, NoOpCollectionLog)
        val store = FakeUsagePollCheckpointStore(storedTimestamp = 1_000L)
        val tx = FakeTransactionRunner()

        UsageModulePersistence.persist(
            entries = emptyList(),
            currentPollTimestamp = 9_000L,
            sink = sink,
            commitCheckpoint = store::commitPollTimestamp,
            transaction = tx::run,
            log = NoOpCollectionLog,
        )

        assertEquals(0, queue.getSize())
        assertEquals(9_000L, store.storedTimestamp)
        assertEquals(1, tx.commits)
    }

    @Test
    fun writeFailureRollsBackTransactionAndDoesNotAdvanceCheckpoint() {
        // Crash-recovery parity: a write failure before the checkpoint commit must leave
        // the cursor untouched so the next worker run re-polls the same window.
        val queue = FakeStorageQueue().apply { failNextInsert = true }
        val sink = UsageEventSink(queue, NoOpCollectionLog)
        val store = FakeUsagePollCheckpointStore(storedTimestamp = 5_000L)
        val tx = FakeTransactionRunner()

        var thrown = false
        try {
            UsageModulePersistence.persist(
                entries = listOf(entry(100, 1)),
                currentPollTimestamp = 9_000L,
                sink = sink,
                commitCheckpoint = store::commitPollTimestamp,
                transaction = tx::run,
                log = NoOpCollectionLog,
            )
        } catch (_: Throwable) {
            thrown = true
        }

        assertTrue("write failure must propagate so the transaction rolls back", thrown)
        assertEquals(0, store.commitCount)
        assertEquals(5_000L, store.storedTimestamp) // checkpoint unchanged
        assertEquals(1, tx.rollbacks)
        assertEquals(0, tx.commits)
    }

    @Test
    fun duplicateWorkerExecutionWithSameQueueKeysSurfacesAsRollbackNotSilentLoss() {
        // Idempotency: a second worker execution that re-builds the SAME QueueEntry keys
        // (composite PK collision on dataQueue) must surface as a rolled-back failure,
        // not a silent partial write — the checkpoint must not advance on the failed run.
        val queue = FakeStorageQueue()
        val sink = UsageEventSink(queue, NoOpCollectionLog)
        val store = FakeUsagePollCheckpointStore(storedTimestamp = 1_000L)

        val firstTx = FakeTransactionRunner()
        UsageModulePersistence.persist(
            entries = listOf(entry(100, 1)),
            currentPollTimestamp = 9_000L,
            sink = sink,
            commitCheckpoint = store::commitPollTimestamp,
            transaction = firstTx::run,
            log = NoOpCollectionLog,
        )
        assertEquals(9_000L, store.storedTimestamp)

        // Second execution re-inserts the same (writeTimestamp, id) — duplicate PK.
        val secondTx = FakeTransactionRunner()
        var thrown = false
        try {
            UsageModulePersistence.persist(
                entries = listOf(entry(100, 1)),
                currentPollTimestamp = 12_000L,
                sink = sink,
                commitCheckpoint = store::commitPollTimestamp,
                transaction = secondTx::run,
                log = NoOpCollectionLog,
            )
        } catch (_: Throwable) {
            thrown = true
        }

        assertTrue(thrown)
        assertEquals(1, queue.getSize())            // no double write
        assertEquals(9_000L, store.storedTimestamp) // checkpoint did not advance on the failed run
        assertEquals(1, secondTx.rollbacks)
    }

    @Test
    fun queueCursorIsAppendOnlyAcrossSuccessiveWorkerRuns() {
        // Successive successful runs append rows with strictly increasing keys; the
        // dataQueue cursor (writeTimestamp, id) keeps every prior row.
        val queue = FakeStorageQueue()
        val sink = UsageEventSink(queue, NoOpCollectionLog)
        val store = FakeUsagePollCheckpointStore()

        UsageModulePersistence.persist(
            listOf(entry(100, 1), entry(101, 2)), 9_000L, sink,
            store::commitPollTimestamp, FakeTransactionRunner()::run, NoOpCollectionLog,
        )
        UsageModulePersistence.persist(
            listOf(entry(200, 3), entry(201, 4)), 18_000L, sink,
            store::commitPollTimestamp, FakeTransactionRunner()::run, NoOpCollectionLog,
        )

        assertEquals(4, queue.getSize())
        assertEquals(18_000L, store.storedTimestamp)
        assertEquals(2, store.commitCount)
    }
}
