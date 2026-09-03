package com.openlattice.chronicle.collection.usage

import android.content.Context
import com.openlattice.chronicle.android.ChronicleData
import com.openlattice.chronicle.collection.CollectionModuleDiagnostics
import com.openlattice.chronicle.collection.CollectionModuleId
import com.openlattice.chronicle.collection.CollectionPrivacyClass
import com.openlattice.chronicle.collection.core.CollectionClock
import com.openlattice.chronicle.collection.core.CollectionLog
import com.openlattice.chronicle.collection.core.CollectionModuleStatus
import com.openlattice.chronicle.collection.core.CollectionWindow
import com.openlattice.chronicle.collection.core.DataCollectionModule
import com.openlattice.chronicle.collection.core.ModuleResult

private const val TAG = "UsageEventsCollectionModule"

/**
 * Outcome of one usage-events poll pass.
 *
 * [events] is the polled [ChronicleData] (usage rows only — no device-state rows; the
 * device-state sampler stays outside this module, Phase 5). [currentPollTimestamp] is
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
 * Phase 4 / subphase 4A: this is the additive wrapper around the existing usage-events
 * polling. It owns the *poll step* and the *checkpoint cursor*, and exposes module
 * diagnostics — without changing any collected value:
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
 *    [pollWindow] returns an empty [ChronicleData], and [poll] reports `Ok(0)`.
 *  - **Checkpoint atomicity preserved.** [commitCheckpoint] is *not* called by this
 *    module; the orchestrator (the worker's module-manager path) calls it inside the
 *    same Room transaction as the [com.openlattice.chronicle.collection.sink.UsageEventSink]
 *    write, so a crash between the write and the commit cannot advance the cursor past
 *    un-persisted rows (refactor plan §7.2 step 17).
 *
 * The [DataCollectionModule.poll] contract method runs a poll pass for `status`/
 * `diagnostics` introspection and returns a [ModuleResult]; the worker's module-manager
 * path instead drives [pollWindow] + [commitCheckpoint] so it keeps transactional
 * control over persistence.
 *
 * `start`/`stop` are no-ops — usage events is a pull module (returns
 * [ModuleResult.Skipped]). `flush` is a no-op for the same reason: this module buffers
 * nothing in memory.
 *
 * This is a plain class holding only its seams (poller, checkpoint store, clock,
 * logger) — no Android [Context]; the [Context] is passed per call.
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
    private val clock: CollectionClock = CollectionClock.SYSTEM,
    private val log: CollectionLog = CollectionLog.LOGCAT,
) : DataCollectionModule {

    override val id: CollectionModuleId = CollectionModuleId.USAGE_EVENTS

    override val privacyClass: CollectionPrivacyClass = id.privacyClass

    init {
        require(privacyClass == id.privacyClass) {
            "UsageEventsCollectionModule.privacyClass must equal id.privacyClass"
        }
    }

    // ----- module diagnostics state (design §1B.3 — redaction-safe operational telemetry) -----
    @Volatile private var lastRunEpochMs: Long? = null
    @Volatile private var lastResult: ModuleResult = ModuleResult.Skipped("not yet run")
    @Volatile private var lastEventCount: Int = 0
    @Volatile private var lastCheckpointTimestamp: Long? = null
    @Volatile private var lastError: String? = null

    /**
     * Runs one poll pass over [window] and returns the polled usage events plus the
     * timestamps the orchestrator needs to commit the checkpoint.
     *
     * The previous poll timestamp is resolved from the checkpoint store — **[window]'s
     * `startEpochMs` is intentionally ignored**: the usage module owns its own cursor
     * (the `usage_poll_checkpoints` row), so the window's start cannot override it.
     * Only [CollectionWindow.endEpochMs] is used — it is the current poll timestamp
     * (the begin time of the *next* poll). Diagnostics (last poll, event count) are
     * updated here; the checkpoint diagnostic is updated by [commitCheckpoint].
     *
     * @throws Exception propagated verbatim from [UsageEventPoller.poll] — a
     *   `UsageStatsManager` failure is surfaced (and recorded in diagnostics), never
     *   silently swallowed. The orchestrator decides retry vs. failure.
     */
    public fun pollWindow(
        users: java.util.NavigableMap<Long, String>,
        window: CollectionWindow,
    ): UsagePollOutcome {
        val previous = checkpointStore.readPreviousPollTimestamp() ?: previousPollTimestampFallback()
        val current = window.endEpochMs
        lastRunEpochMs = clock.nowEpochMs()
        return try {
            val events = poller.poll(previous, current, users)
            lastEventCount = events.size
            lastError = null
            lastResult = ModuleResult.Ok(events.size)
            log.info(TAG, "usage events poll collected ${events.size} event(s)")
            UsagePollOutcome(events, currentPollTimestamp = current, previousPollTimestamp = previous)
        } catch (e: Exception) {
            lastEventCount = 0
            lastError = "UsageStatsManager poll failed: ${e.javaClass.simpleName}"
            lastResult = ModuleResult.Failed(e, redactedMessage = lastError!!)
            log.error(TAG, "usage events poll failed", e)
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
        lastCheckpointTimestamp = currentPollTimestamp
    }

    override fun status(): CollectionModuleStatus = when (lastResult) {
        is ModuleResult.Failed -> CollectionModuleStatus.FAILED
        is ModuleResult.Ok -> CollectionModuleStatus.IDLE
        is ModuleResult.Retry -> CollectionModuleStatus.DEGRADED
        is ModuleResult.Skipped -> CollectionModuleStatus.IDLE
    }

    override fun diagnostics(): CollectionModuleDiagnostics = CollectionModuleDiagnostics(
        moduleId = id,
        privacyClass = privacyClass,
        lastRunEpochMs = lastRunEpochMs,
        lastResult = lastResult.label,
        itemsCollected = lastEventCount,
        queueDepth = 0,
        lastError = lastError,
        redactedParticipantRef = null,
        // Checkpoint timestamp is module-specific telemetry not modelled as a first-class
        // diagnostics field; surface it via notTracked until a counter field exists.
        notTracked = lastCheckpointTimestamp
            ?.let { setOf("checkpointTimestamp=$it") }
            ?: setOf("checkpointTimestamp"),
    )

    /**
     * Pull-style poll for **introspection only** — returns an event-count [ModuleResult]
     * for `status`/`diagnostics`. It does **not** persist, does not commit the checkpoint,
     * and polls with an empty `users` map (so it must not be used to produce rows for
     * upload — user attribution would be lost). The worker's module-manager path uses
     * [pollWindow] + [commitCheckpoint] for real collection, keeping transactional control.
     */
    override fun poll(context: Context, window: CollectionWindow): ModuleResult {
        return try {
            val outcome = pollWindow(java.util.TreeMap(), window)
            ModuleResult.Ok(outcome.events.size)
        } catch (e: Exception) {
            ModuleResult.Failed(e, redactedMessage = "UsageStatsManager poll failed: ${e.javaClass.simpleName}")
        }
    }

    /** No-op: usage events is a pull module, not a push service. */
    override fun start(context: Context): ModuleResult = ModuleResult.Skipped("usage_events is a pull module")

    /** No-op: usage events is a pull module, not a push service. */
    override fun stop(context: Context): ModuleResult = ModuleResult.Skipped("usage_events is a pull module")

    /** No-op: this module buffers nothing in memory; persistence is per-poll. */
    override fun flush(context: Context): ModuleResult = ModuleResult.Skipped("usage_events buffers nothing")
}
