package com.openlattice.chronicle.collection.sink

import com.openlattice.chronicle.collection.core.CollectionLog
import com.openlattice.chronicle.collection.core.ModuleResult
import com.openlattice.chronicle.collection.state.CollectionPersistenceGuard
import com.openlattice.chronicle.collection.state.CollectionPersistenceResult
import com.openlattice.chronicle.storage.HealthMetricSampleDao
import com.openlattice.chronicle.storage.HealthMetricSampleEntry

private const val TAG = "HealthMetricSampleSink"

/**
 * The sanctioned writer for `health_connect` records into the `health_metric_samples`
 * table — mirrors [BatterySampleSink]. Duplicate ids de-duplicate via the DAO's
 * `OnConflictStrategy.IGNORE`.
 */
public open class HealthMetricSampleSink(
    private val dao: HealthMetricSampleDao,
    private val log: CollectionLog = CollectionLog.LOGCAT,
    private val persistenceGuard: CollectionPersistenceGuard = CollectionPersistenceGuard.ALLOW,
) : CollectionSink {

    public fun captureAdmission(): CollectionPersistenceGuard = persistenceGuard.capture()

    public open fun write(samples: List<HealthMetricSampleEntry>): ModuleResult {
        if (samples.isEmpty()) {
            return ModuleResult.Ok(items = 0)
        }
        return try {
            when (persistenceGuard.persistResult { dao.insertAll(samples) }) {
                CollectionPersistenceResult.PERSISTED -> ModuleResult.Ok(items = samples.size)
                CollectionPersistenceResult.REFUSED ->
                    ModuleResult.Skipped("active enrollment persistence gate closed")
                CollectionPersistenceResult.STORAGE_UNAVAILABLE ->
                    ModuleResult.Retry("local storage temporarily unavailable")
            }
        } catch (e: Exception) {
            log.error(TAG, "Failed to persist ${samples.size} health metric(s)", e)
            ModuleResult.Failed(e, redactedMessage = "health_metric_samples insert failed: ${e.javaClass.simpleName}")
        }
    }

    public open fun queueDepth(): Int = dao.count()
}
