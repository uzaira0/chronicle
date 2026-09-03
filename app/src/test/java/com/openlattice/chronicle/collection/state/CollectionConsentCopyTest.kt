package com.openlattice.chronicle.collection.state

import com.openlattice.chronicle.BuildConfig
import com.openlattice.chronicle.collection.CollectionModuleId
import com.openlattice.chronicle.collection.CollectionModuleSetting
import com.openlattice.chronicle.collection.capability.DistributionChannel
import com.openlattice.chronicle.collection.capability.DistributionModulePolicy
import com.openlattice.chronicle.collection.settings.ResolutionSource
import com.openlattice.chronicle.collection.settings.ResolvedModuleSetting
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Coverage for the pure, JVM-testable pieces of the enrollment orientation wizard
 * (per-module consent design §6, §8): the per-module copy templates and the ordered
 * consent plan. The wizard Activity itself is instrumentation-only (blocked on this host).
 */
class CollectionConsentCopyTest {

    @Test fun testEveryGatedModuleHasAStructuredTemplate() {
        CollectionStateMachine.ACK_GATED_MODULES
            .filter { DistributionModulePolicy.supports(DistributionChannel.PLAY, it) }
            .forEach { moduleId ->
            val template = CollectionConsentCopy.template(moduleId)
            assertTrue("$moduleId needs a label", template.label.isNotBlank())
            assertTrue("$moduleId needs at least one 'what it collects' bullet", template.whatItCollects.isNotEmpty())
            assertTrue(
                "$moduleId needs at least one 'what it does NOT collect' bullet",
                template.whatItDoesNotCollect.isNotEmpty(),
            )
            assertTrue("$moduleId needs a privacy class", template.privacyClass.isNotBlank())
        }
    }

    @Test fun testMinimalPublicExcludedModuleHasNoCuratedParticipantCopy() {
        val template = CollectionConsentCopy.template(CollectionModuleId.AUDIO_CONTENT)
        if (BuildConfig.DISTRIBUTION_CHANNEL in setOf("PLAY", "AMAZON")) {
            assertTrue(template.whatItCollects.isEmpty())
            assertTrue(template.whatItDoesNotCollect.isEmpty())
        } else {
            assertTrue(template.whatItCollects.isNotEmpty())
            assertTrue(template.whatItDoesNotCollect.isNotEmpty())
        }
    }

    @Test fun testTemplateSynthesizesSafeFallbackForUnmappedModule() {
        // An operational (non-gated) module has no curated template; the fallback still
        // produces a usable label + privacy class rather than crashing.
        val template = CollectionConsentCopy.template(CollectionModuleId.UPLOAD_TELEMETRY)
        assertTrue(template.label.isNotBlank())
        assertTrue(template.whatItCollects.isEmpty())
    }

    @Test fun testConsentPlanOrdersRequiredBeforeOptional() {
        val plan = ConsentPlan(
            required = listOf(CollectionModuleId.USAGE_EVENTS, CollectionModuleId.DEVICE_LIFECYCLE),
            optional = listOf(CollectionModuleId.BATTERY_TELEMETRY),
        )
        assertEquals(
            listOf(
                CollectionModuleId.USAGE_EVENTS,
                CollectionModuleId.DEVICE_LIFECYCLE,
                CollectionModuleId.BATTERY_TELEMETRY,
            ),
            plan.orderedModules,
        )
        assertFalse(plan.isEmpty)
    }

    @Test fun testWizardRequirementCopyNamesRequiredAndOptionalStudySteps() {
        val required = StepRequirementCopy.forRequired(true)
        assertEquals("Required by source study", required.displayLabel)
        assertEquals("Required by source study", required.shortLabel)
        assertTrue(required.detail.contains("required"))
        assertTrue(required.acceptLabel.contains("required"))
        assertEquals("Don't allow", required.declineLabel)

        val optional = StepRequirementCopy.forRequired(false)
        assertEquals("Optional for source study", optional.displayLabel)
        assertEquals("Optional for source study", optional.shortLabel)
        assertTrue(optional.detail.contains("optional"))
        assertTrue(optional.detail.contains("still enroll"))
        assertTrue(optional.acceptLabel.contains("optional"))
        assertEquals("Don't allow", optional.declineLabel)
    }

    @Test fun testWizardRequirementSummaryIsDerivedFromSourceStudyPlan() {
        val plan = ConsentPlan(
            required = listOf(CollectionModuleId.USAGE_EVENTS, CollectionModuleId.DEVICE_LIFECYCLE),
            optional = listOf(CollectionModuleId.BATTERY_TELEMETRY),
        )

        assertEquals(
            listOf(
                "Step 1: [Required by source study] App Usage Events",
                "Step 2: [Required by source study] Device Lifecycle",
                "Step 3: [Optional for source study] Battery Telemetry",
            ),
            plan.requirementSummaryLines(),
        )
        assertEquals("Source study step plan: 2 required steps, 1 optional step", plan.requirementPlanHeader())
    }

    @Test fun testWizardPlanSplitsRequiredAndOptionalFromSourceStudySettings() {
        val transitions = CollectionStateMachine.reconcile(
            previous = emptyMap(),
            resolved = mapOf(
                CollectionModuleId.USAGE_EVENTS to resolved(CollectionModuleId.USAGE_EVENTS, required = true),
                CollectionModuleId.BATTERY_TELEMETRY to resolved(CollectionModuleId.BATTERY_TELEMETRY, required = false),
                CollectionModuleId.DEVICE_LIFECYCLE to resolved(CollectionModuleId.DEVICE_LIFECYCLE, required = true),
            ),
            settingVersion = 7,
        )

        val plan = ConsentPlan.fromTransitions(transitions)

        assertEquals(
            listOf(CollectionModuleId.USAGE_EVENTS, CollectionModuleId.DEVICE_LIFECYCLE),
            plan.required,
        )
        assertEquals(listOf(CollectionModuleId.BATTERY_TELEMETRY), plan.optional)
        assertEquals(
            listOf(
                "Step 1: [Required by source study] App Usage Events",
                "Step 2: [Required by source study] Device Lifecycle",
                "Step 3: [Optional for source study] Battery Telemetry",
            ),
            plan.requirementSummaryLines(),
        )
        assertEquals("Source study step plan: 2 required steps, 1 optional step", plan.requirementPlanHeader())
    }

    @Test fun testEmptyConsentPlanIsEmpty() {
        assertTrue(ConsentPlan(emptyList(), emptyList()).isEmpty)
        assertEquals("Source study step plan: 0 required steps, 0 optional steps", ConsentPlan(emptyList(), emptyList()).requirementPlanHeader())
    }

    private fun resolved(moduleId: CollectionModuleId, required: Boolean): ResolvedModuleSetting =
        ResolvedModuleSetting(
            moduleId = moduleId,
            setting = CollectionModuleSetting(enabled = true, required = required),
            source = ResolutionSource.GENERALIZED,
            valid = true,
        )
}
