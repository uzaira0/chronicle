package com.openlattice.chronicle.collection.sink

import com.openlattice.chronicle.collection.core.ModuleResult
import com.openlattice.chronicle.collection.state.CollectionPersistenceGuard
import com.openlattice.chronicle.collection.state.SINK_PERSISTENCE_REFUSAL
import com.openlattice.chronicle.collection.state.writeObservation
import com.openlattice.chronicle.storage.HealthMetricSampleDao
import com.openlattice.chronicle.storage.HealthMetricSampleEntry

/**
 * The sanctioned writer for `health_connect` records into the `health_metric_samples`
 * table — mirrors [BatterySampleSink]. Duplicate ids de-duplicate via the DAO's
 * `OnConflictStrategy.IGNORE`.
 */
public open class HealthMetricSampleSink(
    private val dao: HealthMetricSampleDao,
    private val persistenceGuard: CollectionPersistenceGuard = CollectionPersistenceGuard.ALLOW,
) : CollectionSink {

    public fun captureAdmission(): CollectionPersistenceGuard = persistenceGuard.capture()

    public open fun write(samples: List<HealthMetricSampleEntry>): ModuleResult {
        if (samples.isEmpty()) {
            return ModuleResult.Ok(items = 0)
        }
        return persistenceGuard.writeObservation(SINK_PERSISTENCE_REFUSAL) {
            persistenceGuard.insertOutsideCaptureWindows(samples, { it.timestamp }, dao::insertAll)
        }
    }

    public open fun queueDepth(): Int = dao.count()
}
