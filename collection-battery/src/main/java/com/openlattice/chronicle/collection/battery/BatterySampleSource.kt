package com.openlattice.chronicle.collection.battery

import com.openlattice.chronicle.collection.BatteryChargingState
import com.openlattice.chronicle.collection.BatteryHealth
import com.openlattice.chronicle.collection.BatteryPlugType

/**
 * A point-in-time battery reading produced by a [BatterySampleSource].
 *
 * It carries no timestamp and no id — [BatteryTelemetryCollectionModule] adds those
 * from its injected [com.openlattice.chronicle.collection.core.CollectionClock] when it
 * builds the persisted row, so the time source stays a single injected seam.
 */
public data class BatteryReading(
    public val levelPercent: Int,
    public val chargingState: BatteryChargingState,
    public val plugType: BatteryPlugType,
    public val temperatureDeciC: Int,
    public val voltageMillivolts: Int,
    public val health: BatteryHealth,
)

/**
 * Dependency-inversion seam for reading the device battery state
 * (see `docs/SENSING-EXPANSION-DESIGN.md` §5).
 *
 * [BatteryTelemetryCollectionModule] depends on this interface, never on Android's
 * `BatteryManager` directly — so the module is a plain, `Context`-free class that JVM
 * unit tests can drive with a fake reading. The production implementation is
 * [AndroidBatterySampleSource]; it is wired in by `BatteryTelemetryModuleHolder`.
 *
 * Declared as a `fun interface` so tests can supply a reading with a lambda.
 */
public fun interface BatterySampleSource {
    /** Reads the current device battery state, or `null` if it is unavailable. */
    public fun read(): BatteryReading?
}
