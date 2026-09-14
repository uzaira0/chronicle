package com.openlattice.chronicle.collection.capability

import com.openlattice.chronicle.BuildConfig
import com.openlattice.chronicle.collection.CollectionModuleId
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

    private val fullDistributions = listOf(DistributionChannel.OPEN, DistributionChannel.RESEARCH)

    @Test
    fun fullDistributionsSupportRestrictedModulesOnlyWhenTheCollectorsAreCompiledIn() {
        fullDistributions.forEach { distribution ->
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
    }

    @Test
    fun unrestrictedModulesAreSupportedOnFullDistributionsRegardlessOfTheFlag() {
        val unrestricted = CollectionModuleId.entries.filter {
            it.active && it !in DistributionModulePolicy.restrictedResearchModules
        }
        fullDistributions.forEach { distribution ->
            unrestricted.forEach { moduleId ->
                assertTrue(DistributionModulePolicy.supports(distribution, moduleId, restrictedCollectorsCompiledIn = false))
                assertTrue(DistributionModulePolicy.supports(distribution, moduleId, restrictedCollectorsCompiledIn = true))
            }
        }
    }

    @Test
    fun thisArtifactCompilesInWhatItsChannelPromises() {
        val channel = DistributionChannel.current()
        if (channel !in fullDistributions) return
        assertTrue(
            "$channel is a full distribution: ALLOW_RESTRICTED_RESEARCH_PERMISSIONS must be true in app/build.gradle",
            BuildConfig.ALLOW_RESTRICTED_RESEARCH_PERMISSIONS,
        )
        DistributionModulePolicy.restrictedResearchModules.forEach { moduleId ->
            assertTrue(
                "${moduleId.id} must be supported by the $channel artifact",
                DistributionModulePolicy.supports(channel, moduleId),
            )
        }
    }
}
