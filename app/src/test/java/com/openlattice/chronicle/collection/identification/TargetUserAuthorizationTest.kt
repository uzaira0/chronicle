package com.openlattice.chronicle.collection.identification

import com.openlattice.chronicle.collection.state.ResearchPersistenceBarrier
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class TargetUserAuthorizationTest {

    @Test
    fun disabledManifestRejectsTargetUserWritesEvenWhenPreferenceIsOn() {
        assertEquals(
            TargetUserWriteRoute.REJECT,
            targetUserWriteRoute(
                studyAuthorized = false,
                participantEnabled = true,
                resettingToUnassigned = false,
            ),
        )
    }

    @Test
    fun authorizedScopeAllowsEnabledChoiceAndOnlyTheDisabledCleanupWrite() {
        assertEquals(
            TargetUserWriteRoute.MODULE,
            targetUserWriteRoute(true, participantEnabled = true, resettingToUnassigned = false),
        )
        assertEquals(
            TargetUserWriteRoute.RESET_TO_UNASSIGNED,
            targetUserWriteRoute(true, participantEnabled = false, resettingToUnassigned = true),
        )
        assertEquals(
            TargetUserWriteRoute.REJECT,
            targetUserWriteRoute(true, participantEnabled = false, resettingToUnassigned = false),
        )
    }

    @Test
    fun postWithdrawalBoundaryRejectsAnOtherwiseAuthorizedLateWrite() {
        val barrier = ResearchPersistenceBarrier()
        val activeEnrollment = AtomicBoolean(true)
        val writes = AtomicInteger()
        barrier.stop { activeEnrollment.set(false) }

        val persisted = barrier.persistIf(activeEnrollment::get) {
            if (
                targetUserWriteRoute(true, participantEnabled = true, resettingToUnassigned = false) ==
                TargetUserWriteRoute.MODULE
            ) {
                writes.incrementAndGet()
            }
        }

        assertFalse(persisted)
        assertEquals(0, writes.get())
    }

}
