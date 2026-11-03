package com.openlattice.chronicle.collection.state

import com.openlattice.chronicle.collection.BatteryChargingState
import com.openlattice.chronicle.collection.BatteryHealth
import com.openlattice.chronicle.collection.BatteryPlugType
import com.openlattice.chronicle.collection.CollectionModuleId
import com.openlattice.chronicle.collection.HealthMetricType
import com.openlattice.chronicle.collection.NetworkTransport
import com.openlattice.chronicle.collection.battery.BatteryReading
import com.openlattice.chronicle.collection.battery.BatterySampleSource
import com.openlattice.chronicle.collection.battery.BatteryTelemetryCollectionModule
import com.openlattice.chronicle.collection.core.ModuleResult
import com.openlattice.chronicle.collection.device.AndroidDeviceSettingsSource
import com.openlattice.chronicle.collection.device.ConnectivityStateCollectionModule
import com.openlattice.chronicle.collection.device.ConnectivityStateReading
import com.openlattice.chronicle.collection.device.ConnectivityStateSource
import com.openlattice.chronicle.collection.device.DeviceSettingsCollectionModule
import com.openlattice.chronicle.collection.device.DeviceSettingsSource
import com.openlattice.chronicle.collection.device.HealthMetricCollectionModule
import com.openlattice.chronicle.collection.device.HealthMetricReading
import com.openlattice.chronicle.collection.device.HealthMetricSource
import com.openlattice.chronicle.collection.sink.BatterySampleSink
import com.openlattice.chronicle.collection.sink.ConnectivityStateSampleSink
import com.openlattice.chronicle.collection.sink.DeviceSettingsSampleSink
import com.openlattice.chronicle.collection.sink.HealthMetricSampleSink
import com.openlattice.chronicle.collection.usage.DaoUsagePollCheckpointStore
import com.openlattice.chronicle.collection.usage.UsageModulePersistence
import com.openlattice.chronicle.models.ExtractedUsageEvent
import com.openlattice.chronicle.preferences.PARTICIPANT_ID
import com.openlattice.chronicle.serialization.JsonSerializer
import com.openlattice.chronicle.storage.healthMetricSampleDao
import java.time.OffsetDateTime
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ResearchCaptureErasureTest : ResearchErasureTestFixture() {
    @Test fun everyProducerFamilyRejectsCapturedAdmissionAfterDiscardAndReacceptance() {
        val families = listOf(CollectionModuleId.BATTERY_TELEMETRY, CollectionModuleId.CONNECTIVITY_STATE,
            CollectionModuleId.DEVICE_SETTINGS, CollectionModuleId.HEALTH_CONNECT, CollectionModuleId.USAGE_EVENTS,
            CollectionModuleId.SENSOR_ACCELEROMETER, CollectionModuleId.SLEEP, CollectionModuleId.ACTIVITY_RECOGNITION,
            CollectionModuleId.NOTIFICATION_ACTIVITY, CollectionModuleId.INTERACTION_EVENTS, CollectionModuleId.DEVICE_LIFECYCLE)
        families.forEach { module ->
            val origin = ResearchPersistenceGate.captureObservation(context, module)
            eraseAndReaccept(module)
            assertFalse(module.id, origin.persist { db.queueEntryData().insertEntry(row()) })
        }
        assertEquals(0, db.queueEntryData().getSize())
    }

    @Test fun batteryAcquisitionSuspendedThroughErasureCannotRecreateItsRow() {
        val source = proxy(BatterySampleSource::class.java) {
            eraseAndReaccept(CollectionModuleId.BATTERY_TELEMETRY)
            BatteryReading(72, BatteryChargingState.DISCHARGING, BatteryPlugType.UNPLUGGED, 305, 4050, BatteryHealth.GOOD)
        }
        val module = BatteryTelemetryCollectionModule(BatterySampleSink(db.batterySampleDao(),
            ResearchPersistenceGate.guard(context, CollectionModuleId.BATTERY_TELEMETRY)), source, { true })
        assertTrue(module.sample() is ModuleResult.Skipped)
        assertEquals(0, db.batterySampleDao().count())
    }

    @Test fun otherPullFamiliesRejectErasureDuringAcquisition() {
        val connectivity = ConnectivityStateCollectionModule(ConnectivityStateSampleSink(db.connectivityStateSampleDao(),
            ResearchPersistenceGate.guard(context, CollectionModuleId.CONNECTIVITY_STATE)),
            proxy(ConnectivityStateSource::class.java) {
                eraseAndReaccept(CollectionModuleId.CONNECTIVITY_STATE)
                ConnectivityStateReading(NetworkTransport.WIFI, true, false, true)
            }, { true })
        val reading = AndroidDeviceSettingsSource(context).read()
        val settings = DeviceSettingsCollectionModule(DeviceSettingsSampleSink(db.deviceSettingsSampleDao(),
            ResearchPersistenceGate.guard(context, CollectionModuleId.DEVICE_SETTINGS)), proxy(DeviceSettingsSource::class.java) {
                eraseAndReaccept(CollectionModuleId.DEVICE_SETTINGS); reading
            }, { true })
        val health = HealthMetricCollectionModule(HealthMetricSampleSink(db.healthMetricSampleDao(),
            ResearchPersistenceGate.guard(context, CollectionModuleId.HEALTH_CONNECT)), proxy(HealthMetricSource::class.java) {
                eraseAndReaccept(CollectionModuleId.HEALTH_CONNECT)
                listOf(HealthMetricReading(HealthMetricType.STEPS, 42.0, "count", 1, 2, null))
            }, { true }, persistLease = { it() })
        val results = listOf(connectivity.sample(), settings.sample(), health.sample())
        assertTrue(results.toString(), results.all { it is ModuleResult.Skipped })
        assertEquals(0, db.connectivityStateSampleDao().count())
        assertEquals(0, db.deviceSettingsSampleDao().count())
        assertEquals(0, db.healthMetricSampleDao().count())
    }

    @Test fun capturedProducerAdmissionsCannotEnterAReplacementEnrollmentWithoutEpochReuse() {
        val admissions = CollectionModuleId.values().filter { it.active }.associateWith {
            ResearchPersistenceGate.captureObservation(context, it)
        }
        val previous = owner
        stopOnPersistenceWorker {
            db.uploadServerDao().delete(previous.id)
            db.uploadServerDao().insert(previous.copy(id = 0, participantId = "participant-B", createdAt = OffsetDateTime.now().toString()))
            prefs.edit().putString(PARTICIPANT_ID, "participant-B").commit()
        }
        admissions.forEach { (module, admission) -> assertFalse(module.id, admission.persist { db.queueEntryData().insertEntry(row()) }) }
        assertEquals(0, db.queueEntryData().getSize())
    }

    @Test fun usageReadStartsAtTheNewAcceptanceAndRefusalCannotResurrectItsOldCheckpoint() {
        val module = CollectionModuleId.USAGE_EVENTS
        val oldScope = ResearchPersistenceGate.observationScope(context, module)!!
        val oldCheckpoint = DaoUsagePollCheckpointStore(db.usagePollCheckpointDao(), oldScope.key, oldScope.floorMillis)
        oldCheckpoint.commitPollTimestamp(10)
        val oldSink = ResearchPersistenceGate.usageSink(context)
        eraseAndReaccept(module)
        val scope = ResearchPersistenceGate.observationScope(context, module)!!
        val checkpoint = DaoUsagePollCheckpointStore(db.usagePollCheckpointDao(), scope.key, scope.floorMillis)
        assertEquals(fence.floor(module), checkpoint.readPreviousPollTimestamp())
        assertFalse(UsageModulePersistence.persist(listOf(row()), 100, oldSink, oldCheckpoint::commitPollTimestamp,
            { db.runInTransaction(it) }))
        assertEquals(fence.floor(module), checkpoint.readPreviousPollTimestamp())
        assertEquals(0, db.queueEntryData().getSize())
    }

    @Test fun temporaryUsageClosureRetainsWindowAndReacceptanceKeepsItsEpochFloor() {
        val module = CollectionModuleId.USAGE_EVENTS
        stopOnPersistenceWorker {
            fence.erase(setOf(module), durableIntent = false, now = 500)
            fence.accepted(setOf(module), initialReview = false, now = 1_000)
        }
        val scope = ResearchPersistenceGate.observationScope(context, module)!!
        val checkpoint = DaoUsagePollCheckpointStore(db.usagePollCheckpointDao(), scope.key, scope.floorMillis)
        checkpoint.commitPollTimestamp(2_000)
        val captured = ResearchPersistenceGate.usageSink(context)
        consent(module, ParticipantDecision.ACCEPTED, enabled = false)
        assertFalse(UsageModulePersistence.persist(listOf(row()), 3_000, captured, checkpoint::commitPollTimestamp,
            { db.runInTransaction(it) }))
        assertEquals(2_000L, checkpoint.readPreviousPollTimestamp())
        stopOnPersistenceWorker { fence.accepted(setOf(module), initialReview = false, now = 4_000) }
        consent(module, ParticipantDecision.ACCEPTED)
        assertEquals(1_000L, fence.floor(module))
        assertTrue(UsageModulePersistence.persist(listOf(row()), 3_000, captured, checkpoint::commitPollTimestamp,
            { db.runInTransaction(it) }))
        assertEquals(3_000L, checkpoint.readPreviousPollTimestamp())
    }

    @Test fun finalUsageInsertionRedactsOptionalFieldsCapturedBeforeTheirErasure() {
        val captured = ResearchPersistenceGate.usageSink(context)
        val observed = row()
        eraseAndReaccept(CollectionModuleId.IN_APP_ACTIVITY_CLASS)
        eraseAndReaccept(CollectionModuleId.USER_IDENTIFICATION)
        assertEquals(ModuleResult.Ok(1), captured.write(listOf(observed)))
        val stored = JsonSerializer.deserializeQueueEntry(db.queueEntryData().getEntriesAfter(Long.MIN_VALUE, Long.MIN_VALUE, 10).single().data)
            .single() as ExtractedUsageEvent
        assertNull(stored.activityClass)
        assertEquals("", stored.user)
    }
}
