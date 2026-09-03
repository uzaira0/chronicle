package com.openlattice.chronicle.collection.sink

import com.openlattice.chronicle.collection.core.CollectionLog
import com.openlattice.chronicle.collection.core.ModuleResult
import com.openlattice.chronicle.collection.state.CollectionPersistenceGuard
import com.openlattice.chronicle.storage.AppNetworkUsageSampleDao
import com.openlattice.chronicle.storage.AppNetworkUsageSampleEntry

private const val TAG = "AppNetworkUsageSampleSink"

/**
 * The sanctioned writer for `app_network_usage` buckets into the
 * `app_network_usage_samples` table — mirrors [BatterySampleSink]. Duplicate ids
 * de-duplicate via the DAO's `OnConflictStrategy.IGNORE`.
 */
public open class AppNetworkUsageSampleSink(
    private val dao: AppNetworkUsageSampleDao,
    private val log: CollectionLog = CollectionLog.LOGCAT,
    private val persistenceGuard: CollectionPersistenceGuard = CollectionPersistenceGuard.ALLOW,
) : CollectionSink {

    public open fun write(samples: List<AppNetworkUsageSampleEntry>): ModuleResult {
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
            log.error(TAG, "Failed to persist ${samples.size} app-network-usage sample(s)", e)
            ModuleResult.Failed(e, redactedMessage = "app_network_usage_samples insert failed: ${e.javaClass.simpleName}")
        }
    }

    public open fun queueDepth(): Int = dao.count()
}
