package com.openlattice.chronicle.collection.sink

import com.openlattice.chronicle.collection.core.ModuleResult
import com.openlattice.chronicle.storage.SensorSampleEntry
import com.openlattice.chronicle.collection.state.CollectionPersistenceGuard

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
    public data class RetainedSample(val entry: SensorSampleEntry, val origin: CollectionPersistenceGuard)
    public fun interface Discardable { public fun discardSensorSamples(sensorType: String): Int }

    public companion object {
        private val runtimes = java.util.WeakHashMap<Discardable, Unit>()
        private val retained = mutableListOf<RetainedSample>()
        public fun retainSamples(samples: List<RetainedSample>): Int = synchronized(retained) {
            val accepted = samples.take((5_000 - retained.size).coerceAtLeast(0))
            retained.addAll(accepted)
            accepted.size
        }
        public fun takeRetainedSamples(): List<RetainedSample> = synchronized(retained) {
            retained.toList().also { retained.clear() }
        }
        public fun registerRuntime(runtime: Discardable) { synchronized(runtimes) { runtimes[runtime] = Unit } }
        public fun discardSensorSamples(sensorType: String): Int {
            val live = synchronized(runtimes) { runtimes.keys.toList() }
            val pooled = synchronized(retained) {
                val before = retained.size
                retained.removeIf { it.entry.sensorType == sensorType }
                before - retained.size
            }
            return pooled + live.sumOf { it.discardSensorSamples(sensorType) }
        }
    }

    /** Persists [samples]; returns [ModuleResult.Ok] on success, [ModuleResult.Failed] otherwise. */
    public fun write(samples: List<SensorSampleEntry>): ModuleResult

    /** Revalidate a drained batch at the writer's persistence boundary after module erasure. */
    public fun writeCurrent(samples: List<SensorSampleEntry>, isCurrent: (SensorSampleEntry) -> Boolean): ModuleResult =
        write(samples.filter(isCurrent))
}
