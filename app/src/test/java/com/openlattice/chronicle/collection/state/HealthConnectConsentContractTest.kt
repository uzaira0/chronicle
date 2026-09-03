package com.openlattice.chronicle.collection.state

import com.openlattice.chronicle.BuildConfig
import com.openlattice.chronicle.collection.CollectionModuleId
import com.openlattice.chronicle.collection.HealthConnectRecordType
import com.openlattice.chronicle.ui.healthConnectReconsentMessage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class HealthConnectConsentContractTest {

    @Test
    fun consentCopyListsOnlyTheStudyApprovedRecordTypesInCanonicalOrder() {
        val template = CollectionConsentCopy.consentTemplate(
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
        assertTrue(template.whatItDoesNotCollect.first().contains("not listed above"))
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
                    moduleId = CollectionModuleId.HEALTH_CONNECT,
                    healthConnectRecordTypes = emptySet(),
                )
            }
        } else {
            assertTrue(
                CollectionConsentCopy.consentTemplate(
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
                    moduleId = CollectionModuleId.HEALTH_CONNECT,
                    healthConnectRecordTypes = emptySet(),
                ).whatItCollects.isEmpty(),
            )
            return
        }
        val message = healthConnectReconsentMessage(
            linkedSetOf(HealthConnectRecordType.STEPS, HealthConnectRecordType.SLEEP),
        )

        assertTrue(message.contains("• Steps"))
        assertTrue(message.contains("• Sleep sessions and stages"))
        assertFalse(message.contains("Distance"))
        assertThrows(IllegalArgumentException::class.java) {
            healthConnectReconsentMessage(emptySet())
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
