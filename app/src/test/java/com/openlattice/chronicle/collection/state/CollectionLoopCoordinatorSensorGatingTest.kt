package com.openlattice.chronicle.collection.state

import com.openlattice.chronicle.collection.CollectionModuleId
import com.openlattice.chronicle.collection.CollectionModuleSetting
import com.openlattice.chronicle.collection.AndroidDataCollectionSetting
import com.openlattice.chronicle.collection.SensorCollectionModules
import com.openlattice.chronicle.collection.settings.ResolutionSource
import com.openlattice.chronicle.collection.settings.ResolvedModuleSetting
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * JVM coverage for [CollectionLoopCoordinator.retainCollectableSensors] — the hardware-aware
 * gate that drops per-sensor modules the device physically lacks before the resolved settings
 * reach [CollectionStateMachine.reconcile]. This is what makes the enrollment walkthrough, the
 * post-enrollment sync, and decision seeding all skip a study-enabled sensor a device doesn't
 * have (e.g. a Samsung-only sensor on a Pixel), so the participant is never asked to consent to
 * a sensor that can never produce data.
 */
class CollectionLoopCoordinatorSensorGatingTest {

    private fun resolved(moduleId: CollectionModuleId) = ResolvedModuleSetting(
        moduleId = moduleId,
        setting = CollectionModuleSetting(enabled = true),
        source = ResolutionSource.GENERALIZED,
        valid = true,
    )

    private fun resolvedMap(vararg ids: CollectionModuleId) =
        ids.associateWith { resolved(it) }

    private fun authoritativeSetting(
        vararg overrides: Pair<CollectionModuleId, CollectionModuleSetting>,
        settingsVersion: Int = 1,
    ): AndroidDataCollectionSetting = AndroidDataCollectionSetting(
        modules = CollectionModuleId.activeModules.associateWith {
            CollectionModuleSetting(enabled = false)
        } + overrides.toMap(),
        settingsVersion = settingsVersion,
    )

    @Test
    fun dropsSensorModulesAbsentFromTheDevice() {
        val map = resolvedMap(
            CollectionModuleId.USAGE_EVENTS,
            CollectionModuleId.SENSOR_ACCELEROMETER,
            CollectionModuleId.SENSOR_SAMSUNG_GRIP_WIFI,
        )
        // The device has the accelerometer but not the Samsung grip sensor.
        val available = setOf(CollectionModuleId.SENSOR_ACCELEROMETER)

        val gated = CollectionLoopCoordinator.retainCollectableSensors(map, available)

        assertTrue("device-present sensor kept", gated.containsKey(CollectionModuleId.SENSOR_ACCELEROMETER))
        assertFalse("device-absent sensor dropped", gated.containsKey(CollectionModuleId.SENSOR_SAMSUNG_GRIP_WIFI))
    }

    @Test
    fun leavesEveryNonSensorModuleUntouched() {
        val nonSensors = listOf(
            CollectionModuleId.USAGE_EVENTS,
            CollectionModuleId.DEVICE_LIFECYCLE,
            CollectionModuleId.USER_IDENTIFICATION,
            CollectionModuleId.BATTERY_TELEMETRY,
        )
        val map = nonSensors.associateWith { resolved(it) }

        // Even with NO sensors available, non-sensor modules pass through unchanged.
        val gated = CollectionLoopCoordinator.retainCollectableSensors(map, emptySet())

        assertEquals(map.keys, gated.keys)
    }

    @Test
    fun emptyAvailabilityDropsAllSensorModulesButKeepsTheRest() {
        val map = resolvedMap(
            CollectionModuleId.USAGE_EVENTS,
            CollectionModuleId.SENSOR_ACCELEROMETER,
            CollectionModuleId.SENSOR_LIGHT,
            CollectionModuleId.SENSOR_GYROSCOPE,
        )

        val gated = CollectionLoopCoordinator.retainCollectableSensors(map, emptySet())

        assertEquals(setOf(CollectionModuleId.USAGE_EVENTS), gated.keys)
    }

    @Test
    fun keepsEverySensorWhenTheWholeCatalogIsPresent() {
        val map = SensorCollectionModules.sensorModuleIds.associateWith { resolved(it) } +
            mapOf(CollectionModuleId.USAGE_EVENTS to resolved(CollectionModuleId.USAGE_EVENTS))

        val gated = CollectionLoopCoordinator.retainCollectableSensors(
            map,
            SensorCollectionModules.sensorModuleIds,
        )

        assertEquals(map.keys, gated.keys)
    }

    @Test
    fun enrollmentPartitionsEnabledModulesWithoutCallingAbsentHardwareDeclined() {
        val fetched = authoritativeSetting(
            CollectionModuleId.USAGE_EVENTS to CollectionModuleSetting(enabled = true, required = true),
            CollectionModuleId.SENSOR_ACCELEROMETER to
                CollectionModuleSetting(enabled = true, required = true),
            CollectionModuleId.SENSOR_GYROSCOPE to
                CollectionModuleSetting(enabled = true, required = true),
            CollectionModuleId.BATTERY_TELEMETRY to CollectionModuleSetting(enabled = true),
            CollectionModuleId.SENSOR_LIGHT to CollectionModuleSetting(enabled = false),
            settingsVersion = 17,
        )

        val partition = enrollmentModulePartition(
            fetched = fetched,
            availableSensorModules = setOf(CollectionModuleId.SENSOR_ACCELEROMETER),
            accepted = setOf(
                CollectionModuleId.USAGE_EVENTS,
                CollectionModuleId.SENSOR_ACCELEROMETER,
            ),
            declined = setOf(CollectionModuleId.BATTERY_TELEMETRY),
        )

        assertEquals(
            setOf(CollectionModuleId.SENSOR_GYROSCOPE),
            partition.unavailable,
        )
        assertFalse(partition.declined.contains(CollectionModuleId.SENSOR_GYROSCOPE))
        assertEquals(
            fetched.effectiveEnabledModuleIds(),
            partition.accepted + partition.declined + partition.unavailable,
        )
        assertEquals(17, partition.settingsVersion)
    }

    @Test
    fun enrollmentIncludesEnabledOperationalModulesCoveredByStudyDisclosure() {
        val fetched = AndroidDataCollectionSetting()
        val enabled = fetched.effectiveEnabledModuleIds()
        val participantControlled = enabled.intersect(CollectionStateMachine.ACK_GATED_MODULES)
        val operational = enabled - CollectionStateMachine.ACK_GATED_MODULES

        val partition = enrollmentModulePartition(
            fetched = fetched,
            availableSensorModules = emptySet(),
            accepted = participantControlled,
            declined = emptySet(),
        )

        assertTrue("default manifest must exercise operational evidence", operational.isNotEmpty())
        assertTrue(partition.accepted.containsAll(operational))
        assertEquals(enabled, partition.accepted + partition.declined + partition.unavailable)
    }

    @Test
    fun enrollmentEvidenceExcludesAndroidUnsupportedAmbientAudio() {
        val fetched = authoritativeSetting(
            CollectionModuleId.AMBIENT_AUDIO to CollectionModuleSetting(enabled = true),
        )

        val partition = enrollmentModulePartition(
            fetched = fetched,
            availableSensorModules = emptySet(),
            accepted = emptySet(),
            declined = emptySet(),
        )

        assertFalse(CollectionModuleId.AMBIENT_AUDIO in partition.accepted)
        assertFalse(CollectionModuleId.AMBIENT_AUDIO in partition.declined)
        assertFalse(CollectionModuleId.AMBIENT_AUDIO in partition.unavailable)
    }

    @Test(expected = IllegalArgumentException::class)
    fun enrollmentPartitionRejectsAnEnabledModuleMissingFromAllThreeSets() {
        enrollmentModulePartition(
            fetched = authoritativeSetting(
                CollectionModuleId.USAGE_EVENTS to CollectionModuleSetting(enabled = true),
            ),
            availableSensorModules = emptySet(),
            accepted = emptySet(),
            declined = emptySet(),
        )
    }

    @Test(expected = IllegalArgumentException::class)
    fun enrollmentPartitionRejectsDecliningARequiredAvailableModule() {
        enrollmentModulePartition(
            fetched = authoritativeSetting(
                CollectionModuleId.SENSOR_ACCELEROMETER to
                    CollectionModuleSetting(enabled = true, required = true),
            ),
            availableSensorModules = setOf(CollectionModuleId.SENSOR_ACCELEROMETER),
            accepted = emptySet(),
            declined = setOf(CollectionModuleId.SENSOR_ACCELEROMETER),
        )
    }
}
