package com.openlattice.chronicle.services.release

import android.content.Context
import android.util.Log
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.Worker
import androidx.work.WorkerParameters
import com.openlattice.chronicle.BuildConfig
import com.openlattice.chronicle.collection.device.HealthConnectScopeStore
import com.openlattice.chronicle.collection.state.MinimalPlayArtifactState
import com.openlattice.chronicle.collection.state.ResearchPersistenceGate
import com.openlattice.chronicle.collection.directboot.clearDirectBootSensorBuffer
import com.openlattice.chronicle.preferences.InteractionPolicySettings
import com.openlattice.chronicle.preferences.SensorSettings
import com.openlattice.chronicle.preferences.clearDirectBootSensorSnapshot
import com.openlattice.chronicle.services.notifications.DeviceUnlockMonitoringService
import com.openlattice.chronicle.services.notifications.userIdentificationMayRun
import com.openlattice.chronicle.services.upload.UploadQueueSingleFlight
import com.openlattice.chronicle.storage.ChronicleDb

private const val TAG = "MinimalPlayBoundary"
private const val UNIQUE_WORK_NAME = "minimal_play_artifact_boundary"

/**
 * One-time privacy boundary for a fresh or upgraded Play installation.
 *
 * The worker executes while [ResearchPersistenceGate] is closed, removes every queue and local
 * policy belonging to a module absent from the compiled approved-module registry, then records
 * the exact registry hash. A later registry change automatically closes collection until this
 * boundary runs again. Research/open distributions are untouched.
 */
class MinimalPlayBoundaryWorker(
    context: Context,
    params: WorkerParameters,
) : Worker(context, params) {
    override fun doWork(): Result {
        if (BuildConfig.DISTRIBUTION_CHANNEL !in setOf("PLAY", "AMAZON")) return Result.success()
        return try {
            ResearchPersistenceGate.stop {
                UploadQueueSingleFlight.withExclusiveMutation {
                    purgeRestrictedPlayRows(ChronicleDb.getInstance(applicationContext))
                }
                check(SensorSettings(applicationContext).clear()) {
                    "Unable to clear legacy high-rate sensor settings"
                }
                check(clearDirectBootSensorSnapshot(applicationContext)) {
                    "Unable to clear legacy direct-boot sensor settings"
                }
                check(clearDirectBootSensorBuffer(applicationContext)) {
                    "Unable to clear legacy direct-boot sensor rows"
                }
                check(InteractionPolicySettings(applicationContext).clear()) {
                    "Unable to clear the legacy interaction policy"
                }
                HealthConnectScopeStore.of(applicationContext).clear()
                MinimalPlayArtifactState.markBoundaryApplied(applicationContext)
            }
            // The dashboard/boot path may have attempted to start unlock monitoring while the
            // boundary was still closed. Re-evaluate it after the durable boundary completes so
            // an authorized Identify User feature is not stranded off until another app restart.
            if (userIdentificationMayRun(applicationContext)) {
                DeviceUnlockMonitoringService.startAuthorizedService(applicationContext)
            }
            Result.success()
        } catch (error: Exception) {
            Log.e(TAG, "Play artifact boundary failed; collection remains stopped", error)
            Result.retry()
        }
    }
}

internal fun purgeRestrictedPlayRows(db: ChronicleDb) {
    val approvedModuleIds = BuildConfig.PLAY_APPROVED_MODULE_IDS
        .split(',')
        .filter(String::isNotBlank)
    check(approvedModuleIds.isNotEmpty()) { "Play approved-module registry is empty" }
    db.runInTransaction {
        val sql = db.openHelper.writableDatabase
        listOf(
            "sensor_samples",
            "sensor_sample_dead_letters",
            "interaction_samples",
            "audio_activity_samples",
            "audio_content_samples",
            "notification_activity_samples",
            "sleep_samples",
            "activity_recognition_samples",
            "health_metric_samples",
        ).forEach { table -> sql.execSQL("DELETE FROM `$table`") }
        val placeholders = approvedModuleIds.joinToString(",") { "?" }
        sql.execSQL(
            "DELETE FROM collection_module_state WHERE moduleId NOT IN ($placeholders)",
            approvedModuleIds.map { it as Any }.toTypedArray(),
        )
    }
}

fun scheduleMinimalPlayArtifactBoundary(context: Context) {
    if (BuildConfig.DISTRIBUTION_CHANNEL !in setOf("PLAY", "AMAZON")) return
    WorkManager.getInstance(context.applicationContext).enqueueUniqueWork(
        UNIQUE_WORK_NAME,
        ExistingWorkPolicy.KEEP,
        OneTimeWorkRequestBuilder<MinimalPlayBoundaryWorker>().build(),
    )
}
