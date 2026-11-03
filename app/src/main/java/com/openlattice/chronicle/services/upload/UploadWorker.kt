package com.openlattice.chronicle.services.upload

import com.openlattice.chronicle.utils.Utils

import android.content.Context
import android.util.Log
import androidx.work.ExistingWorkPolicy
import androidx.work.ListenableWorker
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import com.openlattice.chronicle.api.ChronicleStudyApi
import com.openlattice.chronicle.collection.state.ResearchPersistenceGate
import com.openlattice.chronicle.services.enrollment.StatusPoll
import com.openlattice.chronicle.services.enrollment.pollParticipationStatus
import com.openlattice.chronicle.storage.ChronicleDb
import com.openlattice.chronicle.storage.UploadServerEntity
import com.openlattice.chronicle.telemetry.LocalTelemetry
import com.openlattice.chronicle.utils.Utils.createRetrofitAdapter
import java.time.OffsetDateTime
import java.util.concurrent.CancellationException
import java.util.concurrent.ConcurrentHashMap

const val BATCH_SIZE = 10
const val LAST_UPDATED_SETTING = "com.openlattice.chronicle.upload.LastUpdated"
const val LATEST_TIMESTAMP_UPLOADED_SETTING = "com.openlattice.chronicle.upload.LatestTimestampUploaded"
const val UPLOAD_INTERVAL_MIN = 15L

object UploadWorker {
    private val studyApiCache = ConcurrentHashMap<String, ChronicleStudyApi>()

    fun getChronicleStudyApi(url: String): ChronicleStudyApi {
        val trustedUrl = Utils.normalizeTrustedServerUrl(url)
            ?: throw IllegalArgumentException("Untrusted Chronicle server URL")
        return studyApiCache.getOrPut(trustedUrl) {
            createRetrofitAdapter(trustedUrl).create(ChronicleStudyApi::class.java)
        }
    }
}

/**
 * Shared failure handler for the active study server's upload workers.
 * Logs the error, records it locally, and calls [updateStatus] to persist failure state.
 * A temporary outage must never disable the enrolled destination; only an authoritative
 * enrollment/settings transition may change whether that destination is eligible.
 */
fun handleServerUploadFailure(
    context: Context,
    tag: String,
    error: Exception,
    moduleFamily: LocalUploadModuleFamily,
    currentFailures: Int,
    updateStatus: (failures: Int, errorMsg: String) -> Unit
) {
    Log.e(tag, "Failed to upload ${moduleFamily.uploadLabel}", error)
    LocalTelemetry.recordException(error)
    LocalUploadDiagnosticsStore.of(context).recordFailure(moduleFamily, error)

    val failures = nextConsecutiveUploadFailureCount(currentFailures)
    // These legacy columns are consumed only as a success/failure marker. Persist a closed code,
    // never exception text: HTTP/library messages can contain the enrolled origin, response text,
    // request metadata, or credentials supplied by a misbehaving interceptor.
    updateStatus(failures, persistedUploadFailureCode(error))
}

internal fun nextConsecutiveUploadFailureCount(currentFailures: Int): Int =
    if (currentFailures >= Int.MAX_VALUE) Int.MAX_VALUE else currentFailures + 1

internal fun persistedUploadFailureCode(error: Exception): String =
    classifyUploadFailure(error, uploadHttpStatus(error)).name

fun triggerImmediateUpload(context: Context) {
    val request = OneTimeWorkRequestBuilder<CombinedUploadWorker>()
        .setConstraints(UPLOAD_NETWORK_CONSTRAINT)
        .build()

    WorkManager.getInstance(context).enqueueUniqueWork(
        COMBINED_UPLOAD_IMMEDIATE_WORK_NAME,
        ExistingWorkPolicy.REPLACE,
        request
    )
}

/**
 * Every upload worker's one admission: the status step, then [operation] under the enrolled-upload lease. A
 * pause, completion or archive this phone missed drops what was captured inside it before anything is sent. A
 * failed or skipped poll uploads nothing: the phone cannot know whether the server stopped it, and the retry polls again.
 * A poll the server answered with an error is an upload that could not run, and counts as one (see [recordRefusedStatusPoll]).
 */
internal fun runEnrolledUpload(context: Context, tag: String, operation: () -> ListenableWorker.Result): ListenableWorker.Result {
    val expected = ChronicleDb.getInstance(context).uploadServerDao().getConfiguredServer()
    val polled = runCatching {
        pollParticipationStatus(context) { error ->
            Log.w(tag, "Status poll failed; upload waits: ${error.javaClass.simpleName}")
            if (uploadHttpStatus(error) != null && expected != null) recordRefusedStatusPoll(context, tag, error, expected)
        }
    }.onFailure { if (it !is Exception) throw it }.getOrNull()
    if (polled != StatusPoll.APPLIED && polled != StatusPoll.NO_ENROLLMENT) return ListenableWorker.Result.retry()
    return ResearchPersistenceGate.runIfEnrolled(context, operation)
        ?: ListenableWorker.Result.success().also { Log.i(tag, "Upload skipped without an active study enrollment") }
}

/**
 * Count a refused poll under the owner lease. It runs inside the status poll's turn (pollParticipationStatus), so
 * simultaneous worker refusals never lose a failure increment.
 */
private fun recordRefusedStatusPoll(context: Context, tag: String, error: Exception, expected: UploadServerEntity) =
    ResearchPersistenceGate.withReadLease {
        if (!ResearchPersistenceGate.isSameEnrolledOwner(context, expected)) return@withReadLease
        val servers = ChronicleDb.getInstance(context).uploadServerDao()
        val server = servers.getConfiguredServer() ?: return@withReadLease
        handleServerUploadFailure(context, tag, error, LocalUploadModuleFamily.USAGE_LIFECYCLE, server.consecutiveFailures) { failures, code ->
            servers.recordUsageUploadFailure(server.id, OffsetDateTime.now().toString(), code, failures)
        }
    }

/** Runs a worker body: a local storage failure retries; every other failure goes to [onLost], then WorkManager. */
// Storage failures arrive wrapped in many types; the shared classifier decides which may retry.
@Suppress("TooGenericExceptionCaught")
internal inline fun retryOnStorageFailure(
    onLost: (Exception) -> Unit = {},
    work: () -> ListenableWorker.Result,
): ListenableWorker.Result = try {
    work()
} catch (error: CancellationException) {
    throw error
} catch (error: InterruptedException) {
    throw error
} catch (error: Exception) {
    if (!ResearchPersistenceGate.isStorageFailure(error)) {
        onLost(error)
        throw error
    }
    Log.e("UploadWorker", "Upload storage unavailable; retained for retry (${error.javaClass.simpleName})")
    ListenableWorker.Result.retry()
}
