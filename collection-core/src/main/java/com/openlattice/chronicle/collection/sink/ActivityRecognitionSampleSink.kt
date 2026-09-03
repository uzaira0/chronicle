package com.openlattice.chronicle.collection.sink

import com.openlattice.chronicle.collection.core.CollectionLog
import com.openlattice.chronicle.collection.core.ModuleResult
import com.openlattice.chronicle.collection.state.CollectionPersistenceGuard
import com.openlattice.chronicle.storage.ActivityRecognitionSampleDao
import com.openlattice.chronicle.storage.ActivityRecognitionSampleEntry

private const val TAG = "ActivityRecognitionSampleSink"

/**
 * The sanctioned writer for `activity_recognition` samples into the
 * `activity_recognition_samples` table — mirrors [BatterySampleSink]. Duplicate ids
 * de-duplicate via the DAO's `OnConflictStrategy.IGNORE`.
 */
public open class ActivityRecognitionSampleSink(
    private val dao: ActivityRecognitionSampleDao,
    private val log: CollectionLog = CollectionLog.LOGCAT,
    private val persistenceGuard: CollectionPersistenceGuard = CollectionPersistenceGuard.ALLOW,
) : CollectionSink {

    public open fun write(samples: List<ActivityRecognitionSampleEntry>): ModuleResult {
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
            log.error(TAG, "Failed to persist ${samples.size} activity sample(s)", e)
            ModuleResult.Failed(e, redactedMessage = "activity_recognition_samples insert failed: ${e.javaClass.simpleName}")
        }
    }

    public open fun queueDepth(): Int = dao.count()
}
