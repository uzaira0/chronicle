package com.openlattice.chronicle.collection.capability

import com.openlattice.chronicle.BuildConfig
import com.openlattice.chronicle.collection.CollectionModuleId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The module policy must never promise a module the artifact cannot start. v58 (open flavor,
 * Play internal track) shipped with the restricted collectors compiled out while the policy
 * accepted every module, so a study with all modules enabled collected only usage, battery,
 * connectivity and device settings. These tests run under every flavor's unit test task.
 */
class DistributionModulePolicyContractTest {

    @Test
    fun openBuildOmitsInteractionNotificationAndAudioCollectors() {
        if (DistributionChannel.current() != DistributionChannel.OPEN) return
        listOf(
            CollectionModuleId.INTERACTION_EVENTS,
            CollectionModuleId.NOTIFICATION_ACTIVITY,
            CollectionModuleId.AUDIO_ACTIVITY,
            CollectionModuleId.AUDIO_CONTENT,
        ).forEach { moduleId ->
            assertFalse(DistributionModulePolicy.supports(DistributionChannel.OPEN, moduleId))
            assertTrue(DistributionModulePolicy.supports(DistributionChannel.RESEARCH, moduleId))
        }
        assertTrue(DistributionModulePolicy.supports(DistributionChannel.OPEN, CollectionModuleId.SENSOR_ACCELEROMETER))
    }

    @Test
    fun researchSupportsRestrictedModulesOnlyWhenTheCollectorsAreCompiledIn() {
        val distribution = DistributionChannel.RESEARCH
        DistributionModulePolicy.restrictedResearchModules.forEach { moduleId ->
            assertTrue(
                "${moduleId.id} must be supported on $distribution when compiled in",
                DistributionModulePolicy.supports(distribution, moduleId, restrictedCollectorsCompiledIn = true),
            )
            assertFalse(
                "${moduleId.id} must be rejected on $distribution when compiled out",
                DistributionModulePolicy.supports(distribution, moduleId, restrictedCollectorsCompiledIn = false),
            )
        }
    }

    @Test
    fun unrestrictedModulesAreSupportedOnResearchRegardlessOfTheFlag() {
        val unrestricted = CollectionModuleId.entries.filter {
            it.active && it !in DistributionModulePolicy.restrictedResearchModules
        }
        val distribution = DistributionChannel.RESEARCH
        unrestricted.forEach { moduleId ->
            assertTrue(DistributionModulePolicy.supports(distribution, moduleId, restrictedCollectorsCompiledIn = false))
            assertTrue(DistributionModulePolicy.supports(distribution, moduleId, restrictedCollectorsCompiledIn = true))
        }
    }

    @Test
    fun thisArtifactCompilesInWhatItsChannelPromises() {
        val channel = DistributionChannel.current()
        if (channel.minimalPublic) return
        assertTrue(
            "$channel needs ALLOW_RESTRICTED_RESEARCH_PERMISSIONS to run its approved restricted collectors",
            BuildConfig.ALLOW_RESTRICTED_RESEARCH_PERMISSIONS,
        )
        DistributionModulePolicy.restrictedResearchModules.forEach { moduleId ->
            assertEquals(
                "${moduleId.id} must follow the $channel artifact registry",
                channel == DistributionChannel.RESEARCH ||
                    moduleId.id in BuildConfig.DISTRIBUTION_APPROVED_MODULE_IDS.split(','),
                DistributionModulePolicy.supports(channel, moduleId),
            )
        }
        if (channel == DistributionChannel.RESEARCH) {
            assertEquals("Research must have no registry allowlist", "", BuildConfig.DISTRIBUTION_APPROVED_MODULE_IDS)
        }
    }
}
