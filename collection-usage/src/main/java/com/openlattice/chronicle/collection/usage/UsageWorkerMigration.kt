package com.openlattice.chronicle.collection.usage

/**
 * Internal migration switch for the usage-events worker (Phase 4, subphase 4B,
 * refactor plan §7.2 step 8 / design §1C.4).
 *
 * Phase 4B introduces a second usage-collection path inside `UsageMonitoringWorker`: the
 * module-manager path, which routes a [UsageEventsCollectionModule] poll through the
 * sanctioned [com.openlattice.chronicle.collection.sink.UsageEventSink]. While both the
 * legacy path ([com.openlattice.chronicle.services.usage.UsageCollectionDelegate]) and
 * the new path coexist, exactly one of them runs per worker execution — the worker
 * branches on [USE_MODULE_MANAGER_USAGE_PATH]. There is no third path and no
 * double-enqueue / double-write.
 *
 * **Default is the current behaviour.** [USE_MODULE_MANAGER_USAGE_PATH] is `false`: the
 * worker keeps invoking the legacy `UsageCollectionDelegate` exactly as before. The new
 * module path stays dormant in production until its parity tests pass; flipping the flag
 * to `true` is a deliberate, separately-reviewed step (refactor plan §7.2 acceptance).
 *
 * This is a compile-time constant, not a server/remote setting — it gates the migration
 * during development only and carries no privacy or wire-shape implication.
 *
 */
public object UsageWorkerMigration {

    /**
     * `false` ⇒ `UsageMonitoringWorker` runs the legacy `UsageCollectionDelegate`
     * (current behaviour — the regression baseline). `true` ⇒ it runs the new
     * module-manager path through `UsageEventsCollectionModule` + `UsageEventSink`.
     *
     * Defaults to `false`: the new path is off until parity is proven.
     */
    public const val USE_MODULE_MANAGER_USAGE_PATH: Boolean = true
}
