package com.openlattice.chronicle.services.upload

import android.content.Context
import android.util.Log
import androidx.work.*
import com.openlattice.chronicle.storage.ChronicleDb
import com.openlattice.chronicle.storage.ServerMigrationHelper
import com.openlattice.chronicle.telemetry.LocalTelemetry
import com.openlattice.chronicle.storage.UploadServerEntity
import com.openlattice.chronicle.api.ChronicleStudyApi
import com.openlattice.chronicle.collection.state.ResearchPersistenceGate
import com.openlattice.chronicle.services.crypto.EncryptionRequiredButUnavailableException
import com.openlattice.chronicle.utils.Utils.createRetrofitAdapter
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

const val LAST_UPLOADED_PLACEHOLDER = "Never"
const val BATCH_SIZE = 10
const val LAST_UPDATED_SETTING = "com.openlattice.chronicle.upload.LastUpdated"
const val LATEST_TIMESTAMP_UPLOADED_SETTING = "com.openlattice.chronicle.upload.LatestTimestampUploaded"
const val UPLOAD_QUEUE_SIZE_SETTING = "upload_queue_size"
const val UPLOAD_INTERVAL_MIN = 15L

val TAG = UploadWorker::class.java.simpleName

class UploadWorker(context: Context, params: WorkerParameters) : Worker(context, params) {

    companion object {
        private val studyApiCache = ConcurrentHashMap<String, ChronicleStudyApi>()

        fun getChronicleStudyApi(
            url: String,
            mobileSigningSecretOverride: String? = null
        ): ChronicleStudyApi {
            val trustedUrl = com.openlattice.chronicle.utils.Utils.normalizeTrustedServerUrl(url)
                ?: throw IllegalArgumentException("Untrusted Chronicle server URL")
            val cacheKey = trustedUrl + "|" +
                com.openlattice.chronicle.utils.Utils.mobileSigningSecretFingerprint(mobileSigningSecretOverride)
            return studyApiCache.getOrPut(cacheKey) {
                createRetrofitAdapter(trustedUrl, mobileSigningSecretOverride)
                    .create(ChronicleStudyApi::class.java)
            }
        }

    }

    override fun doWork(): Result {
        return try {
            val result = ResearchPersistenceGate.runIfActive(applicationContext) {
                if (!UploadQueueSingleFlight.tryAcquire(LEGACY_USAGE_UPLOAD_WORK_NAME)) {
                    Log.i(TAG, "Legacy usage upload deferred because the queue is already being drained")
                    Result.retry()
                } else {
                    try {
                        val chronicleDb = ChronicleDb.getInstance(applicationContext)
                        ServerMigrationHelper.migrateIfNeeded(applicationContext, chronicleDb)
                        val failures = UploadWorkerDelegate(applicationContext, chronicleDb).execute()
                        // Piggyback the per-module collection-settings poll on the upload cadence so
                        // server-side toggles propagate at ~no extra wake-ups (collection loop closure).
                        com.openlattice.chronicle.collection.state.CollectionSettingsSyncWorker
                            .enqueueNow(applicationContext)
                        when {
                            failures == 0 -> Result.success()
                            runAttemptCount > 5 -> Result.failure()
                            else -> Result.retry()
                        }
                    } finally {
                        UploadQueueSingleFlight.release(LEGACY_USAGE_UPLOAD_WORK_NAME)
                    }
                }
            }
            if (result == null) {
                Log.i(TAG, "Legacy usage upload skipped without an active study enrollment")
            }
            result ?: Result.success()
        } catch (e: Exception) {
            LocalTelemetry.recordException(e)
            Log.e(TAG, "usage upload failed", e)
            Result.failure()
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
    server: UploadServerEntity,
    error: Exception,
    moduleFamily: LocalUploadModuleFamily,
    currentFailures: Int,
    updateStatus: (failures: Int, errorMsg: String) -> Unit
) {
    // A fail-closed e2ee gate is NOT a server-reachability failure: the study requires encryption
    // but the public key isn't cached yet (transient). Retain + retry, but do NOT count it toward
    // the consecutive-failure evidence — the next successful upload resets the counter.
    if (error is EncryptionRequiredButUnavailableException) {
        Log.w(tag, "Upload to '${server.name}' gated (e2ee required, key not yet available); retained for retry")
        return
    }

    Log.e(tag, "Upload to '${server.name}' failed", error)
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
    classifyUploadFailure(error, (error as? retrofit2.HttpException)?.code())

fun scheduleUploadWork(context: Context) {
    val workRequest: PeriodicWorkRequest =
        PeriodicWorkRequestBuilder<UploadWorker>(UPLOAD_INTERVAL_MIN, TimeUnit.MINUTES)
            .setConstraints(UPLOAD_NETWORK_CONSTRAINT)
            .build()

    WorkManager.getInstance(context).enqueueUniquePeriodicWork(
        "upload",
        ExistingPeriodicWorkPolicy.UPDATE,
        workRequest
    )
}

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
