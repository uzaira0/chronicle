package com.openlattice.chronicle.collection.settings

import com.openlattice.chronicle.collection.AndroidDataCollectionSetting
import com.openlattice.chronicle.collection.CollectionDefaults
import com.openlattice.chronicle.collection.CollectionModuleId
import com.openlattice.chronicle.collection.CollectionModuleSetting
import com.openlattice.chronicle.collection.CollectionPrivacyClass
import com.openlattice.chronicle.collection.SensorCollectionModules
import com.openlattice.chronicle.collection.core.CollectionLog

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

    /** Derived from the legacy `AndroidSensor` setting via `fromLegacy`. */
    LEGACY_BRIDGE,

    /** No setting available; safe coded [CollectionDefaults] used. */
    SAFE_DEFAULT,

    /** Inbound setting was malformed; module forced to a safe disabled default. */
    INVALID_DISABLED,
}

/**
 * Resolves an [AndroidDataCollectionSetting] per the design's fallback order and
 * validation rules (design §1B.4, §1C.3, refactor plan §6.3).
 *
 * Fallback order, per module:
 *   1. **Generalized** — an explicit entry in the supplied [AndroidDataCollectionSetting];
 *   2. **Legacy bridge** — [AndroidDataCollectionSetting.fromLegacy] applied to the
 *      legacy `AndroidSensor` setting (only ever populates `hardware_sensors`);
 *   3. **Safe default** — [CollectionDefaults], whose `enabled` is driven by the
 *      module's privacy class.
 *
 * Hard rules implemented here:
 *  - **Privacy-sensitive modules are never enabled implicitly.** `PHYSICAL_TELEMETRY`
 *    (`hardware_sensors`) and `LOCAL_PARTICIPANT_LABEL` (`user_identification`) default
 *    to `false`; only an explicit generalized entry, or a non-empty legacy
 *    `AndroidSensor` setting, may flip `hardware_sensors` on (design §1A.4).
 *  - **Invalid settings disable, never silently enable.** The chronicle-models DTOs
 *    validate in their `init`, so a malformed generalized setting throws *before* it
 *    reaches this resolver — meaning the caller passes `null`/legacy instead. If a
 *    setting nevertheless fails validation here (e.g. a future raw-primitive path),
 *    the module is logged and forced to a *disabled* safe default
 *    ([ResolutionSource.INVALID_DISABLED]).
 *  - **Reserved/inactive module IDs are never resolved as enabled** — they are not
 *    `CollectionModuleId.activeModules` and so are excluded entirely.
 *
 * This is a plain class holding only a [LegacySensorSettingSource] and a logger — no
 * Android `Context`. Phase 3 introduces it with no callsite switched.
 *
 */
public class CollectionSettingsResolver(
    private val legacySource: LegacySensorSettingSource,
    private val log: CollectionLog = CollectionLog.LOGCAT,
) {

    /**
     * Resolves every active module's setting.
     *
     * @param generalized the generalized `DataCollection` setting if the server
     *   supplied one, or `null` to fall through to the legacy/default tiers.
     * @return one [ResolvedModuleSetting] per [CollectionModuleId.activeModules] entry.
     */
    public fun resolveAll(generalized: AndroidDataCollectionSetting?): Map<CollectionModuleId, ResolvedModuleSetting> {
        val legacyBridge: AndroidDataCollectionSetting? = buildLegacyBridge()
        return CollectionModuleId.activeModules.associateWith { moduleId ->
            resolve(moduleId, generalized, legacyBridge)
        }
    }

    /**
     * Resolves a single active module's setting.
     *
     * @throws IllegalArgumentException if [moduleId] is a reserved/inactive id.
     */
    public fun resolve(
        moduleId: CollectionModuleId,
        generalized: AndroidDataCollectionSetting?,
    ): ResolvedModuleSetting {
        require(moduleId.active) {
            "Cannot resolve a setting for reserved/inactive module id '${moduleId.id}' (design §1A.3)."
        }
        return resolve(moduleId, generalized, buildLegacyBridge())
    }

    private fun resolve(
        moduleId: CollectionModuleId,
        generalized: AndroidDataCollectionSetting?,
        legacyBridge: AndroidDataCollectionSetting?,
    ): ResolvedModuleSetting {
        // Tier 1: explicit generalized entry.
        generalized?.modules?.get(moduleId)?.let { explicit ->
            return validateOrDisable(moduleId, explicit, ResolutionSource.GENERALIZED)
        }
        // Tier 2: legacy AndroidSensor bridge (only ever populates per-sensor modules) — but
        // never for a sensor the study has already spoken to via the per-sensor model. Once the
        // generalized config carries ANY per-sensor entry it is authoritative for EVERY sensor:
        // a sensor it omits is an explicit "not in this study", so the device-wide legacy bridge
        // must not silently re-enable it. Without this guard a researcher removing a sensor
        // mid-study would be a no-op on already-enrolled devices (the legacy AndroidSensor
        // settings persisted at enrollment keep the omitted sensors alive). Legacy (un-migrated)
        // studies send no per-sensor entry, so the bridge still applies for them.
        if (!(SensorCollectionModules.isSensorModule(moduleId) && hasPerSensorEntry(generalized))) {
            legacyBridge?.modules?.get(moduleId)?.let { bridged ->
                return validateOrDisable(moduleId, bridged, ResolutionSource.LEGACY_BRIDGE)
            }
        }
        // Tier 3: safe coded default — privacy-class driven enablement.
        val default = CollectionDefaults.moduleSetting(moduleId)
        log.info(
            TAG,
            "Module '${moduleId.id}' resolved to safe default (enabled=${default.enabled}, " +
                "privacyClass=${moduleId.privacyClass})",
        )
        return ResolvedModuleSetting(moduleId, default, ResolutionSource.SAFE_DEFAULT, valid = true)
    }

    /**
     * Validates an inbound module setting. The chronicle-models DTOs already validate
     * cadence, battery/network policy, sampling rate and duty cycle in their `init`, so
     * a setting that reached this method is structurally sound; this method enforces the
     * remaining *resolver-level* invariants and, on any failure, forces a safe disabled
     * default rather than propagating an enabled-but-invalid setting.
     */
    private fun validateOrDisable(
        moduleId: CollectionModuleId,
        setting: CollectionModuleSetting,
        source: ResolutionSource,
    ): ResolvedModuleSetting {
        val violation = findViolation(moduleId, setting)
        if (violation != null) {
            log.warn(
                TAG,
                "Invalid setting for module '${moduleId.id}' from $source ($violation); " +
                    "disabling module — invalid settings never silently enable a module.",
            )
            val disabled = CollectionDefaults.moduleSetting(moduleId, enabled = false)
            return ResolvedModuleSetting(moduleId, disabled, ResolutionSource.INVALID_DISABLED, valid = false)
        }
        if (setting.enabled && isPrivacySensitive(moduleId)) {
            // An explicit opt-in is allowed; log it so the audit trail shows the flip.
            log.info(
                TAG,
                "Privacy-sensitive module '${moduleId.id}' (${moduleId.privacyClass}) " +
                    "explicitly enabled from $source.",
            )
        }
        return ResolvedModuleSetting(moduleId, setting, source, valid = true)
    }

    /**
     * Defence-in-depth validation. Returns a human-readable violation string, or `null`
     * if the setting is acceptable. The DTO `init` blocks would already have thrown for
     * most of these; this re-checks at the resolver boundary so a future raw/primitive
     * construction path cannot bypass the rule.
     */
    private fun findViolation(moduleId: CollectionModuleId, setting: CollectionModuleSetting): String? {
        if (setting.collectionCadence.intervalSeconds <= 0L) {
            return "collectionCadence.intervalSeconds must be positive"
        }
        if (setting.uploadCadence.intervalSeconds <= 0L) {
            return "uploadCadence.intervalSeconds must be positive"
        }
        if (setting.collectionCadence.jitterSeconds < 0L ||
            setting.collectionCadence.jitterSeconds > setting.collectionCadence.intervalSeconds
        ) {
            return "collectionCadence.jitterSeconds out of range"
        }
        if (setting.uploadCadence.jitterSeconds < 0L ||
            setting.uploadCadence.jitterSeconds > setting.uploadCadence.intervalSeconds
        ) {
            return "uploadCadence.jitterSeconds out of range"
        }
        val battery = setting.batteryPolicy
        if (battery.minLevelPercent !in 0..100 || battery.stopBelowCriticalPercent !in 0..100) {
            return "batteryPolicy percentage out of range"
        }
        if (battery.stopBelowCriticalPercent > battery.minLevelPercent) {
            return "batteryPolicy.stopBelowCriticalPercent exceeds minLevelPercent"
        }
        val sensorPolicy = setting.sensorPolicy
        if (sensorPolicy != null) {
            if (!SensorCollectionModules.isSensorModule(moduleId)) {
                return "sensorPolicy is only valid on a per-sensor module"
            }
            if (sensorPolicy.samplingRateHz !in 1..CollectionModuleSetting.MAX_SENSOR_SAMPLING_RATE_HZ) {
                return "sensorPolicy.samplingRateHz must be between 1 and " +
                    CollectionModuleSetting.MAX_SENSOR_SAMPLING_RATE_HZ
            }
            if (sensorPolicy.dutyCyclePeriodSeconds <= 0) {
                return "sensorPolicy.dutyCyclePeriodSeconds must be positive"
            }
            if (sensorPolicy.dutyCycleActiveSeconds < 0 ||
                sensorPolicy.dutyCycleActiveSeconds > sensorPolicy.dutyCyclePeriodSeconds
            ) {
                return "sensorPolicy duty cycle out of range"
            }
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

    /**
     * Whether the generalized config has adopted the per-sensor model — i.e. it carries at
     * least one explicit per-sensor (`sensor_*`) module entry. When true, that config is the
     * sole authority on which sensors collect, and the legacy device-wide `AndroidSensor`
     * bridge is suppressed for sensor modules (a sensor the config omits stays off).
     */
    private fun hasPerSensorEntry(generalized: AndroidDataCollectionSetting?): Boolean =
        generalized?.hasAnySensorModule() == true

    /**
     * Builds the legacy-bridge tier from the legacy `AndroidSensor` setting.
     *
     * [AndroidSensorSetting] has no `init` validation, so a corrupt value read from
     * encrypted prefs (e.g. `dutyCyclePeriodSeconds = 0`) constructs fine but makes
     * [AndroidDataCollectionSetting.fromLegacy] throw when it wraps the policy in a
     * validating [CollectionModuleSetting]. That throw must not escape and crash the
     * resolution of *every* module — a malformed legacy setting disables only the
     * affected module (design §1B.4). The bridge is therefore dropped and the resolver
     * falls through to the safe disabled default for `hardware_sensors`.
     */
    private fun buildLegacyBridge(): AndroidDataCollectionSetting? {
        val legacy = legacySource.read() ?: return null
        return try {
            AndroidDataCollectionSetting.fromLegacy(legacy)
        } catch (e: IllegalArgumentException) {
            log.warn(
                TAG,
                "Legacy AndroidSensor setting is malformed; ignoring the legacy bridge — " +
                    "hardware_sensors falls through to its safe disabled default.",
                e,
            )
            null
        }
    }
}
