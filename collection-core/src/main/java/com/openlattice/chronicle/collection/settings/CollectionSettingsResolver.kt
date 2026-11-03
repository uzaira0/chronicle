package com.openlattice.chronicle.collection.settings

import android.util.Log
import com.openlattice.chronicle.collection.AndroidDataCollectionSetting
import com.openlattice.chronicle.collection.CollectionDefaults
import com.openlattice.chronicle.collection.CollectionModuleId
import com.openlattice.chronicle.collection.CollectionModuleSetting
import com.openlattice.chronicle.collection.CollectionPrivacyClass
import com.openlattice.chronicle.collection.SensorCollectionModules

private const val TAG = "CollectionSettingsResolver"

/**
 * Resolved, validated configuration for one collection module.
 *
 * The [setting] is always a valid [CollectionModuleSetting]; [source] records which tier
 * of the fallback order (design §1B.4) it came from, and [valid] is `false` when the
 * inbound setting was malformed and the module was forced to a safe disabled default.
 */
public data class ResolvedModuleSetting(
    val moduleId: CollectionModuleId,
    val setting: CollectionModuleSetting,
    val source: ResolutionSource,
    val valid: Boolean,
) {
    /** Convenience: whether the resolved module is enabled. */
    public val enabled: Boolean get() = setting.enabled

    /** Convenience: whether the study marks the resolved module as required (consent design §4.2). */
    public val required: Boolean get() = setting.required
}

/** Which tier of the fallback order (design §1B.4) produced a [ResolvedModuleSetting]. */
public enum class ResolutionSource {
    /** The generalized `DataCollection` setting supplied an explicit entry. */
    GENERALIZED,

    /** No setting available; safe coded [CollectionDefaults] used. */
    SAFE_DEFAULT,

    /** Inbound setting was malformed; module forced to a safe disabled default. */
    INVALID_DISABLED,
}

/** Resolves explicit generalized module policies, disabling invalid settings and using safe defaults. */
public class CollectionSettingsResolver {

    /**
     * Resolves every active module's setting.
     *
     * @param generalized the generalized `DataCollection` setting if the server
     *   supplied one, or `null` to fall through to the legacy/default tiers.
     * @return one [ResolvedModuleSetting] per [CollectionModuleId.activeModules] entry.
     */
    public fun resolveAll(generalized: AndroidDataCollectionSetting?): Map<CollectionModuleId, ResolvedModuleSetting> {
        return CollectionModuleId.activeModules.associateWith { moduleId -> resolve(moduleId, generalized) }
    }

    public fun resolve(
        moduleId: CollectionModuleId,
        generalized: AndroidDataCollectionSetting?,
    ): ResolvedModuleSetting {
        require(moduleId.active) { "Cannot resolve a setting for reserved/inactive module id '${moduleId.id}'" }
        generalized?.modules?.get(moduleId)?.let { explicit ->
            return validateOrDisable(moduleId, explicit, ResolutionSource.GENERALIZED)
        }
        val default = CollectionDefaults.moduleSetting(moduleId)
        return ResolvedModuleSetting(moduleId, default, ResolutionSource.SAFE_DEFAULT, valid = true)
    }

    /**
     * Validates an inbound module setting. The chronicle-models DTOs validate cadence,
     * battery/network policy, sampling rate and duty cycle in their `init`, so a setting
     * that reached this method is structurally sound; this method enforces the
     * module-scoped invariants and, on any failure, forces a safe disabled
     * default rather than propagating an enabled-but-invalid setting.
     */
    private fun validateOrDisable(
        moduleId: CollectionModuleId,
        setting: CollectionModuleSetting,
        source: ResolutionSource,
    ): ResolvedModuleSetting {
        val violation = findViolation(moduleId, setting)
        if (violation != null) {
            Log.w(
                TAG,
                "Invalid setting for module '${moduleId.id}' from $source ($violation); " +
                    "disabling module — invalid settings never silently enable a module.",
            )
            val disabled = CollectionDefaults.moduleSetting(moduleId, enabled = false)
            return ResolvedModuleSetting(moduleId, disabled, ResolutionSource.INVALID_DISABLED, valid = false)
        }
        if (setting.enabled && isPrivacySensitive(moduleId)) {
            // An explicit opt-in is allowed; log it so the audit trail shows the flip.
            Log.i(
                TAG,
                "Privacy-sensitive module '${moduleId.id}' (${moduleId.privacyClass}) " +
                    "explicitly enabled from $source.",
            )
        }
        return ResolvedModuleSetting(moduleId, setting, source, valid = true)
    }

    /**
     * Module-scoped rules the chronicle-models DTOs cannot see (they do not know which
     * module a setting is attached to). Cadence, battery and sensor-policy ranges are
     * owned by the DTO `init` blocks. Returns a violation string, or `null`.
     */
    private fun findViolation(moduleId: CollectionModuleId, setting: CollectionModuleSetting): String? {
        if (setting.sensorPolicy != null && !SensorCollectionModules.isSensorModule(moduleId)) {
            return "sensorPolicy is only valid on a per-sensor module"
        }
        if (
            moduleId != CollectionModuleId.HEALTH_CONNECT &&
            setting.healthConnectRecordTypes.isNotEmpty()
        ) {
            return "healthConnectRecordTypes is only valid on the health_connect module"
        }
        if (
            moduleId == CollectionModuleId.HEALTH_CONNECT &&
            setting.enabled &&
            setting.healthConnectRecordTypes.isEmpty()
        ) {
            return "enabled health_connect requires at least one approved record type"
        }
        return null
    }

    private fun isPrivacySensitive(moduleId: CollectionModuleId): Boolean =
        moduleId.privacyClass == CollectionPrivacyClass.PHYSICAL_TELEMETRY ||
            moduleId.privacyClass == CollectionPrivacyClass.LOCAL_PARTICIPANT_LABEL

}
