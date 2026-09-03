package com.openlattice.chronicle.collection.sink

import com.openlattice.chronicle.collection.core.CollectionLog
import com.openlattice.chronicle.collection.core.ModuleResult
import com.openlattice.chronicle.collection.state.CollectionPersistenceGuard
import com.openlattice.chronicle.storage.SensorSampleDao
import com.openlattice.chronicle.storage.SensorSampleEntry

private const val TAG = "SensorSampleSink"

/**
 * The sanctioned writer for hardware sensor samples into the `sensor_samples` table
 * (design §1C.2).
 *
 * Wraps [SensorSampleDao.insertAll] — the existing direct writer used by
 * `HardwareSensorService.flushBuffer` and `HardwareSensorService.onDestroy`. The
 * [SensorSampleEntry] shape is preserved unchanged; this sink only reports the result.
 *
 * Result semantics (design §1C.2) — note the difference from [UsageEventSink]:
 *  - empty list → [ModuleResult.Ok] with `items = 0` (idempotent no-op success);
 *  - successful insert → [ModuleResult.Ok] with `items = samples.size`;
 *  - any persistence exception → [ModuleResult.Failed], logged and returned.
 *
 * `SensorSampleDao.insertAll` is declared with `OnConflictStrategy.IGNORE`, so a
 * duplicate-`id` write is **not** an error: it silently de-duplicates at the SQLite
 * level and this sink reports [ModuleResult.Ok]. The reported `items` is the *attempted*
 * count (the DAO does not return an inserted-row count); the cursor semantics of
 * `sensor_samples` (`timestamp`-ordered) are untouched — this sink only inserts.
 *
 * This is a plain class holding only the DAO and a logger — no `Context`.
 *
 */
public open class SensorSampleSink(
    private val sensorSampleDao: SensorSampleDao,
    private val log: CollectionLog = CollectionLog.LOGCAT,
    private val persistenceGuard: CollectionPersistenceGuard = CollectionPersistenceGuard.ALLOW,
    private val sampleAllowedAtPersistence: (SensorSampleEntry) -> Boolean = { true },
) : CollectionSink, SensorSampleWriter {

    /**
     * Inserts [samples] into `sensor_samples`. Duplicate ids are de-duplicated by the
     * DAO's `OnConflictStrategy.IGNORE` and are not treated as a failure.
     *
     * @return [ModuleResult.Ok] (count = attempted) on success, including `items = 0`
     *   for an empty list; [ModuleResult.Failed] if the underlying insert throws.
     */
    public open override fun write(samples: List<SensorSampleEntry>): ModuleResult {
        if (samples.isEmpty()) {
            return ModuleResult.Ok(items = 0)
        }
        return try {
            var persistedCount = 0
            val admitted = persistenceGuard.persist {
                val allowed = samples.filter(sampleAllowedAtPersistence)
                if (allowed.isNotEmpty()) {
                    sensorSampleDao.insertAll(allowed)
                    persistedCount = allowed.size
                }
            }
            if (!admitted || persistedCount == 0) {
                ModuleResult.Skipped("active enrollment or sensor persistence gate closed")
            } else {
                ModuleResult.Ok(items = persistedCount)
            }
        } catch (e: Exception) {
            log.error(TAG, "Failed to persist ${samples.size} sensor sample(s) to sensor_samples", e)
            ModuleResult.Failed(e, redactedMessage = "sensor_samples insert failed: ${e.javaClass.simpleName}")
        }
    }

    /** Current number of rows in `sensor_samples`. */
    public fun queueDepth(): Int = sensorSampleDao.count()
}
