package com.openlattice.chronicle.collection.battery

/**
 * Migration switch for the `battery_telemetry` collection path
 * (see `docs/SENSING-EXPANSION-DESIGN.md` §5 / §12; design §1C.4).
 *
 * `battery_telemetry` is a **new** collection module — there is no legacy
 * battery-sampling path to run in parallel and no parity baseline to prove, so the
 * "default `false` until parity" rule that gates the *replacement* switches
 * (`LifecycleWorkerMigration`, `NotificationsMigration`) does not apply here.
 *
 * The switch is kept anyway, for two reasons consistent with the modularization
 * discipline: it is the single off-button for battery collection without a code
 * change, and it keeps the per-module switch pattern uniform. It therefore defaults
 * to `true`.
 *
 * **Not yet consumed.** As of this change the `:collection-battery` module, its
 * `BatteryTelemetryModuleHolder`, and this switch are all in place, but no periodic
 * scheduler invokes `BatteryTelemetryCollectionModule.sample()` yet — that `:app`-side
 * worker is the next step in `docs/SENSING-EXPANSION-DESIGN.md` §12. Until that worker
 * lands and branches on this constant, the switch has no runtime effect and battery
 * telemetry is not collected regardless of its value.
 *
 * This is a compile-time constant, not a server/remote setting — whether a *study*
 * collects battery telemetry is governed separately by the per-study
 * `CollectionModuleSetting` for `battery_telemetry`.
 *
 */
public object BatteryCollectionMigration {

    /**
     * When the battery-collection scheduler lands and branches on this constant:
     * `true` ⇒ the periodic worker invokes the `battery_telemetry` module (subject to
     * the per-study setting and the enrollment check); `false` ⇒ battery collection is
     * fully disabled regardless of study settings. Until that scheduler exists this
     * constant has no consumer and therefore no runtime effect.
     */
    public const val USE_MODULE_MANAGER_BATTERY_PATH: Boolean = true
}
