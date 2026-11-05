package com.openlattice.chronicle.collection.state

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.openlattice.chronicle.R
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
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Coverage for the enrollment orientation wizard's copy (per-module consent design §6, §8): the
 * per-module templates read from the consent resources and the ordered consent plan.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class CollectionConsentCopyTest {

    private val context: Context = ApplicationProvider.getApplicationContext()

    @Test @Config(qualifiers = "es-rCL")
    fun consentBulletsHaveIndependentStringResourcesForPartialLocaleFallback() {
        for (array in listOf(R.array.consent_usage_events_collects, R.array.consent_usage_events_not_collects,
            R.array.consent_device_lifecycle_collects, R.array.consent_device_lifecycle_not_collects,
            R.array.consent_battery_telemetry_collects, R.array.consent_battery_telemetry_not_collects,
            R.array.consent_in_app_activity_class_collects, R.array.consent_in_app_activity_class_not_collects,
            R.array.consent_user_identification_collects, R.array.consent_user_identification_not_collects)) {
            val bullets = context.resources.obtainTypedArray(array)
            try {
                for (index in 0 until bullets.length()) {
                    val key = bullets.getResourceId(index, 0)
                    assertTrue("${context.resources.getResourceEntryName(array)}[$index] needs its own translation key", key != 0)
                    assertEquals(context.getString(key), context.resources.getStringArray(array)[index])
                }
            } finally { bullets.recycle() }
        }
    }

    /**
     * Curated participant copy and the distribution's module policy must agree: a module this
     * artifact refuses to collect never carries consent copy for it, and a module it does support
     * always does. Keying on the channel name alone missed the open sideload build, which
     * supports fewer modules than research while sharing its source set.
     */
    @Test fun testCuratedParticipantCopyExistsForExactlyTheSupportedModules() {
        val distribution = DistributionChannel.current()
        (CollectionStateMachine.ACK_GATED_MODULES + CollectionModuleId.AUDIO_CONTENT).forEach { moduleId ->
            val template = CollectionConsentCopy.template(context, moduleId)
            if (DistributionModulePolicy.supports(distribution, moduleId)) {
                assertTrue("$moduleId needs a label", template.label.isNotBlank())
                assertTrue("$moduleId needs a privacy class", template.privacyClass.isNotBlank())
                assertTrue("$moduleId is supported and needs curated copy", template.whatItCollects.isNotEmpty())
                assertTrue("$moduleId is supported and needs curated copy", template.whatItDoesNotCollect.isNotEmpty())
            } else {
                assertTrue("$moduleId is unsupported and must carry no copy", template.whatItCollects.isEmpty())
                assertTrue("$moduleId is unsupported and must carry no copy", template.whatItDoesNotCollect.isEmpty())
            }
        }
    }

    @Test fun testTemplateSynthesizesSafeFallbackForUnmappedModule() {
        // A module with no curated names (questionnaire is outside the pilot); the fallback
        // still produces a usable label + privacy class rather than crashing.
        val template = CollectionConsentCopy.template(context, CollectionModuleId.QUESTIONNAIRE)
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

    @Test fun testWizardStepLineSaysPositionAndRequirementOnce() {
        val required = context.getString(R.string.orientation_required)
        val optional = context.getString(R.string.orientation_optional)
        assertEquals("1 of 5 · Required", context.getString(R.string.orientation_step, 1, 5, required))
        assertEquals("5 of 5 · Optional", context.getString(R.string.orientation_step, 5, 5, optional))
        assertEquals("You must allow this to take part.", context.getString(R.string.orientation_required_detail))
        assertEquals("You can skip this and still take part.", context.getString(R.string.orientation_optional_detail))
    }

    @Test @Config(qualifiers = "es-rCL")
    fun spanishWizardStepLineSaysPositionAndRequirementOnce() {
        assertEquals("2 de 3 · Obligatorio",
            context.getString(R.string.orientation_step, 2, 3, context.getString(R.string.orientation_required)))
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
    }

    @Test fun testEmptyConsentPlanIsEmpty() {
        assertTrue(ConsentPlan(emptyList(), emptyList()).isEmpty)
    }

    private fun resolved(moduleId: CollectionModuleId, required: Boolean): ResolvedModuleSetting =
        ResolvedModuleSetting(
            moduleId = moduleId,
            setting = CollectionModuleSetting(enabled = true, required = required),
            source = ResolutionSource.GENERALIZED,
            valid = true,
        )
}
