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
) : Worker(context, workerParameters) {

    private lateinit var settings: EnrollmentSettings
    private lateinit var studyId: UUID
    private lateinit var participantId: String

    override fun doWork(): Result {
        return try {
            if (!ResearchPersistenceGate.isActiveEnrollment(applicationContext)) {
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
            if (!ResearchPersistenceGate.isActiveEnrollment(applicationContext)) {
                return Result.success()
            }
            if (server.authMode == AUTH_MODE_API_KEY) {
                persistStatusIfSameActiveEnrollment(ParticipationStatus.ENROLLED)
                Log.i(TAG, "Skipping legacy participation status endpoint for API-key enrollment")
                LocalTelemetry.logEvent(TelemetryEvents.ENROLLMENT_MONITOR_SUCCESS, null)
                return Result.success()
            }

            val chronicleApi = UploadWorker.getChronicleStudyApi(
                server.url,
                server.mobileSigningSecretOverride,
            )

            val participationStatus =
                chronicleApi.getParticipationStatus(studyId, participantId) ?: ParticipationStatus.UNKNOWN

            persistStatusIfSameActiveEnrollment(participationStatus)

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

    /** Serializes the final identity/status mutation against withdrawal's stop barrier. */
    private fun persistStatusIfSameActiveEnrollment(status: ParticipationStatus): Boolean =
        ResearchPersistenceGate.runIfActive(applicationContext) {
            val current = EnrollmentSettings(applicationContext)
            check(current.getStudyId() == studyId && current.getParticipantId() == participantId) {
                "Enrollment identity changed during status refresh"
            }
            current.setParticipationStatus(status)
            true
        } == true
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
