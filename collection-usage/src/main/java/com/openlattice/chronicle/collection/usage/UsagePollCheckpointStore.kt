package com.openlattice.chronicle.collection.usage

import com.openlattice.chronicle.sensors.USAGE_EVENTS_SENSOR_CHECKPOINT
import com.openlattice.chronicle.storage.UsagePollCheckpointDao
import com.openlattice.chronicle.storage.UsagePollCheckpointEntity

/** Usage cursor commits share the transaction that persists the polled queue rows. */
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

/** Room checkpoint keyed by enrollment and consent generation. */
public class DaoUsagePollCheckpointStore(
    private val dao: UsagePollCheckpointDao,
    private val scope: String,
    private val acceptanceFloor: Long = 0,
) : UsagePollCheckpointStore {

    private fun key(): String = "$USAGE_EVENTS_SENSOR_CHECKPOINT:$scope"

    override fun readPreviousPollTimestamp(): Long? {
        dao.getLastPollTimestamp(key())?.let { return it.coerceAtLeast(acceptanceFloor) }
        return acceptanceFloor.takeIf { it > 0 }
    }

    /** Remember the first accepted start before acquisition, even if its batch is later refused. */
    public fun readOrRememberStart(fallback: () -> Long): Long = readPreviousPollTimestamp() ?: fallback().also {
        dao.upsert(UsagePollCheckpointEntity(key(), it.coerceAtLeast(acceptanceFloor)))
    }

    override fun commitPollTimestamp(currentPollTimestamp: Long) {
        dao.upsert(UsagePollCheckpointEntity(key(), currentPollTimestamp))
    }
}
