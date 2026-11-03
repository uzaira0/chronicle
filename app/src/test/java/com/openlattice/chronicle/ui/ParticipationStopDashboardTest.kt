package com.openlattice.chronicle.ui

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.openlattice.chronicle.R
import com.openlattice.chronicle.collection.CollectionModuleId
import com.openlattice.chronicle.collection.state.CollectionModuleState
import com.openlattice.chronicle.collection.state.ParticipantDecision
import com.openlattice.chronicle.data.ParticipationStatus
import com.openlattice.chronicle.storage.UploadServerEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** What Overview and Data Sharing say once the study team ends or pauses participation (seen on a Pixel). */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [28])
class ParticipationStopDashboardTest {
    private val context: Context = ApplicationProvider.getApplicationContext()

    // The server's 403 not-enrolled rejections pushed the failure counter past the unhealthy threshold.
    private val rejectingServer = UploadServerEntity(
        name = "study", url = "https://example.org", studyId = "s", participantId = "p",
        sourceRandomDeviceId = "d", lastUploadTime = "2026-10-01T18:00:00Z", consecutiveFailures = 12,
    )
    private val acceptedRequiredSensor = CollectionModuleState(
        moduleId = CollectionModuleId.SENSOR_ACCELEROMETER,
        serverEnabled = true,
        decision = ParticipantDecision.ACCEPTED,
        decidedAtEpochMillis = 1L,
        requiredApplied = true,
        appliedVersion = 2,
    )

    private fun serverHealth(status: ParticipationStatus) =
        DashboardDataRepository.loadServerHealth(
            context, listOf(rejectingServer), online = true, ParticipationStop.of(status),
        ).message

    private fun sensorRow(status: ParticipationStatus) =
        sensorCollectionStatusText(acceptedRequiredSensor, true, null, context::getString, CollectionStopState(ParticipationStop.of(status)))

    @Test fun `enrolled participant keeps collecting and failing uploads still read unhealthy`() {
        assertNull(ParticipationStop.of(ParticipationStatus.ENROLLED))
        assertEquals(context.getString(R.string.server_health_uploads_failing), serverHealth(ParticipationStatus.ENROLLED))
        assertEquals(context.getString(R.string.ds_status_required_collecting), sensorRow(ParticipationStatus.ENROLLED))
    }

    @Test fun `participation ended by the study team says so instead of collecting or unhealthy`() {
        val stop = ParticipationStop.of(ParticipationStatus.NOT_ENROLLED)
        assertEquals(ParticipationStop.ENDED, stop)
        assertEquals(
            "Participant\np\nThe study team ended your participation. Data collection has stopped.",
            context.getString(stop!!.copy.participant, "p"),
        )
        assertEquals(context.getString(R.string.server_health_not_enrolled), serverHealth(ParticipationStatus.NOT_ENROLLED))
        assertEquals(
            "Stopped — the study team ended your participation",
            sensorRow(ParticipationStatus.NOT_ENROLLED),
        )
    }

    @Test fun `participation completed by the study team stops collection and keeps upload health`() {
        val stop = ParticipationStop.of(ParticipationStatus.COLLECTION_COMPLETED)
        assertNotNull(stop)
        assertEquals(
            "Participant\np\nThe study team marked your participation complete. Data collection has stopped.",
            context.getString(stop!!.copy.participant, "p"),
        )
        assertEquals(
            serverHealth(ParticipationStatus.ENROLLED),
            serverHealth(ParticipationStatus.COLLECTION_COMPLETED),
        )
        assertEquals(
            "Stopped — the study team marked your participation complete",
            sensorRow(ParticipationStatus.COLLECTION_COMPLETED),
        )
        assertEquals(
            context.getString(stop.copy.status),
            sensorCollectionStatusText(acceptedRequiredSensor, true, null, context::getString, stop = CollectionStopState(stop)),
        )
    }

    @Test fun `participation paused by the study team pauses collection and keeps upload health`() {
        val stop = ParticipationStop.of(ParticipationStatus.PAUSED)
        assertEquals(ParticipationStop.PAUSED, stop)
        assertEquals(
            "Participant\np\nThe study team paused your participation. Data collection is paused until they resume it.",
            context.getString(stop!!.copy.participant, "p"),
        )
        assertEquals(
            serverHealth(ParticipationStatus.ENROLLED),
            serverHealth(ParticipationStatus.PAUSED),
        )
        assertEquals("Paused by the study team", sensorRow(ParticipationStatus.PAUSED))
    }

    @Test fun `paused and completed collection keep healthy offline and blocked upload messages`() {
        val healthyServer = rejectingServer.copy(consecutiveFailures = 0)
        for (status in listOf(ParticipationStatus.PAUSED, ParticipationStatus.COLLECTION_COMPLETED)) {
            val stop = ParticipationStop.of(status)
            assertEquals(context.getString(R.string.server_health_healthy),
                DashboardDataRepository.loadServerHealth(context, listOf(healthyServer), online = true, stop).message)
            assertEquals(context.getString(R.string.server_health_offline),
                DashboardDataRepository.loadServerHealth(context, listOf(healthyServer), online = false, stop).message)
        }
    }
}
