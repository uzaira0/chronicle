package com.openlattice.chronicle.collection.sink

/**
 * Marker for the sanctioned writers to Chronicle's local persistence (design §1C.2).
 *
 * Sinks are the **only** classes allowed to write collected data into the Room DB.
 * After Phases 4–8 wire them in, a direct DAO write outside a [CollectionSink] (or a
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
 * Phase 3 is purely additive: sinks are introduced with no callsite switched.
 *
 */
public interface CollectionSink
