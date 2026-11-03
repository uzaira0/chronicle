package com.openlattice.chronicle.collection.state

import android.database.sqlite.SQLiteException
import android.hardware.Sensor
import android.hardware.SensorManager
import com.openlattice.chronicle.EnrollmentTestStores
import com.openlattice.chronicle.android.AndroidDeviceSensorAvailability
import com.openlattice.chronicle.android.AndroidSensorType
import com.openlattice.chronicle.api.ChronicleStudyApi
import com.openlattice.chronicle.collection.CollectionModuleId
import com.openlattice.chronicle.data.ParticipationStatus
import com.openlattice.chronicle.preferences.EnrollmentSettings
import com.openlattice.chronicle.preferences.PARTICIPATION_STATUS
import com.openlattice.chronicle.services.sensors.ChronicleSensorSettingsGateway
import com.openlattice.chronicle.services.sensors.SensorAvailabilityReporter
import com.openlattice.chronicle.services.upload.decodedUploadBody
import com.openlattice.chronicle.services.withdrawal.WithdrawalStateStore
import com.openlattice.chronicle.storage.createUnreadableChronicleDb
import com.openlattice.chronicle.utils.Utils
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.robolectric.Shadows.shadowOf
import org.robolectric.shadows.SensorBuilder

class ResearchErasureRegressionTest : ResearchErasureTestFixture() {
    @Test fun unknownWireStatusStopsCaptureThroughTheProductionAdapterAndAdmissionGate() {
        val enrolled = enrollWithSensorAvailability(enabled = true).copy(apiKey = UUID.randomUUID().toString())
            .also { db.uploadServerDao().update(it) }
        assertEquals(enrolled.enrollmentIdentity(), requireNotNull(ResearchPersistenceGate.captureOwner(context)).enrollmentIdentity())
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody(
                """{"participationStatus":"UNRECOGNIZED_STATUS","serverTime":"2026-10-28T12:00:00Z","captureWindows":[]}"""))
            val api = Utils.createRetrofitAdapter(server.url("/").toString()).create(ChronicleStudyApi::class.java)
            val status = api.getDeviceParticipationStatus(UUID.fromString(enrolled.studyId),
                enrolled.participantId, enrolled.sourceRandomDeviceId, requireNotNull(enrolled.apiKey)).participationStatus
            assertEquals(ParticipationStatus.UNKNOWN, status)
            assertTrue(onPersistenceWorker {
                ResearchPersistenceGate.applyParticipationStatus(context, enrolled, fence.settingsGeneration(), status, emptyList())
            })
            assertEquals(status, EnrollmentSettings(context).getParticipationStatus())
            assertNull(ResearchPersistenceGate.captureOwner(context))
        }
    }

    @Test fun unavailableRoomStoreDefersServerResolutionForRecovery() {
        for (failure in listOf(SQLiteException("storage unavailable"), IllegalStateException("database closed"),
            IllegalArgumentException("unexpected DAO defect"))) {
            val unavailable = createUnreadableChronicleDb(context, failure)
            EnrollmentTestStores.db = unavailable
            try {
                if (failure is IllegalArgumentException) {
                    val propagated = assertThrows(IllegalArgumentException::class.java) { allServers(context) }
                    assertEquals(failure.message, propagated.message)
                } else {
                    assertEquals(emptyList<Any>(), allServers(context))
                }
            } finally {
                EnrollmentTestStores.db = db
                unavailable.close()
            }
        }
    }

    @Test fun ownerAdmissionAndCaptureAgreeAcrossEveryParticipationAndPersistenceState() {
        val expected = enrollWithSensorAvailability(enabled = true)
        for (status in ParticipationStatus.values()) {
            stopOnPersistenceWorker { EnrollmentSettings(context).setParticipationStatus(status) }
            ResearchPersistenceGate.withOwnerAdmission(context) { admission ->
                val captured = ResearchPersistenceGate.captureOwner(context)
                when (status) {
                    ParticipationStatus.ENROLLED -> {
                        assertEquals(ResearchPersistenceGate.OwnerAdmission.Admitted(expected), admission)
                        assertEquals(expected, captured)
                    }
                    ParticipationStatus.PAUSED, ParticipationStatus.COLLECTION_COMPLETED -> {
                        assertEquals(ResearchPersistenceGate.OwnerAdmission.Stopped(expected, status), admission)
                        assertNull(captured)
                    }
                    else -> {
                        assertEquals(ResearchPersistenceGate.OwnerAdmission.Refused.ENROLLMENT_UNAVAILABLE, admission)
                        assertNull(captured)
                    }
                }
            }
            ResearchPersistenceGate.closeForPersistenceFailure()
            ResearchPersistenceGate.withOwnerAdmission(context) {
                assertEquals(ResearchPersistenceGate.OwnerAdmission.Refused.PERSISTENCE_CLOSED, it)
                assertNull(ResearchPersistenceGate.captureOwner(context))
            }
            ResearchPersistenceGate.resetAfterLocalStoreRecovery()
        }
        stopOnPersistenceWorker { WithdrawalStateStore(context).beginWithdrawal() }
        ResearchPersistenceGate.withOwnerAdmission(context) {
            assertEquals(ResearchPersistenceGate.OwnerAdmission.Refused.ENROLLMENT_UNAVAILABLE, it)
            assertNull(ResearchPersistenceGate.captureOwner(context))
        }
        stopOnPersistenceWorker { db.uploadServerDao().delete(expected.id) }
        ResearchPersistenceGate.withOwnerAdmission(context) {
            assertEquals(ResearchPersistenceGate.OwnerAdmission.Refused.NO_OWNER, it)
            assertNull(ResearchPersistenceGate.captureOwner(context))
        }
    }

    @Test fun expectedOwnerRejectsEveryIdentityChange() {
        val expected = enrollWithSensorAvailability(enabled = true)
        assertEquals(true, ResearchPersistenceGate.runIfExpectedOwner(context, expected) { true })
        val identityChanges = listOf(
            expected.copy(studyId = UUID(0, 0).toString()),
            expected.copy(participantId = "another-participant"),
            expected.copy(sourceRandomDeviceId = "another-device"),
        )
        for (other in identityChanges + listOf(
            expected.copy(id = expected.id + 1),
            expected.copy(createdAt = "2026-01-01T00:00:00Z"),
            expected.copy(enrollmentIssuedAtEpochMillis = 1L),
        )) {
            assertNull(ResearchPersistenceGate.runIfExpectedOwner(context, other) { true })
            if (other in identityChanges) assertFalse(SensorAvailabilityReporter.checkAndReport(context, other.enrollmentIdentity()))
        }
    }

    @Test fun routingAndCredentialsDoNotChangeEnrollmentIdentity() {
        val original = owner
        db.uploadServerDao().update(original.copy(url = "https://availability.local",
            apiKey = "availability-test-credential"))
        val admitted = enrollWithSensorAvailability(enabled = true)
        assertEquals(original.enrollmentIdentity(), admitted.enrollmentIdentity())
        assertEquals(true, ResearchPersistenceGate.runIfExpectedOwner(context, original) { true })
        val gateway = ChronicleSensorSettingsGateway(context, db)
        val stale = gateway.enabledServers().single().copy(url = original.url, apiKey = original.apiKey)
        var calls = 0
        EnrollmentTestStores.apiRequests.clear()
        withStudyApi(onCall = { args ->
            assertEquals(admitted.apiKey, (args[2] as Map<*, *>)[ChronicleStudyApi.API_KEY_HEADER])
            calls++
        }) { method ->
            assertEquals("reportAndroidSensorAvailability", method)
            1
        }.use {
            assertTrue(SensorAvailabilityReporter.checkAndReport(context, original.enrollmentIdentity()))
            assertTrue(gateway.reportAvailability(stale))
            assertFalse(gateway.reportAvailability(stale.copy(identity = stale.identity.copy(participantId = "another-participant"))))
        }
        assertEquals(2, calls)
        assertEquals(2, EnrollmentTestStores.apiRequests.size)
        EnrollmentTestStores.apiRequests.forEach { request ->
            assertEquals(admitted.url, request.url)
        }
    }

    @Test fun availabilityReportHoldsTheOutboundLeaseUntilItsRequestCompletes() {
        val expected = enrollWithSensorAvailability(enabled = true)
        val atRequest = CountDownLatch(1)
        val finishRequest = CountDownLatch(1)
        val stopped = CountDownLatch(1)
        withStudyApi { method ->
            check(method == "reportAndroidSensorAvailability")
            atRequest.countDown()
            check(finishRequest.await(5, TimeUnit.SECONDS))
            1
        }.use {
            val executor = Executors.newFixedThreadPool(2)
            try {
                val report = executor.submit<Boolean> {
                    SensorAvailabilityReporter.checkAndReport(context,
                        expected.enrollmentIdentity())
                }
                assertTrue(atRequest.await(5, TimeUnit.SECONDS))
                val withdrawal = executor.submit {
                    stopOnPersistenceWorker {
                        prefs.edit().putString(PARTICIPATION_STATUS, ParticipationStatus.NOT_ENROLLED.name).commit()
                        fence.erase(CollectionModuleId.values().toSet(), durableIntent = false)
                        stopped.countDown()
                    }
                }
                assertFalse("withdrawal must wait for outbound participant data", stopped.await(150, TimeUnit.MILLISECONDS))
                finishRequest.countDown()
                assertTrue(report.get(5, TimeUnit.SECONDS))
                withdrawal.get(5, TimeUnit.SECONDS)
                assertFalse(SensorAvailabilityReporter.checkAndReport(context,
                    expected.enrollmentIdentity()))
            } finally {
                finishRequest.countDown()
                executor.shutdownNow()
                assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS))
            }
        }
    }

    @Test fun aSampleAcceptedBeforeAPauseIsStillPersistedAfterResumeWithoutAdvancingTheFloor() {
        val expected = requireNotNull(db.uploadServerDao().getConfiguredServer())
        val runtime = runtime()
        runtime.recordSample(AndroidSensorType.accelerometer, floatArrayOf(1f, 2f, 3f), 3)
        val floor = fence.floor(CollectionModuleId.USAGE_EVENTS)
        Thread.sleep(2)
        for (status in listOf(ParticipationStatus.PAUSED, ParticipationStatus.ENROLLED)) {
            assertTrue(onPersistenceWorker {
                ResearchPersistenceGate.applyParticipationStatus(context, expected, fence.settingsGeneration(), status, emptyList())
            })
        }
        assertEquals(0, runtime.bufferedCount)
        assertEquals(1, db.sensorSampleDao().count())
        assertEquals(floor, fence.floor(CollectionModuleId.USAGE_EVENTS))
    }

    @Test fun pausedAndCompletedAvailabilityReportsSkipWithoutServerFailure() {
        val expected = enrollWithSensorAvailability(enabled = true)
        var calls = 0
        withStudyApi(onCall = { calls++ }) { 1 }.use {
            for (status in listOf(ParticipationStatus.PAUSED, ParticipationStatus.COLLECTION_COMPLETED)) {
                assertTrue(onPersistenceWorker {
                    ResearchPersistenceGate.applyParticipationStatus(context, expected, fence.settingsGeneration(), status, emptyList())
                })
                assertTrue("$status availability is an intentional skip",
                    SensorAvailabilityReporter.checkAndReport(context,
                        expected.enrollmentIdentity()))
                assertEquals("$status must not submit a new availability report", 0, calls)
            }
        }
    }

    @Test fun stoppedAvailabilityCannotAcknowledgeAnotherEnrollmentOrWithdrawal() {
        val expected = enrollWithSensorAvailability(enabled = true)
        ResearchPersistenceGate.withOwnerAdmission(context) {
            assertEquals(ResearchPersistenceGate.OwnerAdmission.Admitted(expected), it)
        }
        for (status in listOf(ParticipationStatus.PAUSED, ParticipationStatus.COLLECTION_COMPLETED)) {
            assertTrue(onPersistenceWorker {
                ResearchPersistenceGate.applyParticipationStatus(context, expected, fence.settingsGeneration(), status, emptyList())
            })
            ResearchPersistenceGate.withOwnerAdmission(context) {
                assertEquals(ResearchPersistenceGate.OwnerAdmission.Stopped(expected, status), it)
            }
            assertFalse("A stop only skips observations for its own enrollment",
                SensorAvailabilityReporter.checkAndReport(context,
                    expected.copy(participantId = "another-participant").enrollmentIdentity()))
        }
        stopOnPersistenceWorker { WithdrawalStateStore(context).beginWithdrawal() }
        ResearchPersistenceGate.withOwnerAdmission(context) {
            assertEquals(ResearchPersistenceGate.OwnerAdmission.Refused.ENROLLMENT_UNAVAILABLE, it)
        }
        assertFalse("Withdrawal is a refusal, not a participation skip",
            SensorAvailabilityReporter.checkAndReport(context,
                expected.enrollmentIdentity()))
    }

    @Test fun availabilityChecksEverySensorTypeAndSkipsDisabledModule() {
        db.uploadServerDao().update(owner.copy(url = "https://availability.local"))
        val expected = enrollWithSensorAvailability(enabled = true)
        // No sensor module is enabled; the report still checks every modeled type.
        shadowOf(context.getSystemService(SensorManager::class.java)).apply {
            addSensor(SensorBuilder.newBuilder().setType(Sensor.TYPE_ACCELEROMETER).build())
            addSensor(SensorBuilder.newBuilder().setType(Sensor.TYPE_LIGHT).build())
        }
        val reports = mutableListOf<AndroidDeviceSensorAvailability>()
        withStudyApi(onCall = { args ->
            reports += decodedUploadBody<AndroidDeviceSensorAvailability>(args[3])
        }) { method ->
            check(method == "reportAndroidSensorAvailability")
            1
        }.use {
            fun report() = SensorAvailabilityReporter.checkAndReport(context, expected.enrollmentIdentity())
            assertTrue(report())
            val sent = reports.single()
            assertEquals(setOf(AndroidSensorType.accelerometer, AndroidSensorType.light), sent.availableSensors)
            assertNull(sent.screenWidthPixels)
            assertNull(sent.screenHeightPixels)
            assertNull(sent.screenDensityDpi)
            assertNull(sent.displayRotation)
            assertNull(sent.interactionPointerCaptureCapability)
            enrollWithSensorAvailability(enabled = false)
            assertTrue(report())
            assertEquals(1, reports.size)
        }
    }
}
