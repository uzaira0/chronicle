package com.openlattice.chronicle.collection.sink

import com.openlattice.chronicle.collection.core.ModuleResult
import com.openlattice.chronicle.collection.state.CollectionPersistenceGuard
import com.openlattice.chronicle.collection.state.SINK_PERSISTENCE_REFUSAL
import com.openlattice.chronicle.collection.state.writeObservation
import com.openlattice.chronicle.storage.DeviceSettingsSampleDao
import com.openlattice.chronicle.storage.DeviceSettingsSampleEntry

/**
 * The sanctioned writer for `device_settings` snapshots into the `device_settings_samples`
 * table — mirrors [BatterySampleSink]. Duplicate ids de-duplicate via the DAO's
 * `OnConflictStrategy.IGNORE`.
 */
public open class DeviceSettingsSampleSink(
    private val dao: DeviceSettingsSampleDao,
    private val persistenceGuard: CollectionPersistenceGuard = CollectionPersistenceGuard.ALLOW,
) : CollectionSink {

    public fun captureAdmission(): CollectionPersistenceGuard = persistenceGuard.capture()

    public open fun write(samples: List<DeviceSettingsSampleEntry>): ModuleResult {
        if (samples.isEmpty()) {
            return ModuleResult.Ok(items = 0)
        }
        return persistenceGuard.writeObservation(SINK_PERSISTENCE_REFUSAL) {
            persistenceGuard.insertOutsideCaptureWindows(samples, { it.timestamp }, dao::insertAll)
        }
    }

    public open fun queueDepth(): Int = dao.count()
}
