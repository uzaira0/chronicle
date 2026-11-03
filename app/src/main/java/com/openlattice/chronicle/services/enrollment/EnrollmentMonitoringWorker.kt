package com.openlattice.chronicle.services.enrollment

import android.content.Context
import android.os.SystemClock
import android.util.Log
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequest
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.openlattice.chronicle.collection.state.ResearchErasureFence
import com.openlattice.chronicle.collection.state.ResearchPersistenceGate
import com.openlattice.chronicle.constants.TelemetryEvents
import com.openlattice.chronicle.data.ParticipationStatus
import com.openlattice.chronicle.preferences.EnrollmentSettings
import com.openlattice.chronicle.security.LeaseBoundWorker
import com.openlattice.chronicle.serialization.ChronicleCallException
import com.openlattice.chronicle.services.upload.LocalUploadDiagnosticsStore
import com.openlattice.chronicle.services.upload.LocalUploadModuleFamily
import com.openlattice.chronicle.services.upload.UploadWorker
import com.openlattice.chronicle.services.upload.completeServerForIdentity
import com.openlattice.chronicle.services.usage.collectUsage
import com.openlattice.chronicle.services.withdrawal.ParticipantWithdrawalManager
import com.openlattice.chronicle.services.withdrawal.WithdrawalState
import com.openlattice.chronicle.services.withdrawal.isEnrollmentCancelledRefusal
import com.openlattice.chronicle.storage.ChronicleDb
import com.openlattice.chronicle.storage.UploadServerEntity
import com.openlattice.chronicle.telemetry.LocalTelemetry
import com.openlattice.chronicle.time.ServerClock
import java.util.concurrent.TimeUnit

private const val ENROLLMENT_MONITOR_INTERVAL_MIN = 15L
internal const val ENROLLMENT_MONITOR_WORK_NAME = "enrollment_monitor"
internal const val ENROLLMENT_REFRESH_WORK_NAME = "enrollment_monitor_now"
private val FLUSHED_STOPS = setOf(ParticipationStatus.PAUSED, ParticipationStatus.COLLECTION_COMPLETED)

private val TAG = EnrollmentMonitoringWorker::class.java.simpleName

/**
 * Periodically refreshes [ParticipationStatus] from the Chronicle API and persists it in [EnrollmentSettings].
 *
 * Terminal erasure hands off to the same durable local cleanup used for withdrawal.
 */
class EnrollmentMonitoringWorker(
    context: Context,
    workerParameters: WorkerParameters
) : LeaseBoundWorker(context, workerParameters) {

    override fun runWork(): Result {
        // Bound before the request, so a failure is recorded for the enrollment that sent it.
        val diagnostics = runCatching { LocalUploadDiagnosticsStore.of(applicationContext) }.getOrNull()
        return try {
            when (pollParticipationStatus(applicationContext)) {
                StatusPoll.NO_ENROLLMENT -> Result.success()
                // The settings or the owner changed while the request was in flight; the next poll applies.
                StatusPoll.SKIPPED -> Result.retry()
                StatusPoll.NO_SERVER -> {
                    LocalTelemetry.logEvent(TelemetryEvents.ENROLLMENT_MONITOR_FAILURE, null)
                    Result.failure()
                }
                StatusPoll.APPLIED -> {
                    LocalTelemetry.logEvent(TelemetryEvents.ENROLLMENT_MONITOR_SUCCESS, null)
                    Result.success()
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Enrollment monitoring failed: ${e.javaClass.simpleName}")
            // A refused or unreachable status check is visible to the study, not only in logcat.
            runCatching { diagnostics?.recordFailure(LocalUploadModuleFamily.APP_RUNTIME, e) }
                .onFailure { Log.e(TAG, "Unable to record the monitoring failure: ${it.javaClass.simpleName}") }
            LocalTelemetry.recordException(e)
            LocalTelemetry.logEvent(TelemetryEvents.ENROLLMENT_MONITOR_FAILURE, null)
            Result.failure()
        }
    }

}

/** [SKIPPED]: the apply was refused (settings generation or owner changed in flight), so nothing was applied. */
internal enum class StatusPoll { APPLIED, SKIPPED, NO_ENROLLMENT, NO_SERVER }

private fun pollResult(applied: Boolean) = if (applied) StatusPoll.APPLIED else StatusPoll.SKIPPED

/**
 * The status step, shared by this monitor and every upload worker (runEnrolledUpload runs it first):
 * poll the server, measure its clock, then apply the capture windows and the status. It is the one owner of the
 * status apply, since only its response carries the windows. A failed request runs [onFailure], then throws. Polls
 * run one at a time, so responses apply in the order they were sent (an older response never lands after a newer
 * one), and [onFailure] runs inside the same turn (two refusals never count at once).
 */
internal fun pollParticipationStatus(context: Context, onFailure: (Exception) -> Unit = {}): StatusPoll =
    synchronized(STATUS_POLL_LOCK) {
        runCatching { pollAndApply(context) }.onFailure { if (it is Exception) onFailure(it) }.getOrThrow()
    }

private val STATUS_POLL_LOCK = Any()

private fun pollAndApply(context: Context): StatusPoll {
    // Runs while PAUSED too: this is how a paused device learns it was resumed.
    if (!ResearchPersistenceGate.isEnrolledIgnoringStatus(context)) {
        Log.i(TAG, "Skipping enrollment monitoring outside an active enrollment")
        return StatusPoll.NO_ENROLLMENT
    }
    val settings = EnrollmentSettings(context)
    val studyId = settings.getStudyId()
    val participantId = settings.getParticipantId()
    val server = completeServerForIdentity(ChronicleDb.getInstance(context).uploadServerDao().getConfiguredServer(),
        studyId, participantId)
    if (server == null) {
        Log.w(TAG, "Skipping enrollment monitoring without a complete matching study server")
        return StatusPoll.NO_SERVER
    }
    val generation = ResearchErasureFence(context).settingsGeneration()
    val sent = SystemClock.elapsedRealtime()
    val response = try {
        UploadWorker.getChronicleStudyApi(server.url).getDeviceParticipationStatus(
            studyId, participantId, server.sourceRandomDeviceId,
            requireNotNull(server.apiKey) { "API-key enrollment is missing its credential" },
        )
    } catch (error: ChronicleCallException) {
        // The call adapter raises every HTTP refusal as this exception.
        endIfEnrollmentCancelled(context, server, generation, error)?.let { return pollResult(it) }
        throw error
    }
    ServerClock.of(context).measure(response.serverTime.toInstant(), sent, SystemClock.elapsedRealtime())
    val participationStatus = response.participationStatus

    val applied = if (participationStatus == ParticipationStatus.ERASED) {
        ParticipantWithdrawalManager.endEnrollmentEndedByServer(context, server, generation)
    } else {
        // Pause and completion keep what was captured before them; a hard stop keeps nothing, so it needs no flush.
        if (participationStatus in FLUSHED_STOPS &&
            settings.getParticipationStatus() == ParticipationStatus.ENROLLED &&
            ResearchPersistenceGate.applyCaptureWindows(context, server, generation, response.captureWindows,
                response.hardStop)) {
            // The last poll reads usage up to the stop, the new window drops what came after it.
            collectUsage(context)
        }
        ResearchPersistenceGate.applyParticipationStatus(context, server, generation, participationStatus,
            response.captureWindows, response.hardStop)
    }
    Log.i(TAG, if (applied) "Updated participation status: $participationStatus" else "Status apply skipped; polling again")
    return pollResult(applied)
}

/**
 * The one handler for a cancel the phone learns of late: the study team cancelled this enrollment attempt after
 * its key installed and before its ENROLLMENT ack landed, so the server revoked the key and answers the status poll and
 * the ack 410 ENROLLMENT_CANCELLED. The attempt never acknowledged consent, so nothing it captured will be accepted: the
 * enrollment ends here as cancelled through the hard stop, with no withdrawal request, and this returns whether the end
 * applied. Any other failure (an uncoded 410 included) returns null and keeps the caller's behaviour. Call it outside
 * any research read lease.
 */
internal fun endIfEnrollmentCancelled(context: Context, server: UploadServerEntity, generation: Long, error: Throwable): Boolean? {
    if (!isEnrollmentCancelledRefusal(error)) return null
    Log.w(TAG, "The study team cancelled this enrollment attempt; ending it")
    return ParticipantWithdrawalManager.endEnrollmentEndedByServer(context, server, generation, WithdrawalState.CANCELLED)
}

/**
 * Polls the status now: after boot and a manual clock change (the server clock is re-measured), and when the server
 * refuses an upload (the response that carries a withdrawal, a decline or Mark not enrolled to a phone that missed it).
 */
fun enqueueEnrollmentStatusRefresh(context: Context) {
    WorkManager.getInstance(context).enqueueUniqueWork(
        ENROLLMENT_REFRESH_WORK_NAME,
        ExistingWorkPolicy.KEEP,
        OneTimeWorkRequestBuilder<EnrollmentMonitoringWorker>()
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .build(),
    )
}

fun scheduleEnrollmentMonitoringWork(context: Context) {
    val workRequest: PeriodicWorkRequest =
        PeriodicWorkRequestBuilder<EnrollmentMonitoringWorker>(
            ENROLLMENT_MONITOR_INTERVAL_MIN,
            TimeUnit.MINUTES
        ).build()

    WorkManager.getInstance(context).enqueueUniquePeriodicWork(
        ENROLLMENT_MONITOR_WORK_NAME,
        ExistingPeriodicWorkPolicy.KEEP,
        workRequest
    )
}
