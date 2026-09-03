package com.openlattice.chronicle.collection.sink

import com.openlattice.chronicle.collection.core.CollectionLog
import com.openlattice.chronicle.collection.core.ModuleResult
import com.openlattice.chronicle.collection.state.CollectionPersistenceGuard
import com.openlattice.chronicle.storage.QueueEntry
import com.openlattice.chronicle.storage.StorageQueue

private const val TAG = "UsageEventSink"

/**
 * The sanctioned writer for usage-style rows into the `dataQueue` table (design §1C.2).
 *
 * Wraps [StorageQueue.insertEntries] — the existing direct writer used by
 * `UsageMonitoringWorker.persistUsageQueueAndCheckpoint` and
 * `DeviceLifecycleEventRecorder.recordNow`. The [QueueEntry] shape (`writeTimestamp`,
 * `id`, serialized `data`) is preserved byte-for-byte; this sink only chooses *when*
 * and *how the result is reported*, never *what* is serialized.
 *
 * Result semantics (design §1C.2):
 *  - empty list  → [ModuleResult.Ok] with `items = 0` (idempotent no-op success);
 *  - successful insert → [ModuleResult.Ok] with `items = entries.size`;
 *  - any persistence exception (e.g. a duplicate composite primary key
 *    `(writeTimestamp, id)` — `StorageQueue.insertEntries` has no `OnConflict`
 *    strategy so SQLite throws) → [ModuleResult.Failed]. The failure is logged and
 *    returned, never swallowed.
 *
 * **Queue-size / cursor note:** the legacy writers call `Utils.updateUploadQueueSize`
 * *after* a successful insert. That call needs an Android `Context`; per design §1C a
 * sink must not hold a `Context` in a singleton. Phase 3 therefore does not update the
 * upload-queue size from inside the sink — the orchestration code that wires the sink
 * in (Phases 4–5) owns that side effect, exactly as the existing workers do today.
 * The `dataQueue` cursor (`writeTimestamp, id`) is untouched: this sink only inserts.
 *
 * This is a plain class holding only the DAO and a logger — no `Context`.
 *
 */
public open class UsageEventSink(
    private val storageQueue: StorageQueue,
    private val log: CollectionLog = CollectionLog.LOGCAT,
    private val persistenceGuard: CollectionPersistenceGuard = CollectionPersistenceGuard.ALLOW,
) : CollectionSink {

    /**
     * Inserts [entries] into `dataQueue`.
     *
     * @return [ModuleResult.Ok] (count = inserted) on success, including `items = 0`
     *   for an empty list; [ModuleResult.Failed] if the underlying insert throws.
     */
    public open fun write(entries: List<QueueEntry>): ModuleResult {
        if (entries.isEmpty()) {
            return ModuleResult.Ok(items = 0)
        }
        return try {
            if (persistenceGuard.persist { storageQueue.insertEntries(entries) }) {
                ModuleResult.Ok(items = entries.size)
            } else {
                ModuleResult.Skipped("active enrollment persistence gate closed")
            }
        } catch (e: Exception) {
            log.error(TAG, "Failed to persist ${entries.size} usage queue entr(ies) to dataQueue", e)
            ModuleResult.Failed(e, redactedMessage = "dataQueue insert failed: ${e.javaClass.simpleName}")
        }
    }

    /** Current number of rows in `dataQueue`. */
    public fun queueDepth(): Int = storageQueue.getSize()
}
