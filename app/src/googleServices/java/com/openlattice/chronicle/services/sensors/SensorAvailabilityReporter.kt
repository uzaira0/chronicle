package com.openlattice.chronicle.services.sensors

import android.content.Context
import android.hardware.SensorManager
import android.os.Build
import android.util.Log
import com.openlattice.chronicle.android.AndroidDeviceSensorAvailability
import com.openlattice.chronicle.android.AndroidSensorType
import com.openlattice.chronicle.android.InteractionPointerCaptureCapability
import com.openlattice.chronicle.api.CollectedUploadHeaders
import com.openlattice.chronicle.collection.CollectionModuleId
import com.openlattice.chronicle.collection.capability.DistributionModulePolicy
import com.openlattice.chronicle.collection.state.EnrollmentIdentity
import com.openlattice.chronicle.collection.state.ResearchPersistenceGate
import com.openlattice.chronicle.collection.state.CollectionLoopStore
import com.openlattice.chronicle.collection.state.CollectionModuleState
import com.openlattice.chronicle.collection.state.ResearchPersistenceGate.OwnerAdmission
import com.openlattice.chronicle.collection.state.enrollmentIdentity
import com.openlattice.chronicle.preferences.AppliedOperationalModules
import com.openlattice.chronicle.preferences.configuredStudyModuleEnabled
import com.openlattice.chronicle.sensors.SensorTypeMapping
import com.openlattice.chronicle.services.upload.UploadWorker
import com.openlattice.chronicle.services.upload.CollectedUploadBody
import com.openlattice.chronicle.services.upload.LocalUploadDiagnosticsStore
import com.openlattice.chronicle.services.upload.LocalUploadModuleFamily
import com.openlattice.chronicle.services.upload.LocalOperationalIssue
import com.openlattice.chronicle.storage.UploadServerEntity
import java.util.UUID

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
     * @return true if the report was acknowledged or intentionally skipped,
     *   false on any failure (network, auth, server error). Callers should treat
     *   false as a transient signal and may persist the failure for visibility.
     */
    internal fun checkAndReport(context: Context, expected: EnrollmentIdentity): Boolean = try {
        ResearchPersistenceGate.withOwnerAdmission(context) { admission ->
            val owner = when (admission) {
                is OwnerAdmission.Admitted -> admission.owner
                is OwnerAdmission.Stopped -> admission.owner
                is OwnerAdmission.Refused -> return@withOwnerAdmission false
            }
            if (owner.enrollmentIdentity() != expected) return@withOwnerAdmission false
            if (admission is OwnerAdmission.Stopped) return@withOwnerAdmission true
            if (CollectionModuleState.collectionHalted(CollectionLoopStore.of(context).loadAll().values)) {
                return@withOwnerAdmission true
            }
            reportAdmittedAvailability(context, owner)
        }
    } catch (e: Exception) {
        Log.w(TAG, "Failed to report sensor availability", e)
        false
    }

    private fun reportAdmittedAvailability(context: Context, owner: UploadServerEntity): Boolean {
        val studyId = UUID.fromString(owner.studyId)
        // Not consent-gated, so it has no decision row: the enrollment's study settings decide.
        if (!DistributionModulePolicy.supports(CollectionModuleId.SENSOR_AVAILABILITY) ||
            !configuredStudyModuleEnabled(owner, studyId, owner.participantId, CollectionModuleId.SENSOR_AVAILABILITY,
                appliedOperationalModules = AppliedOperationalModules.load(context, owner))) {
            return true
        }
        val sensorManager = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
        // Every modeled sensor type is checked whether or not a sensor module is enabled; one list: what the device has.
        val availability = AndroidDeviceSensorAvailability(availableSensors = AndroidSensorType.entries.filterTo(mutableSetOf()) {
            sensorManager.getDefaultSensor(SensorTypeMapping.toAndroidType(it)) != null
        })

        val studyApi = UploadWorker.getChronicleStudyApi(
            owner.url
        )
        val body = CollectedUploadBody.encodeSnapshot(availability, AndroidDeviceSensorAvailability::class.java)
        if (body == null) {
            LocalUploadDiagnosticsStore.of(context).recordOperational(
                LocalUploadModuleFamily.SENSOR, LocalOperationalIssue.SAMPLE_QUARANTINED)
            return true
        }
        studyApi.reportAndroidSensorAvailability(studyId, owner.participantId,
            CollectedUploadHeaders(owner.sourceRandomDeviceId, owner.apiKey).toMap(), body)
        Log.i(TAG, "Sensor availability reported")
        return true
    }

}
