package com.openlattice.chronicle.collection.upload

import android.content.Context
import com.openlattice.chronicle.collection.CollectionModuleDiagnostics
import com.openlattice.chronicle.collection.CollectionModuleId
import com.openlattice.chronicle.collection.CollectionPrivacyClass
import com.openlattice.chronicle.collection.core.CollectionLog
import com.openlattice.chronicle.collection.core.CollectionModuleStatus
import com.openlattice.chronicle.collection.core.CollectionWindow
import com.openlattice.chronicle.collection.core.DataCollectionModule
import com.openlattice.chronicle.collection.core.ModuleResult

private const val TAG = "UploadTelemetryCollectionModule"

/**
 * The upload-telemetry / diagnostics data collection module (design §1A.2
 * `upload_telemetry`, refactor plan §11, subphase 8A).
 *
 * Phase 8A makes upload health a **module-owned, read-only** boundary. It does not move
 * upload logic — `CombinedUploadWorker` / `UploadWorkerDelegate` /
 * `SensorUploadWorkerDelegate` keep running unchanged. This module only **observes** the
 * upload subsystem and renders it as redaction-safe operational telemetry.
 *
 *  - **Privacy class `OPERATIONAL_DIAGNOSTICS`** (design §1A.4) — upload/queue health
 *    carries no participant data, so the module is enabled by default; there is no
 *    server setting or preference gating it. Every operation is read-only — `start`,
 *    `stop`, `poll` and `flush` are no-ops (this module collects nothing into a queue;
 *    it reports on the queues other modules fill).
 *  - **Every `UploadServerEntity` field is preserved** by the underlying storage
 *    (refactor plan §11.1 steps 2–7): per-server auth mode, API key, cursors,
 *    consecutive-failure counters, last-error fields and last usage/sensor upload
 *    timestamps, plus the `upload_stats` table — this module touches none of them as a
 *    writer; it only reads the operational subset through [UploadStateProbe].
 *  - **Read-only diagnostics exposed** (refactor plan §11.1 steps 8–15): usage and
 *    sensor queue depth, the last combined-upload worker result, retry state, constraints
 *    state, disabled-server state, malformed-row counts and partial-failure counts.
 *  - **Redaction (refactor plan §11.1 steps 16–18, design §1B.3).** Diagnostics **never**
 *    contain an `apiKey`, a device secret, a `MOBILE_SIGNING_SECRET`, or a raw
 *    `participantId`. The [UploadStateProbe] projection drops the credential fields of
 *    `UploadServerEntity` entirely (they are never read into [UploadServerTelemetry]),
 *    and the per-server last-error strings — exception messages that could embed a host,
 *    body fragment or header — are surfaced only as a boolean "had an error" flag, never
 *    as text.
 *  - **WorkManager never reports false success** (refactor plan §11.1 guardrail 3). The
 *    worker-result line in diagnostics is derived from the actual `WorkInfo.State`: a
 *    `RETRY`/`FAILED`/`CANCELLED`/`ENQUEUED-after-attempt` state is reported as exactly
 *    that, never as `SUCCEEDED`. The module itself records no upload outcome — it reads
 *    WorkManager's.
 *
 * This is a plain class holding only its seams ([uploadState], [workProbe], clock-free
 * [log]) — no Android [Context]. The seams are wired once, from the application
 * `Context`, by [UploadTelemetryModuleHolder] and keep only `Context`-free handles.
 *
 */
public class UploadTelemetryCollectionModule(
    private val uploadState: UploadStateProbe,
    private val workProbe: UploadWorkProbe,
    private val log: CollectionLog = CollectionLog.LOGCAT,
) : DataCollectionModule {

    override val id: CollectionModuleId = CollectionModuleId.UPLOAD_TELEMETRY

    override val privacyClass: CollectionPrivacyClass = id.privacyClass

    init {
        require(privacyClass == id.privacyClass) {
            "UploadTelemetryCollectionModule.privacyClass must equal id.privacyClass"
        }
    }

    /**
     * Renders the current upload subsystem state as a redaction-safe
     * [UploadTelemetrySnapshot].
     *
     * A read of the probes that throws (e.g. a transient Room error) is contained: the
     * snapshot still renders, with the affected section left at its safe default and the
     * failure logged — diagnostics never crash the caller, and never silently claim
     * health they could not observe.
     */
    public fun snapshot(): UploadTelemetrySnapshot {
        val servers = runCatching { uploadState.servers() }
            .onFailure { log.warn(TAG, "Failed to read upload server telemetry", it) }
            .getOrDefault(emptyList())
        val usageDepth = runCatching { uploadState.usageQueueDepth() }
            .onFailure { log.warn(TAG, "Failed to read usage queue depth", it) }
            .getOrDefault(-1)
        val sensorDepth = runCatching { uploadState.sensorQueueDepth() }
            .onFailure { log.warn(TAG, "Failed to read sensor queue depth", it) }
            .getOrDefault(-1)
        val statsRows = runCatching { uploadState.uploadStatsRowCount() }
            .onFailure { log.warn(TAG, "Failed to read upload_stats row count", it) }
            .getOrDefault(-1)
        val periodic = runCatching { workProbe.periodicUploadStatus() }
            .onFailure { log.warn(TAG, "Failed to read periodic upload work status", it) }
            .getOrNull()
        val immediate = runCatching { workProbe.immediateUploadStatus() }
            .onFailure { log.warn(TAG, "Failed to read immediate upload work status", it) }
            .getOrNull()

        return UploadTelemetrySnapshot(
            usageQueueDepth = usageDepth,
            sensorQueueDepth = sensorDepth,
            uploadStatsRowCount = statsRows,
            enabledServerCount = servers.count { it.enabled },
            disabledServerCount = servers.count { !it.enabled },
            serversWithUsageError = servers.count { it.hasUsageUploadError },
            serversWithSensorError = servers.count { it.hasSensorUploadError },
            // Partial-failure signal: a server whose consecutive-failure counter is
            // advanced past zero has failed at least its most recent upload.
            usagePartialFailureCount = servers.count { it.consecutiveFailures > 0 },
            sensorPartialFailureCount = servers.count { it.sensorConsecutiveFailures > 0 },
            periodicWork = periodic,
            immediateWork = immediate,
        )
    }

    override fun status(): CollectionModuleStatus {
        val snap = snapshot()
        return when {
            // A combined-upload chain reported by WorkManager as FAILED/CANCELLED, or a
            // server that has accrued upload errors, surfaces as FAILED.
            snap.periodicWork?.isFailedOrCancelled == true -> CollectionModuleStatus.FAILED
            snap.immediateWork?.isFailedOrCancelled == true -> CollectionModuleStatus.FAILED
            snap.serversWithUsageError > 0 || snap.serversWithSensorError > 0 ->
                CollectionModuleStatus.DEGRADED
            else -> CollectionModuleStatus.IDLE
        }
    }

    /**
     * Operational telemetry — **redaction-safe** (design §1B.3, refactor plan §11.1).
     *
     * No `apiKey`, device secret, `MOBILE_SIGNING_SECRET` or raw `participantId` is
     * present in any field. `redactedParticipantRef` is left `null`: this module derives
     * no participant reference at all. `queueDepth` reports the usage-events queue depth
     * (the primary upload stream); the sensor queue depth and every other counter are
     * carried in [CollectionModuleDiagnostics.notTracked] as labelled `key=value`
     * strings, since the DTO has no first-class field for them.
     *
     * [CollectionModuleDiagnostics.lastResult] is the WorkManager state of the periodic
     * combined-upload work — never synthesised as `OK`/`SUCCEEDED` when WorkManager
     * reported `RETRY`/`FAILED`/`ENQUEUED`-after-attempt (guardrail 8A.3).
     */
    override fun diagnostics(): CollectionModuleDiagnostics {
        val snap = snapshot()
        val periodic = snap.periodicWork
        return CollectionModuleDiagnostics(
            moduleId = id,
            privacyClass = privacyClass,
            // Upload telemetry observes; it has no "run" of its own. The last observed
            // worker attempt count is the closest honest signal — left null when unknown.
            lastRunEpochMs = null,
            // WorkManager state verbatim — never coerced to a success label.
            lastResult = periodic?.state ?: "NO_WORK_ENQUEUED",
            itemsCollected = 0,
            queueDepth = snap.usageQueueDepth.coerceAtLeast(0),
            // No error *text* is surfaced: per-server last-error strings are exception
            // messages that could embed a host/body/header. Only a redacted count.
            lastError = redactedErrorSummary(snap),
            redactedParticipantRef = null,
            notTracked = buildSet {
                add("usageQueueDepth=${snap.usageQueueDepth}")
                add("sensorQueueDepth=${snap.sensorQueueDepth}")
                add("uploadStatsRowCount=${snap.uploadStatsRowCount}")
                add("enabledServers=${snap.enabledServerCount}")
                add("disabledServers=${snap.disabledServerCount}")
                add("usagePartialFailures=${snap.usagePartialFailureCount}")
                add("sensorPartialFailures=${snap.sensorPartialFailureCount}")
                add("serversWithUsageError=${snap.serversWithUsageError}")
                add("serversWithSensorError=${snap.serversWithSensorError}")
                addWorkStatus("periodicUpload", periodic)
                addWorkStatus("immediateUpload", snap.immediateWork)
                // WorkManager exposes no next-fire wall-clock time; honestly untracked.
                add("nextScheduledUploadEpochMs")
                // Malformed sensor-row counts are produced per-run by
                // SensorUploadWorkerDelegate; no cross-run local counter persists them.
                add("malformedRowCount")
            },
        )
    }

    /** No-op: upload telemetry is read-only; it starts no push service. */
    override fun start(context: Context): ModuleResult =
        ModuleResult.Skipped("upload_telemetry is read-only diagnostics; nothing to start")

    /** No-op: upload telemetry is read-only; it stops no push service. */
    override fun stop(context: Context): ModuleResult =
        ModuleResult.Skipped("upload_telemetry is read-only diagnostics; nothing to stop")

    /** No-op: upload telemetry observes other modules' queues; it has no poll window. */
    override fun poll(context: Context, window: CollectionWindow): ModuleResult =
        ModuleResult.Skipped("upload_telemetry is read-only diagnostics; no poll window")

    /** No-op: upload telemetry buffers nothing — it reports on the upload subsystem. */
    override fun flush(context: Context): ModuleResult =
        ModuleResult.Skipped("upload_telemetry buffers nothing")

    private fun MutableSet<String>.addWorkStatus(prefix: String, status: CombinedUploadWorkStatus?) {
        if (status == null) {
            add("${prefix}State=none")
            return
        }
        add("${prefix}State=${status.state ?: "unknown"}")
        add("${prefix}RunAttempt=${status.runAttemptCount}")
        add("${prefix}RetryPending=${status.isRetryPending}")
        add("${prefix}ConstraintsMet=${status.constraintsMet ?: "unknown"}")
    }

    /**
     * A redaction-safe one-line error summary, or `null` when nothing is wrong. Reports
     * only **how many** servers carry an upload error — never the error text, a host, a
     * participant id, or a key.
     */
    private fun redactedErrorSummary(snap: UploadTelemetrySnapshot): String? {
        val usage = snap.serversWithUsageError
        val sensor = snap.serversWithSensorError
        if (usage == 0 && sensor == 0) return null
        return "upload errors: $usage usage, $sensor sensor"
    }
}

/**
 * Immutable, redaction-safe snapshot of the upload subsystem at one instant.
 *
 * Every field is `OPERATIONAL_DIAGNOSTICS`: counts, depths and WorkManager state. No
 * field carries an `apiKey`, a device secret, a `participantId`, a study id, a URL, or
 * any per-server error **text** — only error **counts** (refactor plan §11.1 steps
 * 16–18, design §1B.3). A depth of `-1` means "could not be read this snapshot".
 *
 */
public data class UploadTelemetrySnapshot(
    /** `dataQueue` row count — usage-events upload queue depth (`-1` if unreadable). */
    val usageQueueDepth: Int,
    /** `sensor_samples` row count — sensor upload queue depth (`-1` if unreadable). */
    val sensorQueueDepth: Int,
    /** `upload_stats` row count (`-1` if unreadable). */
    val uploadStatsRowCount: Int,
    /** Number of enabled upload servers. */
    val enabledServerCount: Int,
    /** Number of destinations disabled by an explicit enrollment or authoritative study decision. */
    val disabledServerCount: Int,
    /** Number of servers whose last usage upload recorded an error. */
    val serversWithUsageError: Int,
    /** Number of servers whose last sensor upload recorded an error. */
    val serversWithSensorError: Int,
    /** Number of servers with a non-zero usage consecutive-failure counter. */
    val usagePartialFailureCount: Int,
    /** Number of servers with a non-zero sensor consecutive-failure counter. */
    val sensorPartialFailureCount: Int,
    /** WorkManager status of the periodic `combined_upload` work, or `null`. */
    val periodicWork: CombinedUploadWorkStatus?,
    /** WorkManager status of the one-time `combined_upload_immediate` work, or `null`. */
    val immediateWork: CombinedUploadWorkStatus?,
)
