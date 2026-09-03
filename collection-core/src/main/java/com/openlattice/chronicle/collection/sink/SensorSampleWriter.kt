package com.openlattice.chronicle.collection.sink

import com.openlattice.chronicle.collection.core.ModuleResult
import com.openlattice.chronicle.storage.SensorSampleEntry

/**
 * The write seam the sensor runtime persists through. [SensorSampleSink] is the production
 * implementation (the sanctioned `sensor_samples` Room writer); the direct-boot runtime
 * substitutes a device-protected-storage buffer writer for the pre-first-unlock window,
 * when the credential-encrypted Room DB cannot be opened.
 *
 * Result semantics follow [CollectionSink]: an empty write is an idempotent success
 * ([ModuleResult.Ok] with `items = 0`), and a persistent failure surfaces as
 * [ModuleResult.Failed] — never silently swallowed.
 */
public fun interface SensorSampleWriter {
    /** Persists [samples]; returns [ModuleResult.Ok] on success, [ModuleResult.Failed] otherwise. */
    public fun write(samples: List<SensorSampleEntry>): ModuleResult
}
