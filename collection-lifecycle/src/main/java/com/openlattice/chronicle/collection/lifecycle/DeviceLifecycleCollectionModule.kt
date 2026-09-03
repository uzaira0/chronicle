package com.openlattice.chronicle.collection.lifecycle

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
import com.openlattice.chronicle.collection.sink.LifecycleEventSink
import com.openlattice.chronicle.models.ExtractedUsageEvent
import com.openlattice.chronicle.storage.QueueEntry
import java.util.concurrent.ThreadLocalRandom

private const val TAG = "DeviceLifecycleCollectionModule"

/**
 * The device lifecycle data collection module (design §1A.2 `device_lifecycle`,
 * refactor plan §8, refactor plan decision #13).
 *
 * Phase 5 wraps the pre-existing `DeviceLifecycleEventRecorder` event-building and
 * persistence behind the [DataCollectionModule] boundary, **without changing any
 * observed behaviour**:
 *
 *  - **Supplemental event mapping is centralized** in [LifecycleEventMapper] (5A) —
 *    power/battery/low-memory mappings, the Android system package and label, the
 *    broadcast-action `activityClass`, the UTC timestamp and device timezone are preserved.
 *    Core screen/keyguard/startup/shutdown events remain exclusively in the original
 *    UsageStats timeline.
 *  - **Persistence is routed through [LifecycleEventSink]** (5B) — the sanctioned
 *    `dataQueue` writer. Lifecycle events are system-origin **usage-style** rows; the
 *    sink composes `UsageEventSink`, so the `QueueEntry` shape is unchanged.
 *  - **Non-enrolled skip preserved** — [persist] writes nothing when the participant is
 *    not enrolled (the enrollment check is supplied as the [enrolled] seam).
 *  - **Dedupe window preserved** — the 2-second `chronicle_lifecycle_recorder` dedupe is
 *    applied through [LifecycleDedupeStore]; suppressed events bump
 *    [diagnostics]`.notTracked` `droppedDuplicateCount`.
 *  - **Queue-size update preserved** — after a successful write [persist] calls the
 *    [updateQueueSize] seam with the new `dataQueue` depth, exactly as the legacy
 *    `recordNow` called `Utils.updateUploadQueueSize`. The sink itself never touches the
 *    queue-size pref (design §1C.2 — a sink holds no `Context`).
 *  - **Batch write preserved** — all filtered events of one `recordAsync` call become a
 *    single `QueueEntry` (one serialized `ChronicleData`), as in `recordNow`.
 *  - **Async failure visibility** — a persistence failure surfaces as
 *    [ModuleResult.Failed], is logged, and is recorded in [diagnostics] (`lastError`,
 *    `lastResult = FAILED`); [recordAsyncFailure] lets the `recordAsync` shim mark a
 *    failure that happened on its background executor. Async lifecycle failures are
 *    never silently swallowed (refactor plan §8.2 guardrail 3).
 *
 * **Diagnostics added (refactor plan §8.1 steps 18–19):** the last lifecycle event
 * (`interactionType` + epoch-millis) and the running dropped-duplicate count are exposed
 * through [CollectionModuleDiagnostics.notTracked] (the DTO has no first-class field for
 * either yet — mirrors how Phase 4 surfaced `checkpointTimestamp`).
 *
 * This is a plain class holding only its seams (sink, dedupe store, enrollment check,
 * queue-size update, clock, logger) — no Android [Context]. Each seam resolves whatever
 * `Context` it needs at construction (via [DeviceLifecycleModuleHolder]) and keeps only
 * a `Context`-free handle, so the module never stores a `Context` (design §1C / refactor
 * plan §6.1 guardrail 2). A single app-scoped instance is shared so the dropped-duplicate
 * counter and last-event diagnostics accumulate across broadcasts — see
 * [DeviceLifecycleModuleHolder].
 *
 */
public class DeviceLifecycleCollectionModule(
    private val sink: LifecycleEventSink,
    private val dedupeStore: LifecycleDedupeStore,
    /**
     * Enrollment check seam. Production supplies a lambda over
     * `EnrollmentSettings(context).getParticipationStatus() == ENROLLED`; tests pass a
     * fixed boolean. A non-enrolled participant skips the write (no row persisted).
     */
    private val enrolled: () -> Boolean,
    /**
     * Queue-size update seam, invoked with the new `dataQueue` depth after a successful
     * write. Production supplies `Utils.updateUploadQueueSize(context, depth)`; tests
     * capture the value. Mirrors the legacy `recordNow` post-write side effect.
     */
    private val updateQueueSize: (Int) -> Unit,
    /**
     * Queue-entry serialization seam. Production supplies
     * `JsonSerializer::serializeQueueEntry`; tests pass a stub. This keeps the module
     * free of the `:app`-module `JsonSerializer` (which pulls in Olingo and the app wire boundary)
     * so `:collection-lifecycle` carries no `:app` dependency. The serialized bytes are
     * byte-identical to the legacy `JsonSerializer.serializeQueueEntry` output.
     */
    private val serializeQueueEntry: (ChronicleData) -> ByteArray,
    private val clock: CollectionClock = CollectionClock.SYSTEM,
    private val log: CollectionLog = CollectionLog.LOGCAT,
) : DataCollectionModule {

    override val id: CollectionModuleId = CollectionModuleId.DEVICE_LIFECYCLE

    override val privacyClass: CollectionPrivacyClass = id.privacyClass

    init {
        require(privacyClass == id.privacyClass) {
            "DeviceLifecycleCollectionModule.privacyClass must equal id.privacyClass"
        }
    }

    // ----- module diagnostics state (design §1B.3 — redaction-safe operational telemetry) -----
    @Volatile private var lastRunEpochMs: Long? = null
    @Volatile private var lastResult: ModuleResult = ModuleResult.Skipped("not yet run")
    @Volatile private var lastEventCount: Int = 0
    @Volatile private var lastError: String? = null
    @Volatile private var lastEventInteractionType: String? = null
    @Volatile private var lastEventEpochMs: Long? = null

    // The dropped-duplicate counter accumulates across broadcasts on the shared instance.
    private val droppedDuplicateCount = java.util.concurrent.atomic.AtomicLong(0L)

    /**
     * Persists [events] through the sanctioned lifecycle write path. This is the module
     * equivalent of the legacy `DeviceLifecycleEventRecorder.recordNow`.
     *
     * Behaviour, identical to `recordNow`:
     *  1. an empty input is a no-op success ([ModuleResult.Ok]`(0)`);
     *  2. if the participant is not enrolled, nothing is written ([ModuleResult.Skipped]);
     *  3. each event is filtered through the 2-second dedupe window — suppressed events
     *     increment the dropped-duplicate counter; if all events are duplicates the call
     *     is a no-op success;
     *  4. the surviving events are serialized into **one** [QueueEntry] (a single
     *     `ChronicleData`) and written through [LifecycleEventSink];
     *  5. on success the [updateQueueSize] seam is called with the new `dataQueue` depth.
     *
     * A persistence failure surfaces as [ModuleResult.Failed] — logged and recorded in
     * diagnostics, never swallowed. The caller (the `recordAsync` shim) decides what to
     * do with a [ModuleResult.Failed]; it is not thrown.
     */
    public fun persist(events: List<ExtractedUsageEvent>): ModuleResult {
        val now = clock.nowEpochMs()
        lastRunEpochMs = now

        if (events.isEmpty()) {
            lastResult = ModuleResult.Ok(0)
            lastEventCount = 0
            lastError = null
            return lastResult
        }

        if (!enrolled()) {
            log.info(TAG, "Skipping ${events.size} lifecycle event(s): participant is not enrolled")
            lastResult = ModuleResult.Skipped("participant not enrolled")
            lastEventCount = 0
            lastError = null
            return lastResult
        }

        val filtered = events.filter { event ->
            val keep = dedupeStore.shouldPersist(event, now)
            if (!keep) droppedDuplicateCount.incrementAndGet()
            keep
        }
        if (filtered.isEmpty()) {
            log.info(TAG, "All ${events.size} lifecycle event(s) suppressed by 2s dedupe window")
            lastResult = ModuleResult.Ok(0)
            lastEventCount = 0
            lastError = null
            return lastResult
        }

        val entry = QueueEntry(
            writeTimestamp = now,
            id = ThreadLocalRandom.current().nextLong(),
            data = serializeQueueEntry(ChronicleData(filtered)),
        )

        return when (val result = sink.write(listOf(entry))) {
            is ModuleResult.Ok -> {
                updateQueueSize(sink.queueDepth())
                val last = filtered.last()
                lastEventInteractionType = last.interactionType
                lastEventEpochMs = now
                lastEventCount = filtered.size
                lastError = null
                lastResult = ModuleResult.Ok(filtered.size)
                log.info(
                    TAG,
                    "Persisted ${filtered.size} lifecycle event(s): " +
                        filtered.joinToString { it.interactionType },
                )
                lastResult
            }
            is ModuleResult.Failed -> {
                lastEventCount = 0
                lastError = result.redactedMessage
                lastResult = result
                log.error(TAG, "Failed to persist ${filtered.size} lifecycle event(s)", result.error)
                result
            }
            else -> {
                // LifecycleEventSink only ever returns Ok / Failed; defensively record.
                lastResult = result
                result
            }
        }
    }

    /**
     * Records that a lifecycle persistence attempt failed on a background executor.
     *
     * The `DeviceLifecycleEventRecorder.recordAsync` shim runs [persist] on a
     * single-thread executor. If that thread throws (an unexpected error outside the
     * [ModuleResult] contract), the shim calls this so the failure is **logged and
     * visible in diagnostics** rather than silently swallowed (refactor plan §8.2
     * step 14, guardrail 3).
     */
    public fun recordAsyncFailure(error: Throwable) {
        lastResult = ModuleResult.Failed(error, redactedMessage = "async lifecycle persist failed: ${error.javaClass.simpleName}")
        lastError = "async lifecycle persist failed: ${error.javaClass.simpleName}"
        log.error(TAG, "Async lifecycle event persistence failed on background executor", error)
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
        queueDepth = runCatching { sink.queueDepth() }.getOrDefault(0),
        lastError = lastError,
        redactedParticipantRef = null,
        // Last-event identity and dropped-duplicate count are module-specific telemetry
        // not modelled as first-class diagnostics fields; surface them via notTracked
        // until counter fields exist (mirrors Phase 4 `checkpointTimestamp`).
        notTracked = buildSet {
            add("droppedDuplicateCount=${droppedDuplicateCount.get()}")
            val type = lastEventInteractionType
            val ts = lastEventEpochMs
            if (type != null && ts != null) {
                add("lastEvent=$type@$ts")
            } else {
                add("lastEvent")
            }
        },
    )

    /**
     * Pull-style poll over [window]: not used for broadcast-driven collection (those go
     * through [persist]). The lifecycle module is broadcast-driven; the `poll` contract
     * method exists for `status`/`diagnostics` introspection and reports a no-op.
     */
    override fun poll(context: Context, window: CollectionWindow): ModuleResult =
        ModuleResult.Skipped("device_lifecycle is broadcast-driven; use persist()")

    /** No-op: device lifecycle is not a push service. */
    override fun start(context: Context): ModuleResult = ModuleResult.Skipped("device_lifecycle is broadcast-driven")

    /** No-op: device lifecycle is not a push service. */
    override fun stop(context: Context): ModuleResult = ModuleResult.Skipped("device_lifecycle is broadcast-driven")

    /** No-op: this module buffers nothing in memory; persistence is per-broadcast. */
    override fun flush(context: Context): ModuleResult = ModuleResult.Skipped("device_lifecycle buffers nothing")
}
