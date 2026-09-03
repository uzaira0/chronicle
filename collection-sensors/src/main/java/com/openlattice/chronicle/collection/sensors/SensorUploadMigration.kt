package com.openlattice.chronicle.collection.sensors

/**
 * Internal migration switch for the sensor-upload path (Phase 6, subphase 6C,
 * refactor plan §9.3 / design §1C.4).
 *
 * Phase 6C wraps the sensor upload behind the [SensorUploadModule] interface. While both
 * the legacy `SensorUploadWorkerDelegate.execute()` call site and the new module wrapper
 * coexist, exactly one of them is selected per upload — the callers
 * (`CombinedUploadWorker`, `SensorUploadWorker`) branch on
 * [USE_MODULE_MANAGER_SENSOR_UPLOAD_PATH].
 *
 * **Default is the current behaviour.** [USE_MODULE_MANAGER_SENSOR_UPLOAD_PATH] is
 * `false`: the upload callers keep invoking `SensorUploadWorkerDelegate` directly — the
 * regression baseline. The [SensorUploadModule] wrapper stays dormant until its parity
 * tests pass.
 *
 * Both paths execute the **same** [SensorUploadWorkerDelegate.execute] logic — the
 * `/android/sensors` route, batch size 500, TTL + cap cleanup, exact-ID deletion after
 * durable acknowledgement by every configured destination on the normal delivery path, upload
 * stats, malformed-sample quarantine, and the combined-upload retry/failure semantics. The module
 * wrapper differs only in that it presents the result behind the [SensorUploadModule]
 * interface and exposes the malformed-sample count in diagnostics.
 *
 * This is a compile-time constant, not a server/remote setting.
 *
 */
public object SensorUploadMigration {

    /**
     * `false` ⇒ the upload callers run `SensorUploadWorkerDelegate.execute()` directly
     * (direct delegate path). `true` ⇒ they run it through the
     * [SensorUploadModule] wrapper.
     *
     * Both paths call the same delegate; this remains enabled after parity validation.
     */
    public const val USE_MODULE_MANAGER_SENSOR_UPLOAD_PATH: Boolean = true
}
