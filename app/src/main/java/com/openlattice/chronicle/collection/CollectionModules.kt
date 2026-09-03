package com.openlattice.chronicle.collection

import android.content.Context
import android.util.Log
import com.openlattice.chronicle.BuildConfig
import com.openlattice.chronicle.collection.battery.BatteryTelemetryModuleHolder
import com.openlattice.chronicle.collection.core.CollectionModuleRegistry
import com.openlattice.chronicle.collection.device.ConnectivityStateModuleHolder
import com.openlattice.chronicle.collection.device.DeviceSettingsModuleHolder
import com.openlattice.chronicle.collection.core.DataCollectionModule
import com.openlattice.chronicle.collection.identification.UserIdentificationModuleHolder
import com.openlattice.chronicle.collection.lifecycle.DeviceLifecycleModuleHolder
import com.openlattice.chronicle.collection.notifications.QuestionnaireModuleHolder
import com.openlattice.chronicle.collection.upload.UploadTelemetryModuleHolder

/**
 * Process-wide runtime [CollectionModuleRegistry] over the active, holder-backed collection
 * modules (design §1C.3) — the registry's runtime realization.
 *
 * Phase 3 introduced [CollectionModuleRegistry] with no runtime callsite: production wired
 * each module per-worker behind its own migration flag and nothing drove
 * [CollectionModuleRegistry.all] at runtime. This object closes that gap. It registers the
 * holder-backed [DataCollectionModule] singletons into one registry that read-only
 * consumers (e.g. the sync-run module-health line in `ChronicleSyncWorker`) query. It does
 * **not** change how workers resolve modules — they keep using the per-module holders, and
 * this registry is a faithful view over the *same* singleton instances, so it carries no
 * behavioural risk to the live collection paths.
 *
 * **Some active ids are intentionally not registry-managed**, because they are not
 * holder-backed [DataCollectionModule] singletons — this is architecture, not a gap:
 *  - [CollectionModuleId.USAGE_EVENTS] is built per-collection inside
 *    `UsageModuleCollectionDelegate` and carries run-scoped poll-cursor state; making it a
 *    process singleton would change its lifecycle, so it stays delegate-owned.
 *  - [CollectionModuleId.SENSOR_AVAILABILITY] is realized by `SensorAvailabilityReporter`,
 *    a reporter rather than a `DataCollectionModule`, so it has no module class to register.
 *  - the per-sensor `sensor_*` modules are realized by the one shared `HardwareSensorService`
 *    runtime (it collects every gated-open sensor), not by 14 `DataCollectionModule`
 *    singletons, so they are service-realized like `usage_events` rather than registered here.
 *
 * **Lazy + crash-safe.** The registry builds on first use (synchronized) on whatever
 * background thread first asks — never on the app-startup main thread, so there is no ANR
 * or startup-crash surface. Each module is registered in isolation: a module that fails to
 * build or violates a registry guardrail is logged and skipped, never propagated. Building
 * the registry can therefore never crash a worker; the per-module holders remain the
 * authoritative construction path.
 *
 */
public object CollectionModules {

    private const val TAG = "CollectionModules"

    @Volatile private var registry: CollectionModuleRegistry? = null

    /**
     * The holder-backed module suppliers, keyed by id. [CollectionModuleRegistry.register]
     * validates that each built module's own [DataCollectionModule.id] matches this key, is
     * active, and has a matching privacy class — so this map is the single source of truth
     * for "what is registry-managed".
     */
    private val moduleSuppliers: Map<CollectionModuleId, (Context) -> DataCollectionModule> =
        buildMap {
            putAll(linkedMapOf(
            CollectionModuleId.BATTERY_TELEMETRY to { ctx: Context -> BatteryTelemetryModuleHolder.get(ctx) },
            CollectionModuleId.DEVICE_LIFECYCLE to { ctx: Context -> DeviceLifecycleModuleHolder.get(ctx) },
            CollectionModuleId.UPLOAD_TELEMETRY to { ctx: Context -> UploadTelemetryModuleHolder.get(ctx) },
            CollectionModuleId.USER_IDENTIFICATION to { ctx: Context -> UserIdentificationModuleHolder.get(ctx) },
            CollectionModuleId.CONNECTIVITY_STATE to { ctx: Context -> ConnectivityStateModuleHolder.get(ctx) },
            CollectionModuleId.DEVICE_SETTINGS to { ctx: Context -> DeviceSettingsModuleHolder.get(ctx) },
            ))
            if (BuildConfig.ALLOW_PARTICIPANT_FORM_REMINDERS) {
                put(CollectionModuleId.QUESTIONNAIRE) { ctx: Context -> QuestionnaireModuleHolder.get(ctx) }
            }
            putAll(DistributionCollectionContributions.moduleSuppliers)
        }

    /** The ids this object will attempt to register — every one active and holder-backed. */
    public val MANAGED_MODULE_IDS: Set<CollectionModuleId> get() = moduleSuppliers.keys

    /**
     * Returns the process-wide registry, building it on first use. Synchronized and
     * crash-safe: a module that fails to register is logged and skipped, never thrown.
     */
    public fun registry(context: Context): CollectionModuleRegistry {
        registry?.let { return it }
        val appContext = context.applicationContext
        return synchronized(this) {
            registry ?: build(appContext).also { registry = it }
        }
    }

    private fun build(appContext: Context): CollectionModuleRegistry {
        val built = CollectionModuleRegistry()
        moduleSuppliers.forEach { (moduleId, supplier) ->
            try {
                built.register(supplier(appContext))
            } catch (e: Exception) {
                // One bad module never blocks the rest or the caller; holders stay authoritative.
                Log.e(TAG, "Skipping collection module '${moduleId.id}' — registration failed", e)
            }
        }
        Log.i(TAG, "Collection module registry built: ${built.registeredIds.map { it.id }}")
        return built
    }

    /**
     * Read-only one-line health summary of the registered modules (`id=status`), for the
     * runtime module-health consumer. [com.openlattice.chronicle.collection.core.CollectionModuleStatus]
     * is a non-sensitive lifecycle enum; no module label, participant ref, or diagnostics
     * payload is included (design §1B.3). Never throws.
     */
    public fun moduleHealthSummary(context: Context): String =
        try {
            registry(context).all()
                .joinToString(", ") { "${it.id.id}=${it.status()}" }
                .ifEmpty { "(no modules registered)" }
        } catch (e: Exception) {
            Log.e(TAG, "Module health summary failed", e)
            "(module health unavailable)"
        }
}
