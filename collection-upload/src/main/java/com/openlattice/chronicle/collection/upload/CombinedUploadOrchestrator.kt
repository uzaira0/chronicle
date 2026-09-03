package com.openlattice.chronicle.collection.upload

import com.openlattice.chronicle.collection.core.CollectionLog

private const val TAG = "CombinedUploadOrchestrator"

/** Sentinel returned by an upload step that threw before producing a failure count. */
public const val UPLOAD_DELEGATE_THREW: Int = -1

/** Run-attempt count above which the combined-upload worker stops retrying. */
public const val COMBINED_UPLOAD_MAX_ATTEMPTS: Int = 5

/**
 * The pure, `WorkManager`-free outcome of a combined-upload run.
 *
 * `ListenableWorker.Result` is an Android type that cannot be constructed in a plain JVM
 * unit test (`returnDefaultValues` is not enabled and Phase 8 does not modify
 * `build.gradle`). The combined-upload **decision** is therefore expressed as this pure
 * enum, which unit tests assert directly; [toWorkerResult] maps it to a real
 * `ListenableWorker.Result` on-device.
 *
 *  - [SUCCESS] — both the usage and sensor steps cleanly succeeded.
 *  - [RETRY]   — at least one step failed and the attempt cap is not yet reached.
 *  - [FAILURE] — at least one step failed and the attempt cap is exhausted.
 *
 * The worker **never reports [SUCCESS] when a delegate failed** (refactor plan §11.2
 * guardrails 1 & 2): [SUCCESS] is produced only when both failure counts are exactly `0`.
 *
 */
public enum class CombinedUploadOutcome {
    SUCCESS,
    RETRY,
    FAILURE,
}

/**
 * The **pure** decision logic of `runCombinedUpload`, extracted so the combined-upload
 * semantics (refactor plan §11.2) can be unit tested without an Android `Context`,
 * WorkManager, or Room (Phase 8, subphase 8B).
 *
 * This is a behaviour-preserving extraction — for the identical step outcomes it yields
 * the identical decision the legacy inline body of `runCombinedUpload` reaches. It is
 * gated behind [UploadTelemetryMigration.USE_COMBINED_UPLOAD_ORCHESTRATOR] (default
 * `false`) so it can be proven at parity before becoming the only path.
 *
 * Combined-upload semantics preserved exactly:
 *  - **usage upload first, sensor upload second** — [runUsageUpload] is invoked before
 *    [runSensorUpload];
 *  - both steps always run — a usage failure does not skip the sensor step (the legacy
 *    code runs both in independent `try` blocks);
 *  - **stats cleanup always runs** and a cleanup failure never changes the outcome — it
 *    is swallowed-with-a-log exactly as today (cleanup is best-effort housekeeping, not
 *    an upload outcome);
 *  - **[CombinedUploadOutcome.SUCCESS] only when both steps cleanly succeeded** — a
 *    step's `0` means the delegate ran cleanly; `> 0` means at least one server failed;
 *    [UPLOAD_DELEGATE_THREW] (`-1`) means the delegate threw before returning and is
 *    treated as a failure;
 *  - **partial / total failure retries** until [COMBINED_UPLOAD_MAX_ATTEMPTS], then
 *    [CombinedUploadOutcome.FAILURE] — the worker never reports success when a delegate
 *    failed (refactor plan §11.2 guardrails 1 & 2).
 *
 * @param runAttemptCount the worker's current run attempt (`Worker.runAttemptCount`).
 * @param runUsageUpload runs the usage upload; returns the per-server failure count
 *   (`0` = clean), or [UPLOAD_DELEGATE_THREW] when the delegate threw.
 * @param runSensorUpload runs the sensor upload; same contract as [runUsageUpload].
 * @param cleanupStats best-effort stats cleanup; its throw is logged and ignored.
 * @param log the collection logger.
 * @param onComplete optional local diagnostics hook invoked with the two failure counts
 *   after both steps and cleanup run, for local logging only.
 */
public fun runCombinedUploadCore(
    runAttemptCount: Int,
    runUsageUpload: () -> Int,
    runSensorUpload: () -> Int,
    cleanupStats: () -> Unit,
    log: CollectionLog = CollectionLog.LOGCAT,
    onComplete: ((usageFailures: Int, sensorFailures: Int) -> Unit)? = null,
): CombinedUploadOutcome {
    // Usage upload FIRST.
    val usageFailures = runUsageUpload()
    // Sensor upload SECOND — always runs, even when usage failed.
    val sensorFailures = runSensorUpload()

    // Stats cleanup ALWAYS runs; a cleanup failure never changes the outcome.
    // Log string kept identical to the legacy inline path so any dogfood log grep
    // keyed on it sees the same line regardless of the migration switch.
    try {
        cleanupStats()
    } catch (e: Exception) {
        log.warn(TAG, "Failed to cleanup old stats", e)
    }

    onComplete?.invoke(usageFailures, sensorFailures)

    val usageOk = usageFailures == 0
    val sensorOk = sensorFailures == 0
    log.info(
        TAG,
        "Combined upload complete: usageFailures=$usageFailures, sensorFailures=$sensorFailures",
    )
    return when {
        usageOk && sensorOk -> CombinedUploadOutcome.SUCCESS
        runAttemptCount > COMBINED_UPLOAD_MAX_ATTEMPTS -> {
            log.error(
                TAG,
                "Combined upload failed after $runAttemptCount attempts, giving up",
            )
            CombinedUploadOutcome.FAILURE
        }
        else -> CombinedUploadOutcome.RETRY
    }
}
