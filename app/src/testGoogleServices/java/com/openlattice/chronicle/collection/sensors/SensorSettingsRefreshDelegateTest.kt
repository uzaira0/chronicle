package com.openlattice.chronicle.collection.sensors

import com.openlattice.chronicle.collection.state.EnrollmentIdentity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * JVM unit coverage for [SensorSettingsRefreshDelegate]: it reports which modeled sensors the device has to every
 * enabled server, whatever sensor modules the study enabled, and records a failed report for visibility.
 */
class SensorSettingsRefreshDelegateTest {

    private fun server(id: Long = 1L, name: String = "srv$id") = SensorSettingsGateway.Server(
        id = id,
        name = name,
        identity = EnrollmentIdentity(
            studyId = "00000000-0000-0000-0000-000000000001",
            participantId = "p$id",
            sourceRandomDeviceId = "d$id",
        ),
        apiKey = "key$id",
        url = "https://srv$id.example",
        sensorConsecutiveFailures = 0,
        lastUploadedSensorId = null,
    )

    /** A fake gateway recording which servers were reported to. */
    private class FakeGateway(
        val servers: MutableList<SensorSettingsGateway.Server> = mutableListOf(),
        var reportSucceedsFor: (SensorSettingsGateway.Server) -> Boolean = { true },
    ) : SensorSettingsGateway {
        val availabilityFailuresRecorded = mutableListOf<Long>()
        val reported = mutableListOf<Long>()

        override fun enabledServers(): List<SensorSettingsGateway.Server> = servers

        override fun reportAvailability(server: SensorSettingsGateway.Server): Boolean {
            reported.add(server.id)
            return reportSucceedsFor(server)
        }

        override fun recordAvailabilityFailure(server: SensorSettingsGateway.Server) {
            availabilityFailuresRecorded.add(server.id)
        }
    }

    @Test
    fun noEnabledServersSkipsTheRefreshAsSuccess() {
        val gateway = FakeGateway()
        val result = SensorSettingsRefreshDelegate(gateway).refresh()
        assertEquals(SensorSettingsRefreshOutcome.SUCCESS, result.outcome)
        assertTrue("no servers → nothing reported", gateway.reported.isEmpty())
    }

    @Test
    fun reportsToEveryEnabledServer() {
        val gateway = FakeGateway(servers = mutableListOf(server(1L), server(2L), server(3L)))
        val result = SensorSettingsRefreshDelegate(gateway).refresh()
        assertEquals(SensorSettingsRefreshOutcome.SUCCESS, result.outcome)
        assertEquals(listOf(1L, 2L, 3L), gateway.reported.sorted())
    }

    @Test
    fun partialAvailabilityFailureRecordsFailureAndRetries() {
        val gateway = FakeGateway(
            servers = mutableListOf(server(1L), server(2L)),
            reportSucceedsFor = { it.id != 2L },
        )
        val result = SensorSettingsRefreshDelegate(gateway).refresh()

        assertEquals(SensorSettingsRefreshOutcome.RETRY, result.outcome)
        // The failed server's status is updated for visibility — never a silent skip.
        assertEquals(listOf(2L), gateway.availabilityFailuresRecorded)
    }
}
