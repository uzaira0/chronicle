package com.openlattice.chronicle.collection.sink

import com.openlattice.chronicle.collection.core.ModuleResult
import com.openlattice.chronicle.collection.state.CollectionPersistenceGuard
import com.openlattice.chronicle.collection.state.SINK_PERSISTENCE_REFUSAL
import com.openlattice.chronicle.collection.state.writeObservation
import com.openlattice.chronicle.storage.AppNetworkUsageSampleDao
import com.openlattice.chronicle.storage.AppNetworkUsageSampleEntry

/**
 * The sanctioned writer for `app_network_usage` buckets into the
 * `app_network_usage_samples` table — mirrors [BatterySampleSink]. Duplicate ids
 * de-duplicate via the DAO's `OnConflictStrategy.IGNORE`.
 */
public open class AppNetworkUsageSampleSink(
    private val dao: AppNetworkUsageSampleDao,
    private val persistenceGuard: CollectionPersistenceGuard = CollectionPersistenceGuard.ALLOW,
) : CollectionSink {

    public open fun write(samples: List<AppNetworkUsageSampleEntry>): ModuleResult {
        if (samples.isEmpty()) {
            return ModuleResult.Ok(items = 0)
        }
        return persistenceGuard.writeObservation(SINK_PERSISTENCE_REFUSAL) {
            persistenceGuard.insertOutsideCaptureWindows(samples, { it.timestamp }, dao::insertAll)
        }
    }

    public open fun queueDepth(): Int = dao.count()
}
