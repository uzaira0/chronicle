package com.openlattice.chronicle.collection.battery

import android.content.Context
import com.openlattice.chronicle.collection.CollectionModuleDiagnostics
import com.openlattice.chronicle.collection.CollectionModuleId
import com.openlattice.chronicle.collection.CollectionPrivacyClass
import com.openlattice.chronicle.collection.core.CollectionClock
import com.openlattice.chronicle.collection.core.CollectionLog
import com.openlattice.chronicle.collection.core.CollectionModuleStatus
import com.openlattice.chronicle.collection.core.CollectionWindow
import com.openlattice.chronicle.collection.core.DataCollectionModule
import com.openlattice.chronicle.collection.core.ModuleResult
import com.openlattice.chronicle.collection.sink.BatterySampleSink
import com.openlattice.chronicle.storage.BatterySampleEntry
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.util.TimeZone
import java.util.UUID

private const val TAG = "BatteryTelemetryCollectionModule"

/** Sentinel for [CollectionModuleDiagnostics.queueDepth] when the row count cannot be read. */
private const val QUEUE_DEPTH_UNAVAILABLE = -1

/**
 * The `battery_telemetry` data collection module (design §1A.2; see
 * `docs/SENSING-EXPANSION-DESIGN.md` §5).
 *
 * A **pull-style** module: each [sample] reads the current device battery state through
 * the injected [BatterySampleSource] and persists one [BatterySampleEntry] through the
 * sanctioned [BatterySampleSink]. Battery telemetry is structured numeric data — level,
 * charging state, plug type, temperature, voltage, health — so it gets its own
 * `battery_samples` table, mirroring how `hardware_sensors` uses `sensor_samples`,
 * rather than the usage-event `dataQueue` path.
 *
 * Design rules honoured (matching [com.openlattice.chronicle.collection.lifecycle.DeviceLifecycleCollectionModule]):
 *  - **No `Context` held.** Every dependency is an injected seam — the sink, the
 *    [BatterySampleSource], the [CollectionClock], the [CollectionLog], and the
 *    [enrolled] check. The `Context`-typed [DataCollectionModule] methods receive their
 *    `Context` per call and the module never stores it (design §1C, guardrail 2).
 *  - **Non-enrolled is a skip.** [sample] writes nothing when the participant is not
 *    enrolled; it returns [ModuleResult.Skipped].
 *  - **Explicit results, no thrown failures.** A persistent sink failure — and any
 *    exception thrown by the [BatterySampleSource] (e.g. a platform `registerReceiver`
 *    failure) — surfaces as [ModuleResult.Failed]; it is never propagated out of
 *    [sample]. A momentarily unavailable battery state is a transient [ModuleResult.Retry].
 *
 * **Thread-safety.** A single app-scoped instance is shared (see
 * `BatteryTelemetryModuleHolder`) so diagnostics accumulate across poll ticks. The
 * mutable diagnostics are held in one immutable [SampleState] behind a single
 * `@Volatile` reference, swapped atomically at the end of each [sample] — so a
 * concurrent [diagnostics]/[status] reader always sees an internally consistent
 * snapshot even if two cadence ticks overlap.
 *
 */
public class BatteryTelemetryCollectionModule(
    private val sink: BatterySampleSink,
    private val source: BatterySampleSource,
    /**
     * Enrollment-check seam. Production supplies a lambda over
     * `EnrollmentSettings.getParticipationStatus() == ENROLLED`; tests pass a fixed
     * boolean. A non-enrolled participant skips the write.
     */
    private val enrolled: () -> Boolean,
    private val clock: CollectionClock = CollectionClock.SYSTEM,
    private val log: CollectionLog = CollectionLog.LOGCAT,
) : DataCollectionModule {

    override val id: CollectionModuleId = CollectionModuleId.BATTERY_TELEMETRY

    override val privacyClass: CollectionPrivacyClass = id.privacyClass

    init {
        require(privacyClass == id.privacyClass) {
            "BatteryTelemetryCollectionModule.privacyClass must equal id.privacyClass"
        }
    }

    /**
     * Immutable snapshot of the module's redaction-safe diagnostics (design §1B.3).
     * Swapped atomically so a reader never observes a half-updated set of fields.
     */
    private data class SampleState(
        val lastRunEpochMs: Long?,
        val lastResult: ModuleResult,
        val itemsCollected: Int,
        val lastError: String?,
    )

    @Volatile
    private var state: SampleState = SampleState(
        lastRunEpochMs = null,
        lastResult = ModuleResult.Skipped("not yet run"),
        itemsCollected = 0,
        lastError = null,
    )

    /**
     * Takes one battery sample and persists it — the module's core pull operation.
     *
     * Behaviour:
     *  1. if the participant is not enrolled, nothing is written ([ModuleResult.Skipped]);
     *  2. if reading the battery state throws, the failure is caught and surfaced as
     *     [ModuleResult.Failed] — never propagated out of this method;
     *  3. if the battery state is momentarily unavailable, no row is written and the
     *     call is a transient [ModuleResult.Retry];
     *  4. otherwise the reading is timestamped from [clock], built into one
     *     [BatterySampleEntry], and written through [BatterySampleSink].
     *
     * The diagnostics snapshot is updated exactly once, atomically, before returning.
     */
    public fun sample(): ModuleResult {
        val now = clock.nowEpochMs()
        val result = runSample(now)
        state = SampleState(
            lastRunEpochMs = now,
            lastResult = result,
            itemsCollected = if (result is ModuleResult.Ok) result.items else 0,
            lastError = if (result is ModuleResult.Failed) result.redactedMessage else null,
        )
        return result
    }

    /** Computes the sample result without touching diagnostics state. */
    private fun runSample(now: Long): ModuleResult {
        if (!enrolled()) {
            return ModuleResult.Skipped("participant not enrolled")
        }

        val reading: BatteryReading? = try {
            source.read()
        } catch (e: Exception) {
            // A BatterySampleSource (e.g. AndroidBatterySampleSource.registerReceiver)
            // may throw on some platforms/contexts. The DataCollectionModule contract
            // requires a persistent failure to surface as ModuleResult.Failed, never as
            // a thrown exception — so it is caught here and converted.
            log.error(TAG, "Battery source threw while reading device state", e)
            return ModuleResult.Failed(e, redactedMessage = "battery source read failed: ${e.javaClass.simpleName}")
        }

        if (reading == null) {
            log.warn(TAG, "Battery state unavailable; will retry on the next poll")
            return ModuleResult.Retry("battery state unavailable")
        }

        val entry = BatterySampleEntry(
            id = UUID.randomUUID().toString(),
            timestamp = OffsetDateTime.ofInstant(Instant.ofEpochMilli(now), ZoneOffset.UTC).toString(),
            timezone = TimeZone.getDefault().id,
            levelPercent = reading.levelPercent,
            chargingState = reading.chargingState.name,
            plugType = reading.plugType.name,
            temperatureDeciC = reading.temperatureDeciC,
            voltageMillivolts = reading.voltageMillivolts,
            health = reading.health.name,
        )

        return when (val writeResult = sink.write(listOf(entry))) {
            is ModuleResult.Ok -> {
                log.info(TAG, "Persisted 1 battery sample (level=${reading.levelPercent}%)")
                ModuleResult.Ok(1)
            }
            is ModuleResult.Failed -> {
                log.error(TAG, "Failed to persist battery sample", writeResult.error)
                writeResult
            }
            else -> {
                // BatterySampleSink only ever returns Ok / Failed; defensively pass through.
                writeResult
            }
        }
    }

    override fun status(): CollectionModuleStatus = when (state.lastResult) {
        is ModuleResult.Failed -> CollectionModuleStatus.FAILED
        is ModuleResult.Ok -> CollectionModuleStatus.IDLE
        is ModuleResult.Retry -> CollectionModuleStatus.DEGRADED
        is ModuleResult.Skipped -> CollectionModuleStatus.IDLE
    }

    override fun diagnostics(): CollectionModuleDiagnostics {
        val snapshot = state
        return CollectionModuleDiagnostics(
            moduleId = id,
            privacyClass = privacyClass,
            lastRunEpochMs = snapshot.lastRunEpochMs,
            lastResult = snapshot.lastResult.label,
            itemsCollected = snapshot.itemsCollected,
            // A query failure is reported as QUEUE_DEPTH_UNAVAILABLE rather than 0, so a
            // DB fault is not silently indistinguishable from an empty table.
            queueDepth = runCatching { sink.queueDepth() }.getOrDefault(QUEUE_DEPTH_UNAVAILABLE),
            lastError = snapshot.lastError,
            redactedParticipantRef = null,
        )
    }

    /**
     * Pull-style poll: takes one battery sample. The [window] is unused — a battery
     * reading is point-in-time, not a range — and [context] is unused because the
     * battery state is read through the injected [BatterySampleSource].
     */
    override fun poll(context: Context, window: CollectionWindow): ModuleResult = sample()

    /** No-op: `battery_telemetry` is pull-style, not a push service. */
    override fun start(context: Context): ModuleResult = ModuleResult.Skipped("battery_telemetry is pull-style")

    /** No-op: `battery_telemetry` is pull-style, not a push service. */
    override fun stop(context: Context): ModuleResult = ModuleResult.Skipped("battery_telemetry is pull-style")

    /** No-op: each [sample] persists immediately; the module buffers nothing. */
    override fun flush(context: Context): ModuleResult = ModuleResult.Skipped("battery_telemetry buffers nothing")
}
