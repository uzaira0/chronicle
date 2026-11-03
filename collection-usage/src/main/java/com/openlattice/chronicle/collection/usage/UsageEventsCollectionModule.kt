package com.openlattice.chronicle.collection.usage

import android.util.Log
import com.openlattice.chronicle.android.ChronicleData
import com.openlattice.chronicle.collection.core.CollectionWindow
import java.util.NavigableMap

private const val TAG = "UsageEventsCollectionModule"

/**
 * Outcome of one usage-events poll pass.
 *
 * [events] is the polled [ChronicleData] (usage rows only — no device-state rows; the
 * device-state sampler stays outside this module). [currentPollTimestamp] is
 * the timestamp the checkpoint must advance to *after* the rows are durably persisted —
 * the orchestrator passes it back into [UsageEventsCollectionModule.commitCheckpoint]
 * inside the same Room transaction as the queue write.
 */
public data class UsagePollOutcome(
    val events: ChronicleData,
    val currentPollTimestamp: Long,
    val previousPollTimestamp: Long,
)

/**
 * The usage events data collection module (design §1A.2 `usage_events`, refactor plan §7.1).
 *
 * The wrapper around the usage-events
 * polling. It owns the *poll step* and the *checkpoint cursor* without changing any
 * collected value:
 *
 *  - **Two-timestamp poll preserved.** [pollWindow] resolves the previous poll timestamp
 *    from [UsagePollCheckpointStore] (falling back to [previousPollTimestampFallback]
 *    when no checkpoint row exists yet — exactly the legacy
 *    `UsagePollCheckpointDao.getLastPollTimestamp(...) ?: sensor.previousPollTimestamp()`
 *    order) and queries the half-open `[previous, current)` window through
 *    [UsageEventPoller].
 *  - **Activity-class / event-type / timezone / app-label / user mapping preserved.**
 *    All of that lives in [UsageEventsChronicleSensor] behind [UsageEventPoller]; this
 *    module never re-maps a field.
 *  - **Empty-result behaviour preserved.** An empty poll is still a success: the
 *    orchestrator commits the checkpoint (so the window advances) and writes nothing —
 *    [pollWindow] returns an empty [ChronicleData].
 *  - **Checkpoint atomicity preserved.** [commitCheckpoint] is *not* called by this
 *    module; the orchestrator (the worker's module-manager path) calls it inside the
 *    same Room transaction as the [com.openlattice.chronicle.collection.sink.UsageEventSink]
 *    write, so a crash between the write and the commit cannot advance the cursor past
 *    un-persisted rows (refactor plan §7.2 step 17).
 *
 * The worker drives [pollWindow] + [commitCheckpoint] so it keeps transactional control over persistence.
 *
 * This is a plain class holding only its seams (poller, checkpoint store) — no Android `Context`.
 *
 */
public class UsageEventsCollectionModule(
    private val poller: UsageEventPoller,
    private val checkpointStore: UsagePollCheckpointStore,
    /**
     * Fallback previous-poll timestamp used only when [UsagePollCheckpointStore] has no
     * checkpoint row yet. Production supplies `UsageEventsChronicleSensor.previousPollTimestamp()`
     * (the encrypted-prefs `LAST_USAGE_QUERY_TIMESTAMP`, defaulting to `now - 15min`).
     */
    private val previousPollTimestampFallback: () -> Long,
) {

    /**
     * Runs one poll pass over [window] and returns the polled usage events plus the
     * timestamps the orchestrator needs to commit the checkpoint.
     *
     * The previous poll timestamp is resolved from the checkpoint store — **[window]'s
     * `startEpochMs` is intentionally ignored**: the usage module owns its own cursor
     * (the `usage_poll_checkpoints` row), so the window's start cannot override it.
     * Only [CollectionWindow.endEpochMs] is used — it is the current poll timestamp
     * (the begin time of the *next* poll). Returns null for an unavailable read, leaving the checkpoint unchanged.
     *
     * @throws Exception propagated verbatim from [UsageEventPoller.poll] — a
     *   `UsageStatsManager` failure is surfaced, never
     *   silently swallowed. The orchestrator decides retry vs. failure.
     */
    public fun pollWindow(
        users: NavigableMap<Long, String>,
        window: CollectionWindow,
    ): UsagePollOutcome? {
        val previous = previousPollTimestamp()
        val current = window.endEpochMs
        return try {
            val events = poller.poll(previous, current, users) ?: return null
            Log.i(TAG, "usage events poll collected ${events.size} event(s)")
            UsagePollOutcome(events, currentPollTimestamp = current, previousPollTimestamp = previous)
        } catch (e: Exception) {
            Log.e(TAG, "usage events poll failed", e)
            throw e
        }
    }

    /**
     * Records that the usage-events checkpoint advanced to [currentPollTimestamp].
     *
     * The orchestrator MUST call this inside the same Room transaction as the
     * `UsageEventSink` write so the cursor never advances past un-persisted rows.
     */
    public fun commitCheckpoint(currentPollTimestamp: Long) {
        checkpointStore.commitPollTimestamp(currentPollTimestamp)
    }

    /**
     * [commitCheckpoint] for [outcome], only while the cursor still stands where its poll read it. Another poll that
     * moved it first, so this throws [UsageCursorMovedException] and the caller's transaction rolls back instead of
     * queuing overlapping events. The orchestrator re-polls from that cursor: the losing poll may have read a longer window.
     * A poll stamps its end before reading the cursor, so a poll that committed in between leaves this one an empty,
     * inverted window: the cursor already covers it and never moves back.
     */
    public fun commitCheckpoint(outcome: UsagePollOutcome) {
        val cursor = previousPollTimestamp()
        if (cursor != outcome.previousPollTimestamp) throw UsageCursorMovedException()
        if (outcome.currentPollTimestamp > cursor) commitCheckpoint(outcome.currentPollTimestamp)
    }

    private fun previousPollTimestamp(): Long =
        checkpointStore.readPreviousPollTimestamp() ?: previousPollTimestampFallback()
}

/** A concurrent poll changed the usage cursor; retry its remaining window. */
public class UsageCursorMovedException : IllegalStateException("usage cursor moved by another poll")
