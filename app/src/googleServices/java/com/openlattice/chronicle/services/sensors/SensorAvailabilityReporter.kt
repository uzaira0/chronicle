package com.openlattice.chronicle.services.sensors

import android.content.Context
import android.hardware.SensorManager
import android.os.Build
import android.util.Log
import com.openlattice.chronicle.android.AndroidDeviceSensorAvailability
import com.openlattice.chronicle.android.AndroidSensorType
import com.openlattice.chronicle.android.InteractionPointerCaptureCapability
import com.openlattice.chronicle.sensors.SensorTypeMapping
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
        return try {
            val sensorManager = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
            val available = mutableSetOf<AndroidSensorType>()
            val unavailable = mutableSetOf<AndroidSensorType>()

            // Report every Chronicle-modeled Android sensor, not only the sensors
            // currently enabled by study settings. This keeps collection selective
            // while making device capability gaps visible to operators.
            val inventorySensors = AndroidSensorType.values().toSet()
            for (sensorType in inventorySensors) {
                val androidType = SensorTypeMapping.toAndroidType(sensorType)
                val sensor = sensorManager.getDefaultSensor(androidType)
                if (sensor != null) {
                    available.add(sensorType)
                } else {
                    unavailable.add(sensorType)
                }
            }

            // Static display context — captured here (a general, always-on device-capability
            // report) so screen resolution + density + rotation are recorded at least once even
            // when no pixel-capturing module (interaction_events) is enabled. This anchors raw
            // pixel coordinates and orientation signals for interpretation.
            val metrics = context.resources.displayMetrics
            // DisplayManager.getDisplay(DEFAULT_DISPLAY) works from any context, including the
            // worker/non-visual context this reporter runs off — where Context.getDisplay()
            // throws UnsupportedOperationException — so the rotation is actually captured at
            // least once rather than always coming back null.
            val displayRotation = runCatching {
                (context.getSystemService(Context.DISPLAY_SERVICE) as? android.hardware.display.DisplayManager)
                    ?.getDisplay(android.view.Display.DEFAULT_DISPLAY)?.rotation
            }.getOrNull()

            val availability = AndroidDeviceSensorAvailability(
                availableSensors = available,
                unavailableSensors = unavailable,
                screenWidthPixels = metrics.widthPixels,
                screenHeightPixels = metrics.heightPixels,
                screenDensityDpi = metrics.densityDpi,
                displayRotation = displayRotation,
                interactionPointerCaptureCapability = interactionPointerCapabilityFor(Build.VERSION.SDK_INT),
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
        } catch (e: Exception) {
            Log.w(TAG, "Failed to report sensor availability", e)
            false
        }
    }
}
