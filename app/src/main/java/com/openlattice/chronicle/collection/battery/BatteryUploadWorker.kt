package com.openlattice.chronicle.collection.battery

import android.content.Context
import android.util.Log
import androidx.work.BackoffPolicy
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.Worker
import androidx.work.WorkerParameters
import com.openlattice.chronicle.crypto.EncryptedPayloadType
import com.openlattice.chronicle.collection.state.ResearchPersistenceGate
import com.openlattice.chronicle.serialization.JsonSerializer
import com.openlattice.chronicle.services.crypto.EncryptionRequiredButUnavailableException
import com.openlattice.chronicle.services.crypto.EncryptionSettingStore
import com.openlattice.chronicle.services.crypto.PayloadSealer
import com.openlattice.chronicle.services.upload.UPLOAD_NETWORK_CONSTRAINT
import com.openlattice.chronicle.services.upload.UploadQueueSingleFlight
import com.openlattice.chronicle.services.upload.UploadWorker
import com.openlattice.chronicle.services.upload.LocalUploadDiagnosticsStore
import com.openlattice.chronicle.services.upload.LocalUploadModuleFamily
import com.openlattice.chronicle.services.upload.exactActiveEnrollmentServerResolution
import com.openlattice.chronicle.services.upload.handleServerUploadFailure
import com.openlattice.chronicle.storage.ChronicleDb
import com.openlattice.chronicle.storage.UploadStatsEntity
import java.time.LocalDate
import java.time.OffsetDateTime
import java.util.UUID
import java.util.concurrent.TimeUnit

private val TAG = BatteryUploadWorker::class.java.simpleName

/** Unique WorkManager name for the periodic battery-telemetry upload work. */
internal const val BATTERY_UPLOAD_WORK_NAME = "battery_telemetry_upload"

/** Upload cadence — 15 min (the WorkManager periodic minimum). */
private const val BATTERY_UPLOAD_INTERVAL_MIN = 15L

/**
 * Single-pass row cap. Battery telemetry is ~96 rows/day, so even a multi-day backlog
 * fits one request — no cursor paging is needed (unlike high-volume sensor upload).
 */
private const val BATTERY_UPLOAD_MAX_BATCH = 5000

/**
 * Rows older than this are dropped even if never uploaded. This bounds `battery_samples`
 * growth if an upload server is persistently unreachable.
 */
private const val BATTERY_SAMPLE_TTL_DAYS = 14L

/** Run-attempt count above which a failing upload stops retrying. */
private const val BATTERY_UPLOAD_MAX_ATTEMPTS = 5

/**
 * Periodic [Worker] that uploads collected `battery_samples` rows to the server
 * (see `docs/SENSING-EXPANSION-DESIGN.md` §5). Pairs with [BatteryCollectionWorker]:
 * collection writes rows locally, this worker ships them to the
 * `/chronicle/v4/study/.../android/battery` endpoint.
 *
 */
class BatteryUploadWorker(context: Context, workerParameters: WorkerParameters) :
    Worker(context, workerParameters) {

    override fun doWork(): Result {
        return try {
            val result = ResearchPersistenceGate.runIfActive(applicationContext) {
                if (!UploadQueueSingleFlight.tryAcquire(BATTERY_UPLOAD_WORK_NAME)) {
                    Log.i(TAG, "Battery upload deferred because the queue is already being drained")
                    Result.retry()
                } else try {
                    val failures = BatteryUploadWorkerDelegate(
                        applicationContext, ChronicleDb.getInstance(applicationContext),
                    ).execute()
                    when {
                        failures == 0 -> Result.success()
                        runAttemptCount > BATTERY_UPLOAD_MAX_ATTEMPTS -> Result.failure()
                        else -> Result.retry()
                    }
                } finally {
                    UploadQueueSingleFlight.release(BATTERY_UPLOAD_WORK_NAME)
                }
            }
            if (result == null) {
                Log.i(TAG, "Battery upload skipped without an active study enrollment")
            }
            result ?: Result.success()
        } catch (e: Exception) {
            Log.e(TAG, "Battery upload worker failed", e)
            Result.failure()
        }
    }
}

/**
 * Core battery-upload logic, separated from the [Worker] so it has no `WorkManager`
 * coupling.
 *
 * Pending rows are uploaded only to the exact active-enrollment server and deleted after
 * that destination receives the batch. The server endpoint is idempotent
 * (`ON CONFLICT (study_id, participant_id, sample_id) DO NOTHING`), so when one server
 * fails and the batch is kept, the next run can safely retry it.
 */
class BatteryUploadWorkerDelegate(
    private val context: Context,
    private val db: ChronicleDb,
) {

    /** @return 1 when the active study server failed this run, otherwise 0. */
    fun execute(): Int {
        val dao = db.batterySampleDao()
        val serverDao = db.uploadServerDao()

        val destination = exactActiveEnrollmentServerResolution(context, db)
        val server = destination.server
        if (server == null) {
            LocalUploadDiagnosticsStore.of(context).record(
                LocalUploadModuleFamily.BATTERY,
                requireNotNull(destination.issue),
            )
            Log.w(TAG, "Active enrollment has no eligible server; retaining battery samples")
            return 1
        }
        val servers = listOf(server)

        // Is any enabled study fail-closed (e2ee required but no usable key cached)? Its pending PHI
        // is being deliberately retained for retry, so the age-based TTL purge below must NOT drop
        // it — that would silently lose PHI we cannot upload yet. (The post-upload deletion is
        // already gated on all-servers-succeeded; the TTL purge is a separate path.) The missing
        // destination check above prevents encrypted-preference access without a server.
        val anyFailClosed = run {
            val encryptionStore = EncryptionSettingStore.of(context)
            servers.any { server ->
                runCatching { UUID.fromString(server.studyId) }.getOrNull()?.let { studyId ->
                    PayloadSealer.routing(
                        encryptionStore.get(studyId), encryptionStore.isEncryptionRequired(studyId),
                    ) == PayloadSealer.EncryptionRouting.FAIL_CLOSED
                } ?: false
            }
        }

        // TTL cleanup bounds table growth even if a server is persistently unreachable — but is
        // SKIPPED while any study is fail-closed (above), and the skip is logged so the retained
        // backlog is observable rather than silently dropped as PHI loss.
        try {
            if (anyFailClosed) {
                Log.w(
                    TAG,
                    "Skipping battery TTL purge: a study is fail-closed (e2ee required, key pending); retaining PHI for retry",
                )
            } else {
                val cutoff = OffsetDateTime.now().minusDays(BATTERY_SAMPLE_TTL_DAYS).toString()
                val purged = dao.deleteOlderThan(cutoff)
                if (purged > 0) {
                    Log.w(TAG, "Purged $purged battery sample(s) past the ${BATTERY_SAMPLE_TTL_DAYS}-day TTL")
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Battery sample TTL cleanup failed; continuing with upload", e)
        }

        val pending = dao.getOldest(BATTERY_UPLOAD_MAX_BATCH)
        if (pending.isEmpty()) {
            return 0
        }

        // Convert rows to wire DTOs; a corrupt row is skipped (counted), never aborts the batch.
        var malformed = 0
        val samples = pending.mapNotNull { entry ->
            try {
                entry.toBatterySample()
            } catch (e: Exception) {
                malformed++
                Log.w(TAG, "Skipping corrupt battery sample ${entry.id}", e)
                null
            }
        }

        var failureCount = 0
        for (server in servers) {
            try {
                if (samples.isNotEmpty()) {
                    val studyId = UUID.fromString(server.studyId)
                    val studyApi = UploadWorker.getChronicleStudyApi(server.url, server.mobileSigningSecretOverride)
                    // Study payload-encryption setting (HIPAA-2028 W2): seal + encrypted endpoint
                    // when on for this study, otherwise the existing plaintext battery upload.
                    val encryptionStore = EncryptionSettingStore.of(context)
                    val encryptionSetting = encryptionStore.get(studyId)
                    // Single routing decision (shared with sensor + usage via PayloadSealer.routing()).
                    val encryptionRouting = PayloadSealer.routing(
                        encryptionSetting, encryptionStore.isEncryptionRequired(studyId),
                    )
                    if (encryptionRouting == PayloadSealer.EncryptionRouting.FAIL_CLOSED) {
                        // Fail closed: e2ee required but no usable key cached. Do NOT upload PHI in
                        // plaintext — throw so this server counts as failed, the batch is not deleted
                        // (deletion needs all servers to succeed), and we retry.
                        throw EncryptionRequiredButUnavailableException(studyId)
                    }
                    if (encryptionRouting == PayloadSealer.EncryptionRouting.ENCRYPT) {
                        // Seal the EXACT bytes the plaintext path would post:
                        // mapper.writeValueAsBytes(samples: List<BatterySample>).
                        val plaintext = JsonSerializer.serializeToBytes(samples)
                        val envelope = PayloadSealer.seal(
                            setting = encryptionSetting!!,
                            studyId = studyId,
                            participantId = server.participantId,
                            payloadType = EncryptedPayloadType.BATTERY,
                            plaintext = plaintext,
                            sampleCount = samples.size,
                        )
                        studyApi.uploadAndroidEncryptedData(
                            studyId, server.participantId, server.sourceDeviceId, server.apiKey, listOf(envelope),
                        )
                    } else {
                        studyApi.uploadAndroidBatteryData(
                            studyId,
                            server.participantId,
                            server.sourceDeviceId,
                            server.apiKey,
                            samples,
                        )
                    }
                    Log.i(TAG, "[${server.name}] Uploaded ${samples.size} battery sample(s)")
                }
                serverDao.recordBatteryUploadSuccess(
                    server.id,
                    OffsetDateTime.now().toString(),
                    samples.size,
                )
                val today = LocalDate.now().toString()
                db.uploadStatsDao().insertDay(UploadStatsEntity(serverId = server.id, date = today))
                db.uploadStatsDao().incrementBatteryCount(server.id, today, samples.size)
            } catch (e: Exception) {
                failureCount++
                handleServerUploadFailure(
                    context,
                    TAG,
                    server,
                    e,
                    LocalUploadModuleFamily.BATTERY,
                    server.batteryConsecutiveFailures,
                ) { failures, errorMsg ->
                    serverDao.recordBatteryUploadFailure(server.id, OffsetDateTime.now().toString(), errorMsg, failures)
                }
                val today = LocalDate.now().toString()
                db.uploadStatsDao().insertDay(UploadStatsEntity(serverId = server.id, date = today))
                db.uploadStatsDao().incrementBatteryFailureCount(server.id, today, 1)
            }
        }

        // Delete the batch only when the active study server received it. If it failed, the
        // rows are kept and re-uploaded next run (idempotent server-side). Corrupt rows
        // that produced no sample are still purged here, so they are not retried forever.
        if (failureCount == 0) {
            dao.deleteByIds(pending.map { it.id })
        }
        Log.i(TAG, "Battery upload complete: serverFailures=$failureCount, malformedSkipped=$malformed")
        return failureCount
    }
}

/**
 * Schedules the periodic [BatteryUploadWorker]. Idempotent via
 * [ExistingPeriodicWorkPolicy.UPDATE]; constrained to run only with network.
 */
fun scheduleBatteryUploadWork(context: Context) {
    val workRequest = PeriodicWorkRequestBuilder<BatteryUploadWorker>(
        BATTERY_UPLOAD_INTERVAL_MIN,
        TimeUnit.MINUTES,
    )
        .setConstraints(UPLOAD_NETWORK_CONSTRAINT)
        .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
        .build()

    WorkManager.getInstance(context).enqueueUniquePeriodicWork(
        BATTERY_UPLOAD_WORK_NAME,
        ExistingPeriodicWorkPolicy.UPDATE,
        workRequest,
    )
}
