package com.openlattice.chronicle.collection.sink

import com.openlattice.chronicle.collection.core.CollectionLog
import com.openlattice.chronicle.collection.core.ModuleResult
import com.openlattice.chronicle.collection.state.CollectionPersistenceGuard
import com.openlattice.chronicle.storage.SleepSampleDao
import com.openlattice.chronicle.storage.SleepSampleEntry

private const val TAG = "SleepSampleSink"

/**
 * The sanctioned writer for `sleep` samples into the `sleep_samples` table — the sleep
 * analogue of [BatterySampleSink]. Empty list → Ok(0); insert → Ok(size); any persistence
 * exception → [ModuleResult.Failed] (logged, never swallowed). Duplicate ids de-duplicate
 * via the DAO's `OnConflictStrategy.IGNORE`.
 */
public open class SleepSampleSink(
    private val dao: SleepSampleDao,
    private val log: CollectionLog = CollectionLog.LOGCAT,
    private val persistenceGuard: CollectionPersistenceGuard = CollectionPersistenceGuard.ALLOW,
) : CollectionSink {

    public open fun write(samples: List<SleepSampleEntry>): ModuleResult {
        if (samples.isEmpty()) {
            return ModuleResult.Ok(items = 0)
        }
        return try {
            if (persistenceGuard.persist { dao.insertAll(samples) }) {
                ModuleResult.Ok(items = samples.size)
            } else {
                ModuleResult.Skipped("active enrollment persistence gate closed")
            }
        } catch (e: Exception) {
            log.error(TAG, "Failed to persist ${samples.size} sleep sample(s) to sleep_samples", e)
            ModuleResult.Failed(e, redactedMessage = "sleep_samples insert failed: ${e.javaClass.simpleName}")
        }
    }

    public open fun queueDepth(): Int = dao.count()
}
