package com.openlattice.chronicle.ui

import com.openlattice.chronicle.collection.CollectionModuleId
import com.openlattice.chronicle.collection.state.CollectionModuleState
import com.openlattice.chronicle.collection.state.ParticipantDecision
import org.junit.Assert.assertEquals
import org.junit.Test

public class DataSharingSensorStatusTest {
    @Test
    public fun absentHardwareTakesPrecedenceOverMissingReconciledState() {
        assertEquals(
            "Sensor is not available on this device",
            sensorCollectionStatusText(state = null, isAvailable = false, capability = null),
        )
    }

    @Test
    public fun availableSensorWithoutEnabledStudyStateIsNotCollected() {
        assertEquals(
            "Not collected by this study",
            sensorCollectionStatusText(state = null, isAvailable = true, capability = null),
        )
    }

    @Test
    public fun acceptedRequiredAvailableSensorIsCollecting() {
        val state = CollectionModuleState(
            moduleId = CollectionModuleId.SENSOR_ACCELEROMETER,
            serverEnabled = true,
            decision = ParticipantDecision.ACCEPTED,
            decidedAtEpochMillis = 1L,
            requiredApplied = true,
            appliedVersion = 2,
            appliedPolicySnapshot = null,
            lastDisposition = null,
        )

        assertEquals(
            "Required — collecting",
            sensorCollectionStatusText(state = state, isAvailable = true, capability = null),
        )
    }
}
