package com.openlattice.chronicle.collection.usage

import com.openlattice.chronicle.collection.core.CollectionLog
import com.openlattice.chronicle.collection.core.ModuleResult
import com.openlattice.chronicle.collection.sink.UsageEventSink
import com.openlattice.chronicle.storage.QueueEntry

private const val TAG = "UsageModulePersistence"

/**
 * The transactional write+checkpoint step of the Phase 4B module-manager usage path.
 *
 * This is extracted as a pure function so the atomicity contract is JVM-unit testable
 * without Room: the [transaction] runner and the [UsageEventSink] / commit lambdas are
 * seams. Production passes `chronicleDb::runInTransaction`, [UsageEventSink] over the
 * real `dataQueue` DAO, and [UsageEventsCollectionModule.commitCheckpoint]; tests pass
 * fakes.
 *
 * Atomicity contract — identical to the legacy
 * `UsageCollectionDelegate.persistUsageQueueAndCheckpoint`:
 *  - the queue write **and** the checkpoint commit run inside one [transaction];
 *  - if the sink write returns [ModuleResult.Failed], the underlying error is rethrown
 *    so the transaction rolls back — the checkpoint does **not** advance, and the next
 *    worker run re-polls the same `[previous, current)` window (crash-recovery parity,
 *    refactor plan §7.2 step 17);
 *  - an empty entry list is still a valid pass: nothing is written but the checkpoint
 *    is committed, so the poll window advances (empty-result behaviour preserved).
 *
 */
public object UsageModulePersistence {

    /**
     * Runs the write+checkpoint step.
     *
     * @param transaction a function that runs its body atomically (production:
     *   `ChronicleDb.runInTransaction`). The body's exception aborts the transaction.
     * @throws Throwable the sink failure cause, rethrown so [transaction] rolls back.
     */
    public fun persist(
        entries: List<QueueEntry>,
        currentPollTimestamp: Long,
        sink: UsageEventSink,
        commitCheckpoint: (Long) -> Unit,
        transaction: (() -> Unit) -> Unit,
        log: CollectionLog = CollectionLog.LOGCAT,
    ) {
        transaction {
            val result = sink.write(entries)
            if (result is ModuleResult.Failed) {
                log.error(TAG, "usage queue write failed; rolling back — checkpoint will not advance", result.error)
                // Surface the failure so the transaction rolls back; never swallow it.
                throw result.error
            }
            commitCheckpoint(currentPollTimestamp)
        }
    }
}
