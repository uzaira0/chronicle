package com.openlattice.chronicle.collection.battery

import com.openlattice.chronicle.collection.CollectionModuleId
import com.openlattice.chronicle.collection.core.CollectionClock
import com.openlattice.chronicle.collection.core.CollectionLog
import com.openlattice.chronicle.collection.core.ModuleResult
import com.openlattice.chronicle.collection.core.PullCollectionModule
import com.openlattice.chronicle.collection.state.writeObservation
import com.openlattice.chronicle.collection.sink.BatterySampleSink
import com.openlattice.chronicle.storage.BatterySampleEntry
import com.openlattice.chronicle.storage.UploadServerEntity
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.util.TimeZone
import java.util.UUID

private const val TAG = "BatteryTelemetryCollectionModule"

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
 * mutable diagnostics are held in one immutable diagnostics snapshot behind a single
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
    private val ownerForSample: () -> UploadServerEntity? = { null },
    private val accountWriteResult: (UploadServerEntity?, ModuleResult, Int) -> Unit = { _, _, _ -> },
    clock: CollectionClock = CollectionClock.SYSTEM,
    private val log: CollectionLog = CollectionLog.LOGCAT,
) : PullCollectionModule(CollectionModuleId.BATTERY_TELEMETRY, clock, sink::queueDepth) {

    /** Computes the sample result without touching diagnostics state. */
    override fun runSample(now: Long): ModuleResult {
        if (!enrolled()) {
            return ModuleResult.Skipped("participant not enrolled")
        }
        val observation = sink.captureAdmission()
        val owner = ownerForSample()

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

        return when (val writeResult = observation.writeObservation { sink.write(listOf(entry)) }.also {
            accountWriteResult(owner, it, 1)
        }) {
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

}
