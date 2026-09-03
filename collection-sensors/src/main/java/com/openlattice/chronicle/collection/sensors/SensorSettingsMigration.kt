package com.openlattice.chronicle.collection.sensors

/**
 * Internal migration switch for the sensor-settings-refresh path (Phase 6, subphase 6B,
 * refactor plan §9.2 / design §1C.4).
 *
 * Phase 6B introduces a second sensor-settings-refresh path inside
 * `SensorSettingsRefreshWorker`: the *module path*, which routes the resolved settings
 * through [com.openlattice.chronicle.collection.settings.CollectionSettingsResolver] and
 * starts/stops collection through [HardwareSensorsCollectionModule] instead of calling
 * `HardwareSensorService.startService`/`stopService` directly. While both the legacy
 * inline path and the new module path coexist, exactly one of them runs per worker
 * execution — the worker branches on [USE_MODULE_MANAGER_SENSOR_SETTINGS_PATH]. There is
 * no third path.
 *
 * **Default is the current behaviour.** [USE_MODULE_MANAGER_SENSOR_SETTINGS_PATH] is
 * `false`: `SensorSettingsRefreshWorker` keeps invoking the legacy inline refresh exactly
 * as before — the regression baseline. The module path stays dormant in production until
 * its parity tests pass; flipping the flag to `true` is a deliberate, separately-reviewed
 * step (refactor plan §9.2 acceptance).
 *
 * Both paths preserve identical observable behaviour: the `AndroidSensor` endpoint fetch,
 * the missing-settings 404 → disable-on-missing handling, restart-on-changed /
 * start-on-newly-enabled / stop-on-disabled, schedule-sync-on-newly-enabled, availability
 * reporting to the active study server, and the upload-status update on an availability
 * failure. They differ only in *which class* owns the start/stop and whether the resolver
 * is consulted for the privacy-sensitive default.
 *
 * This is a compile-time constant, not a server/remote setting — it gates the migration
 * during development only and carries no privacy or wire-shape implication.
 *
 */
public object SensorSettingsMigration {

    /**
     * `false` ⇒ `SensorSettingsRefreshWorker` runs the legacy inline refresh (current
     * behaviour — the regression baseline). `true` ⇒ it runs the new module path through
     * [com.openlattice.chronicle.collection.settings.CollectionSettingsResolver] +
     * [HardwareSensorsCollectionModule].
     *
     * Defaults to `false`: the new path is off until parity is proven.
     */
    public const val USE_MODULE_MANAGER_SENSOR_SETTINGS_PATH: Boolean = true
}
