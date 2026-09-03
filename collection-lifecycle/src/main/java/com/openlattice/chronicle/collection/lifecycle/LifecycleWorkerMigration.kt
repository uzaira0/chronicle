package com.openlattice.chronicle.collection.lifecycle

/**
 * Internal migration switch for the device-lifecycle persistence path (Phase 5,
 * subphase 5B, refactor plan §8.2 step 2 / design §1C.4).
 *
 * Phase 5B introduces a second lifecycle-persistence path inside the
 * `DeviceLifecycleEventRecorder.recordAsync` compatibility shim: the *module path*,
 * which routes the recorded events through [DeviceLifecycleCollectionModule] and the
 * sanctioned [com.openlattice.chronicle.collection.sink.LifecycleEventSink]. While
 * both the legacy inline `recordNow` path and the new module path coexist, exactly
 * one of them runs per `recordAsync` invocation — the shim branches on
 * [USE_MODULE_MANAGER_LIFECYCLE_PATH]. There is no third path and no double-write.
 *
 * **Default is the current behaviour.** [USE_MODULE_MANAGER_LIFECYCLE_PATH] is
 * `false`: `recordAsync` keeps invoking the legacy inline `recordNow` exactly as
 * before — the regression baseline. The module path stays dormant in production
 * until its parity tests pass; flipping the flag to `true` is a deliberate,
 * separately-reviewed step (refactor plan §8.2 acceptance, decision #20: "remove the
 * direct writer only after tests pass").
 *
 * Both paths preserve identical observable behaviour: the non-enrolled skip, the
 * `chronicle_lifecycle_recorder` 2-second dedupe window, the `QueueEntry`
 * serialization of `ChronicleData`, and the post-write `Utils.updateUploadQueueSize`
 * call. They differ only in *which class* owns the write and whether module
 * diagnostics are updated. The module path additionally surfaces async failures and
 * the dropped-duplicate count in [DeviceLifecycleCollectionModule.diagnostics].
 *
 * This is a compile-time constant, not a server/remote setting — it gates the
 * migration during development only and carries no privacy or wire-shape implication.
 *
 */
public object LifecycleWorkerMigration {

    /**
     * `false` ⇒ `DeviceLifecycleEventRecorder.recordAsync` runs the legacy inline
     * `recordNow` (current behaviour — the regression baseline). `true` ⇒ it runs the
     * new module path through [DeviceLifecycleCollectionModule] +
     * [com.openlattice.chronicle.collection.sink.LifecycleEventSink].
     *
     * Defaults to `false`: the new path is off until parity is proven.
     */
    public const val USE_MODULE_MANAGER_LIFECYCLE_PATH: Boolean = true
}
