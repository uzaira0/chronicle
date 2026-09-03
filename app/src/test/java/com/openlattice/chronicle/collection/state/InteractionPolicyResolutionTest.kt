package com.openlattice.chronicle.collection.state

import com.openlattice.chronicle.android.AndroidSensorSetting
import com.openlattice.chronicle.android.AndroidSensorType
import com.openlattice.chronicle.collection.CollectionModuleId
import com.openlattice.chronicle.collection.CollectionModuleSetting
import com.openlattice.chronicle.collection.InteractionPolicy
import com.openlattice.chronicle.collection.SensorCollectionModules
import com.openlattice.chronicle.collection.settings.ResolvedModuleSetting
import com.openlattice.chronicle.collection.settings.ResolutionSource
import com.openlattice.chronicle.preferences.InteractionPolicySnapshot
import com.openlattice.chronicle.preferences.decodeInteractionPolicySnapshot
import com.openlattice.chronicle.preferences.encodeInteractionPolicySnapshot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.UUID

class InteractionPolicyResolutionTest {
    @Test
    fun `enabled interaction module uses study policy`() {
        val studyPolicy = InteractionPolicy(
            gridRows = 6,
            gridCols = 5,
            captureClicks = false,
            captureScrolls = true,
            captureExactPosition = false,
        )
        val setting = CollectionModuleSetting(enabled = true, interactionPolicy = studyPolicy)
        val resolved = ResolvedModuleSetting(
            moduleId = CollectionModuleId.INTERACTION_EVENTS,
            setting = setting,
            source = ResolutionSource.GENERALIZED,
            valid = true,
        )

        assertEquals(
            studyPolicy,
            CollectionLoopCoordinator.interactionPolicyFor(
                mapOf(CollectionModuleId.INTERACTION_EVENTS to resolved),
            ),
        )
    }

    @Test
    fun `disabled or absent interaction module uses default policy`() {
        val disabled = ResolvedModuleSetting(
            moduleId = CollectionModuleId.INTERACTION_EVENTS,
            setting = CollectionModuleSetting(
                enabled = false,
                interactionPolicy = InteractionPolicy(captureExactPosition = false),
            ),
            source = ResolutionSource.GENERALIZED,
            valid = true,
        )

        assertEquals(
            InteractionPolicy.DEFAULT,
            CollectionLoopCoordinator.interactionPolicyFor(
                mapOf(CollectionModuleId.INTERACTION_EVENTS to disabled),
            ),
        )
        assertEquals(InteractionPolicy.DEFAULT, CollectionLoopCoordinator.interactionPolicyFor(emptyMap()))
    }

    @Test
    fun `policy snapshot is atomic and active only for its enrolled study`() {
        val studyId = UUID.randomUUID().toString()
        val policy = InteractionPolicy(gridRows = 7, gridCols = 4, captureExactPosition = false)
        val snapshot = InteractionPolicySnapshot(
            studyId = studyId,
            settingsVersion = 19,
            enabled = true,
            policy = policy,
        )

        val decoded = decodeInteractionPolicySnapshot(encodeInteractionPolicySnapshot(snapshot))

        assertEquals(snapshot, decoded)
        assertEquals(policy, decoded.activePolicyFor(studyId, enrolled = true))
        assertNull(decoded.activePolicyFor(UUID.randomUUID().toString(), enrolled = true))
        assertNull(decoded.activePolicyFor(studyId, enrolled = false))
        assertNull(decoded.copy(enabled = false).activePolicyFor(studyId, enrolled = true))
    }

    @Test
    fun `runtime settings persist sensors before publishing interaction generation`() {
        val sensorType = AndroidSensorType.accelerometer
        val sensorPolicy = AndroidSensorSetting(
            sensors = setOf(sensorType),
            samplingRateHz = 17,
            dutyCycleActiveSeconds = 11,
            dutyCyclePeriodSeconds = 61,
        )
        val sensorModule = SensorCollectionModules.moduleFor(sensorType)
        val interactionPolicy = InteractionPolicy(captureExactPosition = false)
        val resolved = mapOf(
            sensorModule to ResolvedModuleSetting(
                moduleId = sensorModule,
                setting = CollectionModuleSetting(enabled = true, sensorPolicy = sensorPolicy),
                source = ResolutionSource.GENERALIZED,
                valid = true,
            ),
            CollectionModuleId.INTERACTION_EVENTS to ResolvedModuleSetting(
                moduleId = CollectionModuleId.INTERACTION_EVENTS,
                setting = CollectionModuleSetting(enabled = true, interactionPolicy = interactionPolicy),
                source = ResolutionSource.GENERALIZED,
                valid = true,
            ),
        )
        val calls = mutableListOf<String>()

        val persisted = CollectionLoopCoordinator.applyRuntimeSettingsBeforeGate(
            resolved = resolved,
            persistSensors = { settings ->
                calls += "sensors"
                assertEquals(sensorPolicy, settings[sensorType])
                true
            },
            persistInteraction = { enabled, policy ->
                calls += "interaction"
                assertTrue(enabled)
                assertEquals(interactionPolicy, policy)
                true
            },
        )

        assertTrue(persisted)
        assertEquals(listOf("sensors", "interaction"), calls)
    }

    @Test
    fun `sensor persistence failure prevents interaction generation publication`() {
        var interactionPublished = false

        val persisted = CollectionLoopCoordinator.applyRuntimeSettingsBeforeGate(
            resolved = emptyMap(),
            persistSensors = { false },
            persistInteraction = { _, _ -> interactionPublished = true; true },
        )

        assertFalse(persisted)
        assertFalse(interactionPublished)
    }
}
