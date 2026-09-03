package com.openlattice.chronicle.collection.upload

/**
 * Internal migration switch for the combined-upload orchestrator (Phase 8, subphase 8B,
 * refactor plan §11.2 / design §1C.4).
 *
 * Phase 8B extracts the pure decision logic of `runCombinedUpload` into
 * [runCombinedUploadCore] so the usage-first / sensor-second order, the partial-failure
 * retry, the max-retry failure cap and the stats cleanup can be unit tested without an
 * Android `Context`, WorkManager, or Room. While both the legacy inline body of
 * `runCombinedUpload` and the extracted [runCombinedUploadCore] path coexist,
 * `runCombinedUpload` branches on [USE_COMBINED_UPLOAD_ORCHESTRATOR] — exactly one runs
 * per worker execution.
 *
 * **Default is the current behaviour.** [USE_COMBINED_UPLOAD_ORCHESTRATOR] is `false`: the
 * worker keeps running the legacy inline decision code — the regression baseline. The
 * extracted orchestrator stays dormant in production until its parity tests pass; flipping
 * the flag is a deliberate, separately-reviewed step (refactor plan §11.2 acceptance).
 *
 * Both paths are designed to produce the **identical** `ListenableWorker.Result` for the
 * identical delegate outcomes — `runCombinedUploadCore` is a behaviour-preserving
 * extraction, not a rewrite. The switch exists so the extraction can be proven at parity
 * before it becomes the only path, mirroring `SensorUploadMigration` /
 * `UsageWorkerMigration` / `LifecycleWorkerMigration`.
 *
 * This is a compile-time constant, not a server/remote setting; it carries no privacy or
 * wire-shape implication.
 *
 */
public object UploadTelemetryMigration {

    /**
     * `false` ⇒ `runCombinedUpload` runs the legacy inline decision code (current
     * behaviour — the regression baseline). `true` ⇒ it routes the same delegate outcomes
     * through [runCombinedUploadCore].
     *
     * Defaults to `false`: the extracted orchestrator is off until parity is proven.
     */
    public const val USE_COMBINED_UPLOAD_ORCHESTRATOR: Boolean = true
}
