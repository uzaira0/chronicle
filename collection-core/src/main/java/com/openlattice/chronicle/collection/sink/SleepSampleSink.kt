package com.openlattice.chronicle.collection.sink

import com.openlattice.chronicle.collection.core.ModuleResult
import com.openlattice.chronicle.collection.state.CollectionPersistenceGuard
import com.openlattice.chronicle.collection.state.SINK_PERSISTENCE_REFUSAL
import com.openlattice.chronicle.collection.state.writeObservation
import com.openlattice.chronicle.storage.SleepSampleDao
import com.openlattice.chronicle.storage.SleepSampleEntry

/**
 * The sanctioned writer for `sleep` samples into the `sleep_samples` table — the sleep
 * analogue of [BatterySampleSink]. Empty list → Ok(0); insert → Ok(size); any persistence
 * exception → [ModuleResult.Failed] (logged, never swallowed). Duplicate ids de-duplicate
 * via the DAO's `OnConflictStrategy.IGNORE`.
 */
public open class SleepSampleSink(
    private val dao: SleepSampleDao,
    private val persistenceGuard: CollectionPersistenceGuard = CollectionPersistenceGuard.ALLOW,
) : CollectionSink {

    public fun captureAdmission(): CollectionPersistenceGuard = persistenceGuard.capture()

    public open fun write(samples: List<SleepSampleEntry>): ModuleResult {
        if (samples.isEmpty()) {
            return ModuleResult.Ok(items = 0)
        }
        return persistenceGuard.writeObservation(SINK_PERSISTENCE_REFUSAL) {
            persistenceGuard.insertOutsideCaptureWindows(samples, { it.timestamp }, dao::insertAll)
        }
    }

    public open fun queueDepth(): Int = dao.count()
}
