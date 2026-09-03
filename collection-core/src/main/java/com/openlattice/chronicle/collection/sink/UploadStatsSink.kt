package com.openlattice.chronicle.collection.sink

import com.openlattice.chronicle.collection.core.CollectionLog
import com.openlattice.chronicle.collection.core.ModuleResult
import com.openlattice.chronicle.storage.UploadStatsDao
import com.openlattice.chronicle.storage.UploadStatsEntity

private const val TAG = "UploadStatsSink"

/**
 * The sanctioned writer for upload telemetry counters into the `upload_stats` table
 * (design §1C.2, refactor plan §6.2 step 4 — "upload telemetry must be a module-owned
 * boundary").
 *
 * Wraps the [UploadStatsDao] increment path used today by `UploadExecutor` (usage) and
 * `SensorUploadWorkerDelegate` (sensors). Both existing callers do the same two-step,
 * idempotent dance: `insertDay(UploadStatsEntity(serverId, date))` — a no-op when the
 * `(serverId, date)` row already exists thanks to `OnConflictStrategy.IGNORE` — then an
 * `UPDATE ... SET count = count + :n`. This sink preserves that exact pattern so the
 * counter semantics are unchanged.
 *
 * Result semantics (design §1C.2):
 *  - a `count` of `0` → [ModuleResult.Ok]`(0)` without touching the DB (idempotent
 *    no-op success);
 *  - a successful increment → [ModuleResult.Ok] with `items = count`;
 *  - any persistence exception → [ModuleResult.Failed], logged and returned — an upload
 *    telemetry write failure is never silently swallowed.
 *
 * `upload_stats` carries no participant data (privacy class `OPERATIONAL_DIAGNOSTICS`)
 * and this sink stores no API key or signing secret — only counters keyed by
 * `serverId` and `date` (design §1B.3).
 *
 * This is a plain class holding only the DAO and a logger — no `Context`.
 *
 */
public open class UploadStatsSink(
    private val uploadStatsDao: UploadStatsDao,
    private val log: CollectionLog = CollectionLog.LOGCAT,
) : CollectionSink {

    /**
     * Adds [count] to the usage-events counter for ([serverId], [date]).
     *
     * @return [ModuleResult.Ok]`(count)` on success (and for `count == 0` without a DB
     *   write); [ModuleResult.Failed] if the underlying write throws.
     * @throws IllegalArgumentException if [count] is negative.
     */
    public open fun recordUsageUploaded(serverId: Long, date: String, count: Int): ModuleResult =
        record("usage", serverId, date, count) { uploadStatsDao.incrementUsageCount(serverId, date, count) }

    /**
     * Adds [count] to the sensor-samples counter for ([serverId], [date]).
     *
     * @return [ModuleResult.Ok]`(count)` on success (and for `count == 0` without a DB
     *   write); [ModuleResult.Failed] if the underlying write throws.
     * @throws IllegalArgumentException if [count] is negative.
     */
    public open fun recordSensorUploaded(serverId: Long, date: String, count: Int): ModuleResult =
        record("sensor", serverId, date, count) { uploadStatsDao.incrementSensorCount(serverId, date, count) }

    private inline fun record(
        kind: String,
        serverId: Long,
        date: String,
        count: Int,
        increment: () -> Unit,
    ): ModuleResult {
        require(count >= 0) { "UploadStatsSink $kind count must be non-negative: $count" }
        if (count == 0) {
            return ModuleResult.Ok(items = 0)
        }
        return try {
            // Idempotent: insertDay is a no-op when the (serverId, date) row exists.
            uploadStatsDao.insertDay(UploadStatsEntity(serverId = serverId, date = date))
            increment()
            ModuleResult.Ok(items = count)
        } catch (e: Exception) {
            log.error(TAG, "Failed to record $kind upload count ($count) for server $serverId on $date", e)
            ModuleResult.Failed(e, redactedMessage = "upload_stats $kind increment failed: ${e.javaClass.simpleName}")
        }
    }
}
