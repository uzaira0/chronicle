package com.openlattice.chronicle.collection.sink

import com.openlattice.chronicle.collection.core.CollectionLog
import com.openlattice.chronicle.collection.core.ModuleResult
import com.openlattice.chronicle.collection.state.CollectionPersistenceGuard
import com.openlattice.chronicle.collection.state.CollectionPersistenceResult
import com.openlattice.chronicle.storage.DeviceSettingsSampleDao
import com.openlattice.chronicle.storage.DeviceSettingsSampleEntry

private const val TAG = "DeviceSettingsSampleSink"

/**
 * The sanctioned writer for `device_settings` snapshots into the `device_settings_samples`
 * table — mirrors [BatterySampleSink]. Duplicate ids de-duplicate via the DAO's
 * `OnConflictStrategy.IGNORE`.
 */
public open class DeviceSettingsSampleSink(
    private val dao: DeviceSettingsSampleDao,
    private val log: CollectionLog = CollectionLog.LOGCAT,
    private val persistenceGuard: CollectionPersistenceGuard = CollectionPersistenceGuard.ALLOW,
) : CollectionSink {

    public fun captureAdmission(): CollectionPersistenceGuard = persistenceGuard.capture()

    public open fun write(samples: List<DeviceSettingsSampleEntry>): ModuleResult {
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
            log.error(TAG, "Failed to persist ${samples.size} device-settings snapshot(s)", e)
            ModuleResult.Failed(e, redactedMessage = "device_settings_samples insert failed: ${e.javaClass.simpleName}")
        }
    }

    public open fun queueDepth(): Int = dao.count()
}
