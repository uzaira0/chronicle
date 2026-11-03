package com.openlattice.chronicle.collection.lifecycle

import android.util.Log
import com.openlattice.chronicle.android.ChronicleData
import com.openlattice.chronicle.collection.core.ModuleResult
import com.openlattice.chronicle.collection.state.CollectionPersistenceGuard
import com.openlattice.chronicle.collection.sink.LifecycleEventSink
import com.openlattice.chronicle.collection.state.writeObservation
import com.openlattice.chronicle.models.ExtractedUsageEvent
import com.openlattice.chronicle.storage.QueueEntry
import java.util.UUID
import java.util.concurrent.ThreadLocalRandom

private const val TAG = "DeviceLifecycleCollectionModule"

/**
 * The device lifecycle data collection module (design §1A.2 `device_lifecycle`,
 * refactor plan §8, refactor plan decision #13).
 *
 * Wraps the `DeviceLifecycleEventRecorder` event-building and
 * persistence, **without changing any
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
 *    applied through [LifecycleDedupeStore].
 *  - **Batch write preserved** — all filtered events of one `recordAsync` call become a
 *    single `QueueEntry` (one serialized `ChronicleData`), as in `recordNow`.
 *  - **Async failure visibility** — a persistence failure surfaces as
 *    [ModuleResult.Failed] and is logged; [recordAsyncFailure] logs a failure that
 *    happened on the `recordAsync` background executor.
 *
 * This is a plain class holding only its seams (sink, dedupe store, enrollment check,
 * clock) — no Android `Context`. Each seam resolves whatever
 * `Context` it needs at construction (via [DeviceLifecycleModuleHolder]) and keeps only
 * a `Context`-free handle, so the module never stores a `Context` (design §1C / refactor
 * plan §6.1 guardrail 2); see [DeviceLifecycleModuleHolder].
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
     * Queue-entry serialization seam. Production supplies
     * `JsonSerializer::serializeQueueEntry`; tests pass a stub. This keeps the module
     * free of the `:app`-module `JsonSerializer` (which pulls in Olingo and the app wire boundary)
     * so `:collection-lifecycle` carries no `:app` dependency. The serialized bytes are
     * byte-identical to the legacy `JsonSerializer.serializeQueueEntry` output.
     */
    private val serializeQueueEntry: (ChronicleData) -> ByteArray,
    /**
     * Queue write-cursor seam. `QueueEntry.writeTimestamp` orders the queue, not a timestamp.
     * Production supplies `ChronicleDb.nextQueueWriteTimestamp`, which advances past anything
     * already queued so a backward device-clock step keeps write order. The default is identity
     * (wall clock unchanged) for tests that do not exercise ordering.
     */
    private val nextWriteTimestamp: (wallClockMillis: Long) -> Long = { it },
    private val now: () -> Long = System::currentTimeMillis,
    /**
     * Loss-accounting seam. Called before the write to capture the enrollment that owns it; the
     * returned recorder charges an abandoned batch (issue code, row count) to that owner.
     */
    private val captureLossRecorder: () -> (issue: String, count: Int) -> Unit = { { _, _ -> } },
) {

    /**
     * Persists [events] through the sanctioned lifecycle write path. This is the module
     * equivalent of the legacy `DeviceLifecycleEventRecorder.recordNow`.
     *
     * Behaviour, identical to `recordNow`:
     *  1. an empty input is a no-op success ([ModuleResult.Ok]`(0)`);
     *  2. if the participant is not enrolled, nothing is written ([ModuleResult.Skipped]);
     *  3. each event is filtered through the 2-second dedupe window; if all events are
     *     duplicates the call is a no-op success;
     *  4. the surviving events are serialized into **one** [QueueEntry] (a single
     *     `ChronicleData`) and written through [LifecycleEventSink].
     *
     * A persistence failure surfaces as [ModuleResult.Failed] — logged, never swallowed. The caller (the `recordAsync` shim) decides what to
     * do with a [ModuleResult.Failed]; it is not thrown.
     */
    public fun persist(
        events: List<ExtractedUsageEvent>,
        origin: CollectionPersistenceGuard = sink.captureAdmission(),
    ): ModuleResult {
        val recordLoss = captureLossRecorder()
        var eligibleCount: Int? = null
        val result = origin.writeObservation {
            // Under the lease: a capture window applied while these events waited refuses those captured inside it.
            val admitted = origin.outsideCaptureWindows(events) { it.timestamp.toInstant().toEpochMilli() }
            eligibleCount = admitted.size
            persistAdmitted(admitted) { eligibleCount = it }
        }
        // The admission transaction has ended; its rollback must not erase the loss diagnostic.
        val abandonedCount = when (result) {
            is ModuleResult.Failed -> eligibleCount ?: events.size
            is ModuleResult.Skipped -> eligibleCount ?: 0
            else -> 0
        }
        if (abandonedCount > 0) {
            val issue = if (result is ModuleResult.Failed) "LOCAL_WRITE_FAILED" else "COLLECTION_GATE_DROPPED"
            recordLoss(issue, abandonedCount)
        }
        return result
    }

    private fun persistAdmitted(events: List<ExtractedUsageEvent>, eligible: (Int) -> Unit): ModuleResult {
        val now = now()
        if (events.isEmpty()) return ModuleResult.Ok(0)
        if (!enrolled()) {
            eligible(0)
            Log.i(TAG, "Skipping ${events.size} lifecycle event(s): participant is not enrolled")
            return ModuleResult.Skipped("participant not enrolled")
        }
        val filtered = events.filter { event -> dedupeStore.shouldPersist(event, now) }
        eligible(filtered.size)
        if (filtered.isEmpty()) {
            Log.i(TAG, "All ${events.size} lifecycle event(s) suppressed by 2s dedupe window")
            return ModuleResult.Ok(0)
        }

        val entry = QueueEntry(
            writeTimestamp = nextWriteTimestamp(now),
            id = ThreadLocalRandom.current().nextLong(),
            data = serializeQueueEntry(ChronicleData(filtered.map { it.copy(clientEventId = UUID.randomUUID()) })),
        )

        return when (val result = sink.write(listOf(entry))) {
            is ModuleResult.Ok -> {
                Log.i(
                    TAG,
                    "Persisted ${filtered.size} lifecycle event(s): " +
                        filtered.joinToString { it.interactionType },
                )
                ModuleResult.Ok(filtered.size)
            }
            is ModuleResult.Failed -> {
                Log.e(TAG, "Failed to persist ${filtered.size} lifecycle event(s)", result.error)
                result
            }
            else -> result
        }
    }

    /**
     * Records that a lifecycle persistence attempt failed on a background executor.
     *
     * The `DeviceLifecycleEventRecorder.recordAsync` shim runs [persist] on a
     * single-thread executor. If that thread throws (an unexpected error outside the
     * [ModuleResult] contract), the shim calls this so the failure is logged rather than
     * silently swallowed.
     */
    public fun recordAsyncFailure(error: Throwable) {
        Log.e(TAG, "Async lifecycle event persistence failed on background executor", error)
    }
}
