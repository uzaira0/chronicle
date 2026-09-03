package com.openlattice.chronicle.collection.sink

import com.openlattice.chronicle.collection.core.CollectionLog
import com.openlattice.chronicle.collection.core.ModuleResult
import com.openlattice.chronicle.collection.state.CollectionPersistenceGuard
import com.openlattice.chronicle.storage.BatterySampleDao
import com.openlattice.chronicle.storage.BatterySampleEntry

private const val TAG = "BatterySampleSink"

/**
 * The sanctioned writer for battery-telemetry samples into the `battery_samples` table
 * (design §1C.2; see `docs/SENSING-EXPANSION-DESIGN.md` §5).
 *
 * The battery-telemetry analogue of [SensorSampleSink]: battery samples are structured
 * numeric telemetry, not usage-style events, so they get their own table and their own
 * sink rather than riding the `dataQueue` path that [LifecycleEventSink] uses.
 *
 * Result semantics (design §1C.2), identical to [SensorSampleSink]:
 *  - empty list → [ModuleResult.Ok] with `items = 0` (idempotent no-op success);
 *  - successful insert → [ModuleResult.Ok] with `items = samples.size`;
 *  - any persistence exception → [ModuleResult.Failed], logged and returned — a
 *    persistent failure is never silently swallowed.
 *
 * `BatterySampleDao.insertAll` is declared with `OnConflictStrategy.IGNORE`, so a
 * duplicate-`id` write is **not** an error: it silently de-duplicates at the SQLite
 * level and this sink reports [ModuleResult.Ok]. The reported `items` is the *attempted*
 * count (the DAO does not return an inserted-row count).
 *
 * This is a plain class holding only the DAO and a logger — no `Context`. It is `open`
 * so collection-module tests can substitute a fake writer, matching [SensorSampleSink].
 *
 */
public open class BatterySampleSink(
    private val batterySampleDao: BatterySampleDao,
    private val log: CollectionLog = CollectionLog.LOGCAT,
    private val persistenceGuard: CollectionPersistenceGuard = CollectionPersistenceGuard.ALLOW,
) : CollectionSink {

    /**
     * Inserts [samples] into `battery_samples`. Duplicate ids are de-duplicated by the
     * DAO's `OnConflictStrategy.IGNORE` and are not treated as a failure.
     *
     * @return [ModuleResult.Ok] (count = attempted) on success, including `items = 0`
     *   for an empty list; [ModuleResult.Failed] if the underlying insert throws.
     */
    public open fun write(samples: List<BatterySampleEntry>): ModuleResult {
        if (samples.isEmpty()) {
            return ModuleResult.Ok(items = 0)
        }
        return try {
            if (persistenceGuard.persist { batterySampleDao.insertAll(samples) }) {
                ModuleResult.Ok(items = samples.size)
            } else {
                ModuleResult.Skipped("active enrollment persistence gate closed")
            }
        } catch (e: Exception) {
            log.error(TAG, "Failed to persist ${samples.size} battery sample(s) to battery_samples", e)
            ModuleResult.Failed(e, redactedMessage = "battery_samples insert failed: ${e.javaClass.simpleName}")
        }
    }

    /** Current number of rows in `battery_samples`. */
    public open fun queueDepth(): Int = batterySampleDao.count()
}
