package com.openlattice.chronicle.collection.sink

import com.openlattice.chronicle.collection.core.CollectionLog
import com.openlattice.chronicle.collection.core.ModuleResult
import com.openlattice.chronicle.collection.state.CollectionPersistenceGuard
import com.openlattice.chronicle.storage.QueueEntry
import com.openlattice.chronicle.storage.StorageQueue

/**
 * The sanctioned writer for device-lifecycle rows (design §1C.2, refactor plan
 * decision #13, refactor plan §6.2 step 2).
 *
 * Device lifecycle events are system-origin **usage-style** rows: they are serialized
 * as `QueueEntry` and stored in the same `dataQueue` table as usage events. This sink
 * therefore *composes* [UsageEventSink] rather than duplicating its persistence logic —
 * the lifecycle sink is a thin semantic wrapper that exists so the lifecycle module has
 * its own named boundary while sharing one, tested, write path.
 *
 * Result semantics are inherited from [UsageEventSink]: empty → [ModuleResult.Ok]`(0)`,
 * success → [ModuleResult.Ok]`(size)`, persistence failure → [ModuleResult.Failed].
 *
 * The non-enrolled-skip and dedupe-window behaviours of the current
 * `DeviceLifecycleEventRecorder` are *module* concerns (Phase 5), not sink concerns —
 * the sink only persists whatever rows it is handed. Phase 3 introduces this class
 * additively with no callsite switched.
 *
 */
public class LifecycleEventSink private constructor(
    private val delegate: UsageEventSink,
) : CollectionSink {

    /** Builds a lifecycle sink over [storageQueue], composing a private [UsageEventSink]. */
    public constructor(
        storageQueue: StorageQueue,
        log: CollectionLog = CollectionLog.LOGCAT,
        persistenceGuard: CollectionPersistenceGuard = CollectionPersistenceGuard.ALLOW,
    ) : this(UsageEventSink(storageQueue, log, persistenceGuard))

    /**
     * Persists lifecycle [entries] through the shared usage write path.
     *
     * @return the [ModuleResult] from the composed [UsageEventSink].
     */
    public fun write(entries: List<QueueEntry>): ModuleResult = delegate.write(entries)

    /** Current number of rows in `dataQueue` (shared usage + lifecycle stream). */
    public fun queueDepth(): Int = delegate.queueDepth()
}
