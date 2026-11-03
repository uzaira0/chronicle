package com.openlattice.chronicle.collection.sink

import com.openlattice.chronicle.collection.core.ModuleResult
import com.openlattice.chronicle.collection.state.CollectionPersistenceGuard
import com.openlattice.chronicle.collection.state.SINK_PERSISTENCE_REFUSAL
import com.openlattice.chronicle.collection.state.writeObservation
import com.openlattice.chronicle.storage.ActivityRecognitionSampleDao
import com.openlattice.chronicle.storage.ActivityRecognitionSampleEntry

/**
 * The sanctioned writer for `activity_recognition` samples into the
 * `activity_recognition_samples` table — mirrors [BatterySampleSink]. Duplicate ids
 * de-duplicate via the DAO's `OnConflictStrategy.IGNORE`.
 */
public open class ActivityRecognitionSampleSink(
    private val dao: ActivityRecognitionSampleDao,
    private val persistenceGuard: CollectionPersistenceGuard = CollectionPersistenceGuard.ALLOW,
) : CollectionSink {

    public fun captureAdmission(): CollectionPersistenceGuard = persistenceGuard.capture()

    public open fun write(samples: List<ActivityRecognitionSampleEntry>): ModuleResult {
        if (samples.isEmpty()) {
            return ModuleResult.Ok(items = 0)
        }
        return persistenceGuard.writeObservation(SINK_PERSISTENCE_REFUSAL) {
            persistenceGuard.insertOutsideCaptureWindows(samples, { it.timestamp }, dao::insertAll)
        }
    }

    public open fun queueDepth(): Int = dao.count()
}
