package com.openlattice.chronicle.collection.sink

import com.openlattice.chronicle.collection.core.CollectionLog
import com.openlattice.chronicle.collection.core.ModuleResult
import com.openlattice.chronicle.collection.state.CollectionPersistenceGuard
import com.openlattice.chronicle.collection.state.SINK_PERSISTENCE_REFUSAL
import com.openlattice.chronicle.collection.state.writeObservation
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
            persistenceGuard.writeObservation(SINK_PERSISTENCE_REFUSAL) {
                dao.insertAll(samples)
                ModuleResult.Ok(items = samples.size)
            }
        } catch (e: Exception) {
            log.error(TAG, "Failed to persist ${samples.size} device-settings snapshot(s)", e)
            ModuleResult.Failed(e, redactedMessage = "device_settings_samples insert failed: ${e.javaClass.simpleName}")
        }
    }

    public open fun queueDepth(): Int = dao.count()
}
