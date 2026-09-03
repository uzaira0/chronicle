package com.openlattice.chronicle.collection.settings

import com.openlattice.chronicle.android.AndroidSensorSetting
import com.openlattice.chronicle.android.AndroidSensorType
import com.openlattice.chronicle.collection.AndroidDataCollectionSetting
import com.openlattice.chronicle.collection.CollectionDefaults
import com.openlattice.chronicle.collection.CollectionModuleId
import com.openlattice.chronicle.collection.CollectionModuleSetting
import com.openlattice.chronicle.collection.SensorCollectionModules
import com.openlattice.chronicle.collection.core.NoOpCollectionLog
import com.openlattice.chronicle.collection.core.RecordingCollectionLog
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/** Fake [LegacySensorSettingSource] returning a fixed legacy setting (or null). */
private class FakeLegacySource(private val setting: AndroidSensorSetting?) : LegacySensorSettingSource {
    override fun read(): AndroidSensorSetting? = setting
}

class CollectionSettingsResolverTest {

    private fun resolver(
        legacy: AndroidSensorSetting? = null,
        log: com.openlattice.chronicle.collection.core.CollectionLog = NoOpCollectionLog,
    ) = CollectionSettingsResolver(FakeLegacySource(legacy), log)

    // ---- Tier 3: safe coded defaults -------------------------------------------------

    @Test
    fun noSettingResolvesEveryActiveModuleToSafeDefault() {
        val resolved = resolver().resolveAll(generalized = null)

        assertEquals(CollectionModuleId.activeModules, resolved.keys)
        resolved.values.forEach { entry ->
            assertEquals(ResolutionSource.SAFE_DEFAULT, entry.source)
            assertTrue(entry.valid)
        }
    }

    @Test
    fun privacySensitiveModulesAreNotEnabledImplicitlyByDefault() {
        val resolved = resolver().resolveAll(generalized = null)

        // PHYSICAL_TELEMETRY (every per-sensor module) and LOCAL_PARTICIPANT_LABEL default to disabled.
        assertFalse(resolved[CollectionModuleId.SENSOR_ACCELEROMETER]!!.enabled)
        assertFalse(resolved[CollectionModuleId.USER_IDENTIFICATION]!!.enabled)
        // Non-sensitive operational/metadata modules default enabled.
        assertTrue(resolved[CollectionModuleId.USAGE_EVENTS]!!.enabled)
        assertTrue(resolved[CollectionModuleId.DEVICE_LIFECYCLE]!!.enabled)
        assertTrue(resolved[CollectionModuleId.UPLOAD_TELEMETRY]!!.enabled)
        assertTrue(resolved[CollectionModuleId.SENSOR_AVAILABILITY]!!.enabled)
    }

    // ---- Tier 2: legacy AndroidSensor bridge -----------------------------------------

    @Test
    fun emptyLegacySensorSettingLeavesSensorsDisabled() {
        val resolved = resolver(legacy = AndroidSensorSetting.NO_SENSORS).resolveAll(generalized = null)
        // An empty legacy setting yields null from the source; falls through to defaults.
        assertFalse(resolved[CollectionModuleId.SENSOR_ACCELEROMETER]!!.enabled)
    }

    @Test
    fun nonEmptyLegacySensorSettingEnablesOnlyTheNamedSensorModuleViaBridge() {
        val legacy = AndroidSensorSetting(
            sensors = setOf(AndroidSensorType.accelerometer),
            samplingRateHz = 5,
            dutyCycleActiveSeconds = 30,
            dutyCyclePeriodSeconds = 300,
        )
        val resolved = resolver(legacy = legacy).resolveAll(generalized = null)

        val accelerometer = resolved[CollectionModuleId.SENSOR_ACCELEROMETER]!!
        assertTrue(accelerometer.enabled)
        assertEquals(ResolutionSource.LEGACY_BRIDGE, accelerometer.source)
        assertEquals(setOf(AndroidSensorType.accelerometer), accelerometer.setting.sensorPolicy?.sensors)
        assertEquals(5, accelerometer.setting.sensorPolicy?.samplingRateHz)

        // A sensor the legacy setting did not name is not enabled by the bridge.
        assertFalse(resolved[CollectionModuleId.SENSOR_GYROSCOPE]!!.enabled)
        // No other module is enabled implicitly by the legacy bridge.
        assertFalse(resolved[CollectionModuleId.USER_IDENTIFICATION]!!.enabled)
        // Non-sensor modules still come from safe defaults, not the bridge.
        assertEquals(ResolutionSource.SAFE_DEFAULT, resolved[CollectionModuleId.USAGE_EVENTS]!!.source)
    }

    @Test
    fun perSensorGeneralizedConfigSuppressesLegacyBridgeForOmittedSensors() {
        // The study has migrated to the per-sensor model: it explicitly lists ONLY the
        // accelerometer. The device still carries a legacy AndroidSensor setting that also
        // names the gyroscope (persisted at a prior enrollment). A per-sensor config is
        // authoritative for EVERY sensor, so the omitted gyroscope must NOT be re-enabled
        // by the legacy bridge — otherwise a researcher removing a sensor mid-study would be
        // a no-op on already-enrolled devices. It must fall to the disabled safe default.
        val legacy = AndroidSensorSetting(
            sensors = setOf(AndroidSensorType.accelerometer, AndroidSensorType.gyroscope),
            samplingRateHz = 5,
            dutyCycleActiveSeconds = 30,
            dutyCyclePeriodSeconds = 300,
        )
        val generalized = AndroidDataCollectionSetting(
            modules = mapOf(
                CollectionModuleId.SENSOR_ACCELEROMETER to CollectionDefaults.moduleSetting(
                    CollectionModuleId.SENSOR_ACCELEROMETER, enabled = true,
                ),
            ),
        )
        val resolved = resolver(legacy = legacy).resolveAll(generalized = generalized)

        val accelerometer = resolved[CollectionModuleId.SENSOR_ACCELEROMETER]!!
        assertTrue(accelerometer.enabled)
        assertEquals(ResolutionSource.GENERALIZED, accelerometer.source)

        val gyroscope = resolved[CollectionModuleId.SENSOR_GYROSCOPE]!!
        assertFalse(
            "a per-sensor config that omits a sensor means OFF — the legacy bridge must not re-enable it",
            gyroscope.enabled,
        )
        assertEquals(ResolutionSource.SAFE_DEFAULT, gyroscope.source)
    }

    @Test
    fun perSensorConfigIsAuthoritativeForEverySensorEvenAgainstAFullLegacyBridge() {
        // Worst case: a legacy AndroidSensor blob naming EVERY sensor type (e.g. the
        // device-wide default persisted at a prior enrollment), and a per-sensor study that
        // enables only a subset. Every sensor the per-sensor config omits MUST resolve disabled
        // from SAFE_DEFAULT — the legacy bridge may never re-enable an omitted sensor once the
        // config is per-sensor. This is the master guard for the mid-study sensor-removal bug.
        val legacy = AndroidSensorSetting(
            sensors = AndroidSensorType.entries.toSet(),
            samplingRateHz = 5,
            dutyCycleActiveSeconds = 30,
            dutyCyclePeriodSeconds = 300,
        )
        val kept = setOf(CollectionModuleId.SENSOR_ACCELEROMETER, CollectionModuleId.SENSOR_LIGHT)
        val generalized = AndroidDataCollectionSetting(
            modules = kept.associateWith { CollectionDefaults.moduleSetting(it, enabled = true) },
        )
        val resolved = resolver(legacy = legacy).resolveAll(generalized = generalized)

        SensorCollectionModules.sensorModuleIds.forEach { sensorId ->
            val r = resolved[sensorId]!!
            if (sensorId in kept) {
                assertTrue("kept sensor '$sensorId' must stay enabled from the per-sensor config", r.enabled)
                assertEquals(ResolutionSource.GENERALIZED, r.source)
            } else {
                assertFalse("omitted sensor '$sensorId' must NOT be re-enabled by the legacy bridge", r.enabled)
                assertEquals(ResolutionSource.SAFE_DEFAULT, r.source)
            }
        }
    }

    @Test
    fun legacyBridgeStillAppliesWhenGeneralizedHasNoPerSensorEntries() {
        // A study that has NOT migrated to the per-sensor model sends only non-sensor module
        // entries (or null). The device-wide legacy AndroidSensor bridge must still enable the
        // sensors it names — the per-sensor authoritative rule only kicks in once the config
        // itself carries a per-sensor entry.
        val legacy = AndroidSensorSetting(
            sensors = setOf(AndroidSensorType.accelerometer),
            samplingRateHz = 5,
            dutyCycleActiveSeconds = 30,
            dutyCyclePeriodSeconds = 300,
        )
        val generalized = AndroidDataCollectionSetting(
            modules = mapOf(
                CollectionModuleId.USAGE_EVENTS to CollectionDefaults.moduleSetting(
                    CollectionModuleId.USAGE_EVENTS, enabled = true,
                ),
            ),
        )
        val resolved = resolver(legacy = legacy).resolveAll(generalized = generalized)

        val accelerometer = resolved[CollectionModuleId.SENSOR_ACCELEROMETER]!!
        assertTrue(accelerometer.enabled)
        assertEquals(ResolutionSource.LEGACY_BRIDGE, accelerometer.source)
    }

    // ---- Tier 1: generalized setting -------------------------------------------------

    @Test
    fun generalizedSettingTakesPrecedenceOverLegacyAndDefaults() {
        val legacy = AndroidSensorSetting(sensors = setOf(AndroidSensorType.gyroscope))
        val generalized = AndroidDataCollectionSetting(
            modules = mapOf(
                CollectionModuleId.USAGE_EVENTS to CollectionDefaults.moduleSetting(
                    CollectionModuleId.USAGE_EVENTS, enabled = false,
                ),
            ),
        )
        val resolved = resolver(legacy = legacy).resolve(CollectionModuleId.USAGE_EVENTS, generalized)

        assertEquals(ResolutionSource.GENERALIZED, resolved.source)
        assertFalse("explicit generalized entry wins", resolved.enabled)
    }

    @Test
    fun privacySensitiveModuleMayBeEnabledByExplicitGeneralizedOptIn() {
        // The rule is "never enabled IMPLICITLY" — an explicit opt-in is allowed.
        val generalized = AndroidDataCollectionSetting(
            modules = mapOf(
                CollectionModuleId.SENSOR_ACCELEROMETER to CollectionDefaults.moduleSetting(
                    CollectionModuleId.SENSOR_ACCELEROMETER, enabled = true,
                ),
            ),
        )
        val resolved = resolver().resolve(CollectionModuleId.SENSOR_ACCELEROMETER, generalized)

        assertTrue(resolved.enabled)
        assertEquals(ResolutionSource.GENERALIZED, resolved.source)
    }

    @Test
    fun explicitPrivacySensitiveOptInIsLogged() {
        val log = RecordingCollectionLog()
        val generalized = AndroidDataCollectionSetting(
            modules = mapOf(
                CollectionModuleId.USER_IDENTIFICATION to CollectionDefaults.moduleSetting(
                    CollectionModuleId.USER_IDENTIFICATION, enabled = true,
                ),
            ),
        )
        resolver(log = log).resolve(CollectionModuleId.USER_IDENTIFICATION, generalized)
        assertTrue(log.entries.any { it.message.contains("explicitly enabled") })
    }

    // ---- Malformed settings ----------------------------------------------------------

    @Test
    fun malformedSensorPolicyOnWrongModuleIsDisabledNotEnabled() {
        // sensorPolicy is only valid on hardware_sensors; placing it on usage_events is
        // a violation — the resolver must disable, not silently propagate enabled.
        val badSetting = CollectionModuleSetting(
            enabled = true,
            sensorPolicy = AndroidSensorSetting(sensors = setOf(AndroidSensorType.light)),
        )
        val generalized = AndroidDataCollectionSetting(
            modules = mapOf(CollectionModuleId.USAGE_EVENTS to badSetting),
        )
        val log = RecordingCollectionLog()
        val resolved = resolver(log = log).resolve(CollectionModuleId.USAGE_EVENTS, generalized)

        assertFalse("invalid setting must never silently enable", resolved.enabled)
        assertEquals(ResolutionSource.INVALID_DISABLED, resolved.source)
        assertFalse(resolved.valid)
        assertTrue("the violation must be logged", log.problems.isNotEmpty())
    }

    @Test
    fun malformedLegacySettingDoesNotCrashResolverAndDisablesSensors() {
        // AndroidSensorSetting has no init validation, so a corrupt dutyCyclePeriodSeconds
        // constructs fine; AndroidDataCollectionSetting.fromLegacy throws when wrapping it.
        // That throw must NOT escape resolveAll and crash every module's resolution.
        val corruptLegacy = AndroidSensorSetting(
            sensors = setOf(AndroidSensorType.accelerometer),
            samplingRateHz = 5,
            dutyCycleActiveSeconds = 30,
            dutyCyclePeriodSeconds = 0, // invalid: must be positive
        )
        val log = RecordingCollectionLog()
        val resolved = resolver(legacy = corruptLegacy, log = log).resolveAll(generalized = null)

        // Resolver survived; the sensor fell through to a safe disabled default.
        assertEquals(CollectionModuleId.activeModules, resolved.keys)
        val accelerometer = resolved[CollectionModuleId.SENSOR_ACCELEROMETER]!!
        assertFalse("malformed legacy setting must never enable a sensor", accelerometer.enabled)
        assertEquals(ResolutionSource.SAFE_DEFAULT, accelerometer.source)
        // Other modules resolved normally despite the malformed legacy bridge.
        assertTrue(resolved[CollectionModuleId.USAGE_EVENTS]!!.enabled)
        assertTrue("the malformed legacy bridge must be logged", log.problems.isNotEmpty())
    }

    @Test
    fun resolveRejectsReservedInactiveModuleId() {
        for (reserved in CollectionModuleId.entries.filter { !it.active }) {
            try {
                resolver().resolve(reserved, generalized = null)
                fail("resolve must reject reserved/inactive id '${reserved.id}'")
            } catch (e: IllegalArgumentException) {
                assertTrue(e.message!!.contains("reserved"))
            }
        }
    }

    @Test
    fun resolveAllNeverIncludesReservedModules() {
        val resolved = resolver().resolveAll(generalized = null)
        CollectionModuleId.entries.filter { !it.active }.forEach { reserved ->
            assertFalse(resolved.containsKey(reserved))
        }
    }
}
