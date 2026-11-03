package com.openlattice.chronicle.services.sensors

import com.openlattice.chronicle.android.InteractionPointerCaptureCapability
import org.junit.Assert.assertEquals
import org.junit.Test
import com.openlattice.chronicle.collection.state.ResearchErasureTestFixture
import com.openlattice.chronicle.collection.state.ParticipantDecision
import com.openlattice.chronicle.collection.state.enrollmentIdentity
import com.openlattice.chronicle.collection.state.stopOnPersistenceWorker
import com.openlattice.chronicle.collection.state.onPersistenceWorker
import com.openlattice.chronicle.collection.CollectionModuleId
import com.openlattice.chronicle.storage.CollectionModuleStateEntity
import org.junit.Assert.assertTrue
import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorManager
import com.openlattice.chronicle.android.AndroidDeviceSensorAvailability
import com.openlattice.chronicle.android.AndroidSensorType
import com.openlattice.chronicle.collection.CollectionModuleSetting
import com.openlattice.chronicle.collection.state.EnrollmentIdentity
import com.openlattice.chronicle.serialization.ChronicleJson
import com.openlattice.chronicle.services.upload.CollectedUploadBody
import java.io.IOException
import org.junit.Assert.assertFalse
import org.robolectric.Shadows.shadowOf
import org.robolectric.shadows.SensorBuilder

class SensorAvailabilityReporterTest : ResearchErasureTestFixture() {
    @Test fun requiredDeclineSkipsAvailabilityWithoutCallingTheServer() {
        enrollWithSensorAvailability(enabled = true)
        val module = CollectionModuleId.BATTERY_TELEMETRY
        stopOnPersistenceWorker {
            db.collectionModuleStateDao().upsertAll(listOf(CollectionModuleStateEntity(
                module.id, true, ParticipantDecision.DECLINED.name, 0, true, 1)))
        }
        withStudyApi { error("A halted enrollment must not report availability") }.use {
            assertTrue(onPersistenceWorker {
                SensorAvailabilityReporter.checkAndReport(context, owner.enrollmentIdentity())
            })
        }
    }
    @Test
    fun preAndroid14ReportsPointerAPIUnavailable() {
        assertEquals(
            InteractionPointerCaptureCapability.PLATFORM_API_UNAVAILABLE,
            SensorAvailabilityReporter.interactionPointerCapabilityFor(33),
        )
    }

    @Test
    fun android14AndLaterReportThatPointerCaptureRequiresInterception() {
        assertEquals(
            InteractionPointerCaptureCapability.REQUIRES_INPUT_INTERCEPTION,
            SensorAvailabilityReporter.interactionPointerCapabilityFor(34),
        )
        assertEquals(
            InteractionPointerCaptureCapability.REQUIRES_INPUT_INTERCEPTION,
            SensorAvailabilityReporter.interactionPointerCapabilityFor(36),
        )
    }

    /** The pilot study: usage required, the rest optional, no sensor module at all. */
    private val pilot = listOf(CollectionModuleId.USAGE_EVENTS, CollectionModuleId.IN_APP_ACTIVITY_CLASS,
        CollectionModuleId.DEVICE_LIFECYCLE, CollectionModuleId.USER_IDENTIFICATION, CollectionModuleId.UPLOAD_TELEMETRY,
        CollectionModuleId.BATTERY_TELEMETRY).associateWith {
        CollectionModuleSetting(enabled = true, required = it == CollectionModuleId.USAGE_EVENTS)
    }

    private fun installSensors(vararg androidTypes: Int) {
        val manager = shadowOf(context.getSystemService(Context.SENSOR_SERVICE) as SensorManager)
        androidTypes.forEach { manager.addSensor(SensorBuilder.newBuilder().setType(it).build()) }
    }

    /** Reports once through the production reporter; returns its result and the decoded bodies it posted. */
    private fun report(expected: EnrollmentIdentity = owner.enrollmentIdentity(),
                       answer: () -> Any = { 1 }): Pair<Boolean, List<AndroidDeviceSensorAvailability>> {
        val sent = mutableListOf<AndroidDeviceSensorAvailability>()
        val result = withStudyApi(onCall = { args ->
            val json = (args[3] as CollectedUploadBody<*>).json()
            assertTrue(json, json.contains("\"availableSensors\""))
            sent += ChronicleJson.moshi.adapter(AndroidDeviceSensorAvailability::class.java).fromJson(json)!!
        }) { method -> check(method == "reportAndroidSensorAvailability") { method }; answer() }.use {
            onPersistenceWorker { SensorAvailabilityReporter.checkAndReport(context, expected) }
        }
        return result to sent
    }

    /**
     * A Pilot phone collects no sensor, yet the report lists exactly the modeled sensors the device has: here
     * accelerometer, light and step counter; heart rate is present but not modeled, gyroscope is modeled but absent.
     */
    @Test fun pilotReportListsExactlyThePresentModeledSensors() {
        enrollWithSensorAvailability(enabled = true, modules = pilot)
        installSensors(Sensor.TYPE_ACCELEROMETER, Sensor.TYPE_LIGHT, Sensor.TYPE_STEP_COUNTER, Sensor.TYPE_HEART_RATE)
        val (ok, sent) = report()
        assertTrue(ok)
        assertEquals(listOf(setOf(AndroidSensorType.accelerometer, AndroidSensorType.light, AndroidSensorType.stepCounter)),
            sent.map { it.availableSensors })
    }

    @Test fun aDeviceWithoutModeledSensorsStillReportsAnEmptyList() {
        enrollWithSensorAvailability(enabled = true, modules = pilot)
        val (ok, sent) = report()
        assertTrue(ok)
        assertEquals(listOf(emptySet<AndroidSensorType>()), sent.map { it.availableSensors })
    }

    @Test fun aFailedReportIsSurfacedAndTheRetrySendsTheSameList() {
        enrollWithSensorAvailability(enabled = true, modules = pilot)
        installSensors(Sensor.TYPE_PROXIMITY)
        val (failed, first) = report { throw IOException("offline") }
        assertFalse("a failed POST is reported to the caller for retry", failed)
        val (ok, retried) = report()
        assertTrue(ok)
        assertEquals(first.map { it.availableSensors }, retried.map { it.availableSensors })
        assertEquals(setOf(AndroidSensorType.proximity), retried.single().availableSensors)
    }

    @Test fun availabilityTurnedOffSendsNothing() {
        enrollWithSensorAvailability(enabled = false, modules = pilot)
        installSensors(Sensor.TYPE_ACCELEROMETER)
        val (ok, sent) = report { error("availability is off for this study") }
        assertTrue(ok)
        assertEquals(emptyList<AndroidDeviceSensorAvailability>(), sent)
    }

    @Test fun aReportForAReplacedEnrollmentSendsNothing() {
        enrollWithSensorAvailability(enabled = true, modules = pilot)
        installSensors(Sensor.TYPE_ACCELEROMETER)
        val stale = owner.copy(participantId = "participant-replaced").enrollmentIdentity()
        val (ok, sent) = report(stale) { error("a replaced enrollment must not report") }
        assertFalse(ok)
        assertEquals(emptyList<AndroidDeviceSensorAvailability>(), sent)
    }
}
