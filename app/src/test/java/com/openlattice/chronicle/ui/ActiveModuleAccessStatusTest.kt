package com.openlattice.chronicle.ui

import com.openlattice.chronicle.collection.CollectionModuleId
import com.openlattice.chronicle.collection.capability.CapabilityEnvironment
import com.openlattice.chronicle.collection.capability.DistributionChannel
import com.openlattice.chronicle.collection.state.CollectionModuleState
import com.openlattice.chronicle.collection.state.ParticipantDecision
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Drives MainActivity's Data Sharing route and the Overview "access needed" shortcut. */
public class ActiveModuleAccessStatusTest {
    private val activeUsage = CollectionModuleState(
        moduleId = CollectionModuleId.USAGE_EVENTS,
        serverEnabled = true,
        decision = ParticipantDecision.ACCEPTED,
        decidedAtEpochMillis = 1L,
        requiredApplied = true,
        appliedVersion = 1,
        appliedPolicySnapshot = null,
        lastDisposition = null,
    )

    private fun environment(usageAccessGranted: Boolean) = CapabilityEnvironment(
        sdkInt = 36,
        distribution = DistributionChannel.OPEN,
        googleServicesAvailable = true,
        healthConnectAvailable = true,
        healthConnectGranted = true,
        usageAccessGranted = usageAccessGranted,
        grantedRuntimePermissions = emptySet(),
        notificationListenerEnabled = true,
        accessibilityEnabled = true,
    )

    @Test
    public fun activeUsageEventsWithoutUsageAccessNeedsAccess() {
        val status = activeModulePermissionStatus(listOf(activeUsage), environment(usageAccessGranted = false))
        assertTrue(status.needUsageAccess)
        assertTrue(status.hasMissing)
    }

    @Test
    public fun grantedUsageAccessNeedsNothing() {
        assertFalse(activeModulePermissionStatus(listOf(activeUsage), environment(usageAccessGranted = true)).hasMissing)
    }

    @Test
    public fun undecidedModuleNeverPromptsForAccess() {
        val undecided = activeUsage.copy(decision = ParticipantDecision.UNDECIDED, decidedAtEpochMillis = null)
        assertFalse(activeModulePermissionStatus(listOf(undecided), environment(usageAccessGranted = false)).hasMissing)
    }
}
