package com.openlattice.chronicle.services.enrollment

import android.content.Context
import android.util.Log
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequest
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.Worker
import androidx.work.WorkerParameters
import com.openlattice.chronicle.api.ChronicleStudyApi
import com.openlattice.chronicle.constants.TelemetryEvents
import com.openlattice.chronicle.collection.state.ResearchPersistenceGate
import com.openlattice.chronicle.data.ParticipationStatus
import com.openlattice.chronicle.preferences.EnrollmentSettings
import com.openlattice.chronicle.serialization.ChronicleCallException
import com.openlattice.chronicle.storage.AUTH_MODE_API_KEY
import com.openlattice.chronicle.services.upload.UploadWorker
import com.openlattice.chronicle.services.upload.completeServerForIdentity
import com.openlattice.chronicle.telemetry.LocalTelemetry
import java.util.UUID
import java.util.concurrent.TimeUnit

private const val ENROLLMENT_MONITOR_INTERVAL_MIN = 15L
private const val UNIQUE_WORK_NAME = "enrollment_monitor"

private val TAG = EnrollmentMonitoringWorker::class.java.simpleName

/**
 * Periodically refreshes [ParticipationStatus] from the Chronicle API and persists it in [EnrollmentSettings].
 *
 * This worker intentionally has a narrow responsibility: fetch + persist status.
 */
class EnrollmentMonitoringWorker(
    context: Context,
    workerParameters: WorkerParameters
) : com.openlattice.chronicle.security.LeaseBoundWorker(context, workerParameters) {

    private lateinit var settings: EnrollmentSettings
    private lateinit var studyId: UUID
    private lateinit var participantId: String

    override fun runWork(): Result {
        return try {
            // Runs while PAUSED too: this worker is how a paused device learns it was resumed.
            if (!ResearchPersistenceGate.isEnrolledIgnoringStatus(applicationContext)) {
                Log.i(TAG, "Skipping enrollment monitoring outside an active enrollment")
                return Result.success()
            }
            settings = EnrollmentSettings(applicationContext)
            studyId = settings.getStudyId()
            participantId = settings.getParticipantId()

            val db = com.openlattice.chronicle.storage.ChronicleDb.getInstance(applicationContext)
            val server = completeServerForIdentity(
                db.uploadServerDao().getConfiguredServer(),
                studyId,
                participantId,
            )
            if (server == null) {
                Log.w(TAG, "Skipping enrollment monitoring without a complete matching study server")
                LocalTelemetry.logEvent(TelemetryEvents.ENROLLMENT_MONITOR_FAILURE, null)
                return Result.failure()
            }
            val chronicleApi = UploadWorker.getChronicleStudyApi(
                server.url,
                server.mobileSigningSecretOverride,
            )

            val generation = com.openlattice.chronicle.collection.state.ResearchErasureFence(applicationContext).settingsGeneration()
            val participationStatus = if (server.authMode == AUTH_MODE_API_KEY) {
                try {
                    chronicleApi.getDeviceParticipationStatus(
                        studyId,
                        participantId,
                        server.sourceDeviceId,
                        requireNotNull(server.apiKey) { "API-key enrollment is missing its credential" },
                    )
                } catch (error: ChronicleCallException) {
                    // Servers before the device status endpoint: keep collecting, as before.
                    if (error.code != 404) throw error
                    ParticipationStatus.ENROLLED
                }
            } else {
                chronicleApi.getParticipationStatus(studyId, participantId) ?: ParticipationStatus.UNKNOWN
            }

            ResearchPersistenceGate.applyParticipationStatus(applicationContext, server, generation, participationStatus)

            Log.i(TAG, "Updated participation status: $participationStatus")
            LocalTelemetry.logEvent(TelemetryEvents.ENROLLMENT_MONITOR_SUCCESS, null)
            Result.success()
        } catch (e: Exception) {
            Log.i(TAG, "Enrollment monitoring failed", e)
            LocalTelemetry.recordException(e)
            LocalTelemetry.logEvent(TelemetryEvents.ENROLLMENT_MONITOR_FAILURE, null)
            Result.failure()
        }
    }

}

fun scheduleEnrollmentMonitoringWork(context: Context) {
    val workRequest: PeriodicWorkRequest =
        PeriodicWorkRequestBuilder<EnrollmentMonitoringWorker>(
            ENROLLMENT_MONITOR_INTERVAL_MIN,
            TimeUnit.MINUTES
        ).build()

    WorkManager.getInstance(context).enqueueUniquePeriodicWork(
        UNIQUE_WORK_NAME,
        ExistingPeriodicWorkPolicy.REPLACE,
        workRequest
    )
}
