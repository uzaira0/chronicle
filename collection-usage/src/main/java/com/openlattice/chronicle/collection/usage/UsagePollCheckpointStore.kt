package com.openlattice.chronicle.collection.usage

import com.openlattice.chronicle.sensors.USAGE_EVENTS_SENSOR_CHECKPOINT
import com.openlattice.chronicle.storage.UsagePollCheckpointDao
import com.openlattice.chronicle.storage.UsagePollCheckpointEntity

/**
 * The checkpoint-cursor seam for the usage events module (Phase 4, subphase 4A).
 *
 * The legacy [com.openlattice.chronicle.services.usage.UsageCollectionDelegate] reads the
 * previous poll timestamp from [UsagePollCheckpointDao] (keyed by
 * [USAGE_EVENTS_SENSOR_CHECKPOINT]) and `upsert`s the new one inside the *same* Room
 * transaction as the queue write. [UsagePollCheckpointStore] is the interface that lets
 * [UsageEventsCollectionModule] own that cursor semantics while remaining JVM-unit
 * testable: production wires in [DaoUsagePollCheckpointStore]; tests inject a fake.
 *
 * Semantics preserved verbatim from the legacy delegate:
 *  - [readPreviousPollTimestamp] returns the persisted cursor, or `null` when no
 *    checkpoint row exists yet — the caller then falls back to the sensor's own
 *    encrypted-prefs default (`previousPollTimestamp()`), exactly as the worker does.
 *  - [commitPollTimestamp] is an `upsert` (Room `OnConflictStrategy.REPLACE`) of the
 *    `(USAGE_EVENTS_SENSOR_CHECKPOINT, currentPollTimestamp)` row. It MUST be called
 *    inside the same transaction as the queue write so a crash between the two cannot
 *    advance the cursor past un-persisted rows (refactor plan §7.2 step 17).
 *
 */
public interface UsagePollCheckpointStore {

    /** The persisted previous-poll cursor, or `null` if no checkpoint has been committed yet. */
    public fun readPreviousPollTimestamp(): Long?

    /**
     * Upserts the usage-events checkpoint to [currentPollTimestamp].
     *
     * Must run inside the queue-write transaction — see the class doc.
     */
    public fun commitPollTimestamp(currentPollTimestamp: Long)
}

/**
 * Production [UsagePollCheckpointStore] backed by [UsagePollCheckpointDao], keyed by the
 * existing [USAGE_EVENTS_SENSOR_CHECKPOINT] sensor name so the `usage_poll_checkpoints`
 * row is shared byte-for-byte with the legacy path — already-checkpointed enrolled
 * devices are unaffected (refactor plan decisions #10–11).
 */
public class DaoUsagePollCheckpointStore(
    private val dao: UsagePollCheckpointDao,
) : UsagePollCheckpointStore {

    override fun readPreviousPollTimestamp(): Long? =
        dao.getLastPollTimestamp(USAGE_EVENTS_SENSOR_CHECKPOINT)

    override fun commitPollTimestamp(currentPollTimestamp: Long) {
        dao.upsert(UsagePollCheckpointEntity(USAGE_EVENTS_SENSOR_CHECKPOINT, currentPollTimestamp))
    }
}
