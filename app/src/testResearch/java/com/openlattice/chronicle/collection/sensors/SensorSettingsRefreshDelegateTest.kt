package com.openlattice.chronicle.collection.sensors

import com.openlattice.chronicle.android.AndroidSensorSetting
import com.openlattice.chronicle.android.AndroidSensorType
import com.openlattice.chronicle.collection.core.NoOpCollectionLog
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * JVM unit coverage for [SensorSettingsRefreshDelegate].
 *
 * Under the per-sensor consent redesign (2026-06-11) the delegate is **availability-only**:
 * it reports the coordinator-owned per-sensor configured set to every enabled server and
 * must NEVER write or clear the sensor config — per-sensor config and the service lifecycle
 * are owned by `CollectionLoopCoordinator` (the per-sensor `DataCollection` modules), and a
 * stray `save`/`clear` here would clobber them (the Data Sharing read-only Hz/duty source
 * and the runtime config).
 */
class SensorSettingsRefreshDelegateTest {

    private fun server(id: Long = 1L, name: String = "srv$id") = SensorSettingsGateway.Server(
        id = id,
        name = name,
        studyId = "00000000-0000-0000-0000-000000000001",
        participantId = "p$id",
        sourceDeviceId = "d$id",
        apiKey = "key$id",
        mobileSigningSecretOverride = null,
        url = "https://srv$id.example",
        sensorConsecutiveFailures = 0,
        lastUploadedSensorId = null,
    )

    private val accelSetting = AndroidSensorSetting(
        sensors = setOf(AndroidSensorType.accelerometer),
        samplingRateHz = 5,
        dutyCycleActiveSeconds = 30,
        dutyCyclePeriodSeconds = 300,
    )

    /** A fake gateway recording what was reported to which server. */
    private class FakeGateway(
        val servers: MutableList<SensorSettingsGateway.Server> = mutableListOf(),
        var reportSucceedsFor: (SensorSettingsGateway.Server) -> Boolean = { true },
    ) : SensorSettingsGateway {
        val availabilityFailuresRecorded = mutableListOf<Long>()
        val reported = mutableListOf<Pair<Long, Set<AndroidSensorType>>>()

        override fun enabledServers(): List<SensorSettingsGateway.Server> = servers

        // Retired path — the availability-only refresh never calls this.
        override fun fetchAndroidSensorSetting(server: SensorSettingsGateway.Server) = AndroidSensorSetting()

        override fun reportAvailability(
            server: SensorSettingsGateway.Server,
            requestedSensors: Set<AndroidSensorType>,
        ): Boolean {
            reported.add(server.id to requestedSensors)
            return reportSucceedsFor(server)
        }

        override fun recordAvailabilityFailure(server: SensorSettingsGateway.Server) {
            availabilityFailuresRecorded.add(server.id)
        }
    }

    /** A fake settings store recording any write/clear (which must never happen). */
    private class FakeStore(
        var current: AndroidSensorSetting? = null,
    ) : SensorSettingsStore {
        var cleared = false
        var saveCount = 0
        override fun read(): AndroidSensorSetting? = current
        override fun isEffectivelyEnabled(): Boolean = current?.sensors?.isNotEmpty() ?: false
        override fun save(setting: AndroidSensorSetting) {
            saveCount++
            current = setting
        }

        override fun clear() {
            cleared = true
            current = null
        }
    }

    private fun delegate(gateway: FakeGateway, store: FakeStore) =
        SensorSettingsRefreshDelegate(gateway = gateway, store = store, log = NoOpCollectionLog)

    @Test
    fun noEnabledServersSkipsTheRefreshAsSuccess() {
        val gateway = FakeGateway() // empty server list
        val store = FakeStore(current = accelSetting)
        val result = delegate(gateway, store).refresh()
        assertEquals(SensorSettingsRefreshOutcome.SUCCESS, result.outcome)
        assertTrue("no servers → nothing reported", gateway.reported.isEmpty())
    }

    @Test
    fun reportsConfiguredSensorSetToEveryEnabledServer() {
        val gateway = FakeGateway(servers = mutableListOf(server(1L), server(2L), server(3L)))
        val store = FakeStore(current = accelSetting)
        val result = delegate(gateway, store).refresh()
        assertEquals(SensorSettingsRefreshOutcome.SUCCESS, result.outcome)
        assertEquals(listOf(1L, 2L, 3L), gateway.reported.map { it.first }.sorted())
        // The reported requested-set is the coordinator-owned per-sensor configured set.
        assertTrue(gateway.reported.all { it.second == setOf(AndroidSensorType.accelerometer) })
    }

    @Test
    fun emptyConfiguredSetReportsNothingButSucceeds() {
        val gateway = FakeGateway(servers = mutableListOf(server()))
        val store = FakeStore(current = null)
        val result = delegate(gateway, store).refresh()
        assertEquals(SensorSettingsRefreshOutcome.SUCCESS, result.outcome)
        assertTrue("empty configured set → nothing to report", gateway.reported.isEmpty())
    }

    @Test
    fun neverWritesOrClearsTheCoordinatorOwnedSensorConfig() {
        // Regression: the legacy refresh used to save()/clear() SensorSettings from the
        // retired device-wide AndroidSensor fetch, clobbering the per-sensor config the
        // coordinator writes from the DataCollection modules (the dashboard's read-only
        // Hz/duty source). The availability-only refresh must leave the config untouched.
        val gateway = FakeGateway(servers = mutableListOf(server()))
        val store = FakeStore(current = accelSetting)
        delegate(gateway, store).refresh()
        assertEquals("availability refresh must not write the sensor config", 0, store.saveCount)
        assertFalse("availability refresh must not clear the sensor config", store.cleared)
        assertEquals("the coordinator-owned config is left intact", accelSetting, store.current)
    }

    @Test
    fun partialAvailabilityFailureRecordsFailureAndStillSucceeds() {
        val gateway = FakeGateway(
            servers = mutableListOf(server(1L), server(2L)),
            reportSucceedsFor = { it.id != 2L }, // server 2 fails its availability report
        )
        val store = FakeStore(current = accelSetting)
        val result = delegate(gateway, store).refresh()

        assertEquals(SensorSettingsRefreshOutcome.SUCCESS, result.outcome)
        // The failed server's status is updated for visibility — never a silent skip.
        assertEquals(listOf(2L), gateway.availabilityFailuresRecorded)
    }
}
