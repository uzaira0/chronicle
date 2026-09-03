package com.openlattice.chronicle.collection.upload

import android.content.Context
import androidx.work.WorkInfo
import androidx.work.WorkManager
import com.openlattice.chronicle.constants.COMBINED_UPLOAD_IMMEDIATE_WORK_NAME
import com.openlattice.chronicle.constants.COMBINED_UPLOAD_WORK_NAME
import com.openlattice.chronicle.storage.ChronicleDb

/**
 * Holds the single app-scoped [UploadTelemetryCollectionModule] instance and constructs
 * it with its production seams (refactor plan §11, mirrors the Phase 4–7 holders).
 *
 * **Why a holder.** The module reads Room (`dataQueue`, `sensor_samples`, `upload_stats`,
 * `upload_servers`) and WorkManager, both of which need an Android `Context` to obtain —
 * yet design §1C / refactor plan §6.1 guardrail 2 forbid storing a `Context` in a
 * singleton field. This holder resolves the tension: it builds the module **lazily on
 * first use** from the application `Context`, wires its seams (each keeping only
 * `Context`-free handles — Room DAOs and a `WorkManager` instance), and then holds only
 * the module — never a `Context`. The module itself is stateless (it reads live state on
 * every call), so a process-wide singleton is safe.
 *
 */
public object UploadTelemetryModuleHolder {

    @Volatile private var instance: UploadTelemetryCollectionModule? = null

    /**
     * Returns the shared [UploadTelemetryCollectionModule], building it on first use from
     * the application context of [context].
     */
    public fun get(context: Context): UploadTelemetryCollectionModule {
        instance?.let { return it }
        return synchronized(this) {
            instance ?: build(context.applicationContext).also { instance = it }
        }
    }

    private fun build(appContext: Context): UploadTelemetryCollectionModule {
        val db = ChronicleDb.getInstance(appContext)
        return UploadTelemetryCollectionModule(
            uploadState = RoomUploadStateProbe(db),
            // WorkManager.getInstance returns a process-wide singleton; holding it is not
            // holding a Context.
            workProbe = WorkManagerUploadProbe(WorkManager.getInstance(appContext)),
        )
    }
}

/**
 * Production [UploadStateProbe] over the real `ChronicleDb` DAOs.
 *
 * Holds only the Room database handle (no `Context`). Every method is a read. The
 * server projection **drops `UploadServerEntity.apiKey`, `participantId`,
 * `sourceDeviceId`, `studyId` and `url`** — they are never copied into
 * [UploadServerTelemetry], so no credential or participant identifier can reach
 * diagnostics (design §1B.3, refactor plan §11.1 steps 16–18).
 *
 */
public class RoomUploadStateProbe(
    private val db: ChronicleDb,
) : UploadStateProbe {

    override fun servers(): List<UploadServerTelemetry> =
        listOfNotNull(db.uploadServerDao().getConfiguredServer()).map { server ->
            UploadServerTelemetry(
                serverId = server.id,
                enabled = server.enabled,
                consecutiveFailures = server.consecutiveFailures,
                sensorConsecutiveFailures = server.sensorConsecutiveFailures,
                lastUsageUploadTime = server.lastUploadTime,
                lastSensorUploadTime = server.lastSensorUploadTime,
                // Only a boolean "had an error" — never the error text (it is an
                // exception message that could embed a host/body/header).
                hasUsageUploadError = server.lastUploadError != null,
                hasSensorUploadError = server.lastSensorUploadError != null,
                // NOTE: server.apiKey / participantId / sourceDeviceId / studyId / url
                // are deliberately NOT read here — diagnostics carry no secret.
            )
        }

    override fun usageQueueDepth(): Int = db.queueEntryData().getSize()

    override fun sensorQueueDepth(): Int = db.sensorSampleDao().count()

    override fun uploadStatsRowCount(): Int = db.uploadStatsDao().rowCount()
}

/**
 * Production [UploadWorkProbe] over WorkManager.
 *
 * Holds only a [WorkManager] instance (a process-wide singleton — not a `Context`).
 * Reads the latest `WorkInfo` for the two combined-upload unique work names and projects
 * it into a credential-free [CombinedUploadWorkStatus]; it never reports a success state
 * WorkManager did not actually report.
 *
 */
public class WorkManagerUploadProbe(
    private val workManager: WorkManager,
) : UploadWorkProbe {

    override fun periodicUploadStatus(): CombinedUploadWorkStatus? =
        statusOf(COMBINED_UPLOAD_WORK_NAME)

    override fun immediateUploadStatus(): CombinedUploadWorkStatus? =
        statusOf(COMBINED_UPLOAD_IMMEDIATE_WORK_NAME)

    private fun statusOf(workName: String): CombinedUploadWorkStatus? {
        val infos: List<WorkInfo> = runCatching {
            workManager.getWorkInfosForUniqueWork(workName).get()
        }.getOrElse { return null }
        // Most-recently-generated WorkInfo (a periodic chain re-uses one id; a unique
        // one-time work has at most one).
        val info = infos.lastOrNull() ?: return null
        return CombinedUploadWorkStatus(
            workName = workName,
            state = info.state.name,
            runAttemptCount = info.runAttemptCount,
            // WorkInfo.constraints is API-level dependent; treat absence as "unknown".
            constraintsMet = runCatching { allConstraintsKnownMet(info) }.getOrNull(),
        )
    }

    /**
     * WorkManager does not expose live constraint-satisfaction; `BLOCKED` means a
     * constraint is unmet, any other non-terminal state implies constraints are met or
     * not yet relevant. This is a best-effort signal — `null` is returned otherwise.
     */
    private fun allConstraintsKnownMet(info: WorkInfo): Boolean? = when (info.state) {
        WorkInfo.State.BLOCKED -> false
        WorkInfo.State.RUNNING, WorkInfo.State.SUCCEEDED -> true
        else -> null
    }
}
