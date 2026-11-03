package com.openlattice.chronicle.collection.sink

import com.openlattice.chronicle.collection.core.ModuleResult
import com.openlattice.chronicle.collection.state.CollectionPersistenceGuard
import com.openlattice.chronicle.storage.capturedMillis

/**
 * Marker for the sanctioned writers to Chronicle's local persistence (design §1C.2).
 *
 * Sinks are the **only** classes allowed to write collected data into the Room DB.
 * A direct DAO write outside a [CollectionSink] (or a
 * test) is forbidden and enforced by ast-grep (design §4 rules #3, #4).
 *
 * Every sink:
 *  - wraps exactly one persistence boundary and preserves its existing entry
 *    serialization byte-for-byte ([com.openlattice.chronicle.storage.QueueEntry],
 *    [com.openlattice.chronicle.storage.SensorSampleEntry]);
 *  - returns an explicit [com.openlattice.chronicle.collection.core.ModuleResult] from
 *    every write — a persistent failure surfaces as
 *    [com.openlattice.chronicle.collection.core.ModuleResult.Failed] and is never
 *    silently swallowed;
 *  - treats an empty write as an idempotent success
 *    ([com.openlattice.chronicle.collection.core.ModuleResult.Ok] with `items = 0`),
 *    not a skip.
 *
 */
public interface CollectionSink

/**
 * Inside a timed sink's persist: rows captured inside a published capture window are dropped by rule (not refused) and
 * the rest go to [insert]; Ok counts the rows written.
 */
internal fun <T> CollectionPersistenceGuard.insertOutsideCaptureWindows(
    rows: List<T>,
    timestamp: (T) -> String,
    insert: (List<T>) -> Unit,
): ModuleResult {
    val captured = outsideCaptureWindows(rows) { capturedMillis(timestamp(it)) }
    insert(captured)
    return ModuleResult.Ok(items = captured.size)
}
