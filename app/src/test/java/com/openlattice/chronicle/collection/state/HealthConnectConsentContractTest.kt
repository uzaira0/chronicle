package com.openlattice.chronicle.collection.state

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.openlattice.chronicle.BuildConfig
import com.openlattice.chronicle.collection.CollectionModuleId
import com.openlattice.chronicle.collection.HealthConnectRecordType
import com.openlattice.chronicle.ui.healthConnectReconsentMessage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class HealthConnectConsentContractTest {

    private val context: Context = ApplicationProvider.getApplicationContext()

    @Test
    fun consentCopyListsOnlyTheStudyApprovedRecordTypesInCanonicalOrder() {
        val template = CollectionConsentCopy.consentTemplate(
            context = context,
            moduleId = CollectionModuleId.HEALTH_CONNECT,
            healthConnectRecordTypes = linkedSetOf(
                HealthConnectRecordType.SLEEP,
                HealthConnectRecordType.STEPS,
                HealthConnectRecordType.HEART_RATE,
            ),
        )

        if (!BuildConfig.HAS_HEALTH_CONNECT) {
            assertTrue(template.whatItCollects.isEmpty())
            assertTrue(template.whatItDoesNotCollect.isEmpty())
            return
        }

        assertEquals(
            listOf(
                "Steps",
                "Heart rate",
                "Sleep sessions and stages",
                "When each approved record was recorded",
            ),
            template.whatItCollects,
        )
        assertFalse(template.whatItCollects.joinToString().contains("Distance"))
        // One list serves the consent and Data Sharing views (and one translatable resource).
        assertTrue(template.whatItDoesNotCollect.first().contains("not listed for the active study"))
    }

    @Test
    fun enabledHealthConnectStepRefusesAnEmptyApprovedScope() {
        assertThrows(IllegalArgumentException::class.java) {
            ConsentPlan(
                required = listOf(CollectionModuleId.HEALTH_CONNECT),
                optional = emptyList(),
                healthConnectRecordTypes = emptySet(),
            )
        }
        if (BuildConfig.HAS_HEALTH_CONNECT) {
            assertThrows(IllegalArgumentException::class.java) {
                CollectionConsentCopy.consentTemplate(
                    context = context,
                    moduleId = CollectionModuleId.HEALTH_CONNECT,
                    healthConnectRecordTypes = emptySet(),
                )
            }
        } else {
            assertTrue(
                CollectionConsentCopy.consentTemplate(
                    context = context,
                    moduleId = CollectionModuleId.HEALTH_CONNECT,
                    healthConnectRecordTypes = emptySet(),
                ).whatItCollects.isEmpty(),
            )
        }
    }

    @Test
    fun consentPlanCarriesTheExactApprovedScope() {
        val approved = linkedSetOf(
            HealthConnectRecordType.STEPS,
            HealthConnectRecordType.OXYGEN_SATURATION,
        )

        val plan = ConsentPlan(
            required = emptyList(),
            optional = listOf(CollectionModuleId.HEALTH_CONNECT),
            healthConnectRecordTypes = approved,
        )

        assertEquals(approved, plan.healthConnectRecordTypes)
    }

    @Test
    fun dataSharingReconsentNamesTheExactCurrentScopeAndRejectsAnEmptyScope() {
        if (!BuildConfig.HAS_HEALTH_CONNECT) {
            assertTrue(
                CollectionConsentCopy.consentTemplate(
                    context = context,
                    moduleId = CollectionModuleId.HEALTH_CONNECT,
                    healthConnectRecordTypes = emptySet(),
                ).whatItCollects.isEmpty(),
            )
            return
        }
        val message = healthConnectReconsentMessage(
            context,
            linkedSetOf(HealthConnectRecordType.STEPS, HealthConnectRecordType.SLEEP),
        )

        assertTrue(message.contains("• Steps"))
        assertTrue(message.contains("• Sleep sessions and stages"))
        assertFalse(message.contains("Distance"))
        assertThrows(IllegalArgumentException::class.java) {
            healthConnectReconsentMessage(context, emptySet())
        }
    }

    @Test
    fun reconsentCannotOpenTheGateForAChangedScope() {
        val reviewed = setOf(HealthConnectRecordType.STEPS, HealthConnectRecordType.SLEEP)

        assertTrue(healthConnectScopeMatchesReview(reviewed, reviewed))
        assertFalse(
            healthConnectScopeMatchesReview(
                reviewed,
                reviewed + HealthConnectRecordType.HEART_RATE,
            ),
        )
        assertFalse(healthConnectScopeMatchesReview(reviewed, emptySet()))
    }
}
