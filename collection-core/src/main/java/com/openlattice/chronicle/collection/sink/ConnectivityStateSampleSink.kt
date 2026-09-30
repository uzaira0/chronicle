package com.openlattice.chronicle.collection.sink

import com.openlattice.chronicle.collection.core.CollectionLog
import com.openlattice.chronicle.collection.core.ModuleResult
import com.openlattice.chronicle.collection.state.CollectionPersistenceGuard
import com.openlattice.chronicle.collection.state.CollectionPersistenceResult
import com.openlattice.chronicle.storage.ConnectivityStateSampleDao
import com.openlattice.chronicle.storage.ConnectivityStateSampleEntry

private const val TAG = "ConnectivityStateSampleSink"

/**
 * The sanctioned writer for `connectivity_state` samples into the
 * `connectivity_state_samples` table — mirrors [BatterySampleSink]. Duplicate ids
 * de-duplicate via the DAO's `OnConflictStrategy.IGNORE`.
 */
public open class ConnectivityStateSampleSink(
    private val dao: ConnectivityStateSampleDao,
    private val log: CollectionLog = CollectionLog.LOGCAT,
    private val persistenceGuard: CollectionPersistenceGuard = CollectionPersistenceGuard.ALLOW,
) : CollectionSink {

    public fun captureAdmission(): CollectionPersistenceGuard = persistenceGuard.capture()

    public open fun write(samples: List<ConnectivityStateSampleEntry>): ModuleResult {
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
            log.error(TAG, "Failed to persist ${samples.size} connectivity sample(s)", e)
            ModuleResult.Failed(e, redactedMessage = "connectivity_state_samples insert failed: ${e.javaClass.simpleName}")
        }
    }

    public open fun queueDepth(): Int = dao.count()
}
