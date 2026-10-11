package com.openlattice.chronicle.services.sensors

import android.content.Context
import android.hardware.SensorManager
import android.os.Build
import android.util.Log
import com.openlattice.chronicle.android.AndroidDeviceSensorAvailability
import com.openlattice.chronicle.android.AndroidSensorType
import com.openlattice.chronicle.android.InteractionPointerCaptureCapability
import com.openlattice.chronicle.sensors.SensorTypeMapping
import com.openlattice.chronicle.collection.CollectionModuleId
import com.openlattice.chronicle.collection.state.CollectionLoopStore
import java.util.*

private val TAG = SensorAvailabilityReporter::class.java.simpleName

object SensorAvailabilityReporter {

    /** Exact pointer coordinates cannot be collected passively on any supported Android API. */
    internal fun interactionPointerCapabilityFor(sdkInt: Int): InteractionPointerCaptureCapability =
        if (sdkInt >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            InteractionPointerCaptureCapability.REQUIRES_INPUT_INTERCEPTION
        } else {
            InteractionPointerCaptureCapability.PLATFORM_API_UNAVAILABLE
        }

    /**
     * @return true if the report was sent and acknowledged by the server,
     *   false on any failure (network, auth, server error). Callers should treat
     *   false as a transient signal and may persist the failure for visibility.
     */
    fun checkAndReport(
        context: Context,
        studyId: UUID,
        participantId: String,
        deviceId: String,
        apiKey: String?,
        requestedSensors: Set<AndroidSensorType>,
        serverUrl: String,
        mobileSigningSecretOverride: String? = null
    ): Boolean {
        val gate = com.openlattice.chronicle.collection.state.ResearchPersistenceGate
        val expected = gate.captureOwner(context)?.takeIf {
            it.studyId == studyId.toString() && it.participantId == participantId && it.sourceDeviceId == deviceId &&
                it.url == serverUrl && it.apiKey == apiKey && it.mobileSigningSecretOverride == mobileSigningSecretOverride
        } ?: return false
        return try {
            gate.runIfExpectedOwner(context, expected) {
            if (!CollectionLoopStore.of(context).collects(CollectionModuleId.SENSOR_AVAILABILITY)) {
                return@runIfExpectedOwner true
            }
            val sensorManager = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
            val available = mutableSetOf<AndroidSensorType>()
            val unavailable = mutableSetOf<AndroidSensorType>()

            for (sensorType in requestedSensors) {
                val androidType = SensorTypeMapping.toAndroidType(sensorType)
                val sensor = sensorManager.getDefaultSensor(androidType)
                if (sensor != null) {
                    available.add(sensorType)
                } else {
                    unavailable.add(sensorType)
                }
            }

            val availability = AndroidDeviceSensorAvailability(
                availableSensors = available,
                unavailableSensors = unavailable,
            )

            val studyApi = com.openlattice.chronicle.services.upload.UploadWorker.getChronicleStudyApi(
                serverUrl,
                mobileSigningSecretOverride
            )
            val result = studyApi.reportAndroidSensorAvailability(
                studyId, participantId, deviceId, apiKey, availability
            )

            Log.i(
                TAG,
                "Reported sensor availability: requested=$requestedSensors, available=$available, unavailable=$unavailable (result=$result)"
            )
            true
            } ?: false
        } catch (e: Exception) {
            Log.w(TAG, "Failed to report sensor availability", e)
            false
        }
    }
}
