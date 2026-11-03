package com.openlattice.chronicle.collection.battery

import com.openlattice.chronicle.collection.core.ModuleResult
import com.openlattice.chronicle.collection.core.PullAccounting
import com.openlattice.chronicle.collection.core.SnapshotCollectionModule
import com.openlattice.chronicle.collection.sink.BatterySampleSink
import com.openlattice.chronicle.collection.state.CollectionPersistenceGuard
import com.openlattice.chronicle.storage.BatterySampleEntry
import com.openlattice.chronicle.storage.storedInstant
import java.time.Instant
import java.util.TimeZone
import java.util.UUID

/**
 * The `battery_telemetry` data collection module (design §1A.2; see
 * `docs/SENSING-EXPANSION-DESIGN.md` §5).
 *
 * A **pull-style** module: each [sample] reads the current device battery state through
 * the injected [BatterySampleSource] and persists one [BatterySampleEntry] through the
 * sanctioned [BatterySampleSink]. Battery telemetry is structured numeric data — level,
 * charging state, plug type, temperature, voltage, health — so it gets its own
 * `battery_samples` table, mirroring how per-sensor modules use `sensor_samples`,
 * rather than the usage-event `dataQueue` path.
 *
 * Design rules honoured (matching [com.openlattice.chronicle.collection.lifecycle.DeviceLifecycleCollectionModule]):
 *  - **No `Context` held.** Every dependency is an injected seam — the sink, the
 *    [BatterySampleSource], the clock, and the [enrolled] check (design §1C, guardrail 2).
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
    enrolled: () -> Boolean,
    accounting: PullAccounting = PullAccounting(),
    now: () -> Long = System::currentTimeMillis,
    private val producerVersion: String? = null,
) : SnapshotCollectionModule<BatteryReading>("battery", enrolled, accounting, now) {
    override fun captureAdmission(): CollectionPersistenceGuard = sink.captureAdmission()

    override fun read(): BatteryReading? = source.read()

    override fun persist(reading: BatteryReading, now: Long): ModuleResult = sink.write(listOf(
        BatterySampleEntry(
            id = UUID.randomUUID().toString(),
            timestamp = storedInstant(Instant.ofEpochMilli(now)),
            timezone = TimeZone.getDefault().id,
            levelPercent = reading.levelPercent,
            chargingState = reading.chargingState.name,
            plugType = reading.plugType.name,
            temperatureDeciC = reading.temperatureDeciC,
            voltageMillivolts = reading.voltageMillivolts,
            health = reading.health.name,
            producerVersion = producerVersion,
        ),
    ))
}
