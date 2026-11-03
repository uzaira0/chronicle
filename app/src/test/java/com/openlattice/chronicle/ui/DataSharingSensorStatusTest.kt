package com.openlattice.chronicle.ui

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.openlattice.chronicle.collection.CollectionModuleId
import com.openlattice.chronicle.collection.state.CollectionModuleState
import com.openlattice.chronicle.collection.state.ParticipantDecision
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [28], qualifiers = "en")
public class DataSharingSensorStatusTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    @Test
    public fun absentHardwareTakesPrecedenceOverMissingReconciledState() {
        assertEquals(
            "Sensor is not available on this device",
            sensorCollectionStatusText(state = null, isAvailable = false, capability = null, resolve = context::getString),
        )
    }

    @Test
    public fun availableSensorWithoutEnabledStudyStateIsNotCollected() {
        assertEquals(
            "Not collected by this study",
            sensorCollectionStatusText(state = null, isAvailable = true, capability = null, resolve = context::getString),
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
        )

        assertEquals(
            "Required — collecting",
            sensorCollectionStatusText(state = state, isAvailable = true, capability = null, resolve = context::getString),
        )
    }
}
