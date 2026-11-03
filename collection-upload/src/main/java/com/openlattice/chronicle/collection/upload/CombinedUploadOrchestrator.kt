package com.openlattice.chronicle.collection.upload

import android.util.Log


private const val TAG = "CombinedUploadOrchestrator"

/** Sentinel returned by an upload step that threw before producing a failure count. */
public const val UPLOAD_DELEGATE_THREW: Int = -1

/** Run-attempt count above which the combined-upload worker stops retrying. */
public const val COMBINED_UPLOAD_MAX_ATTEMPTS: Int = 5

/** Pure outcome shared by upload orchestration and WorkManager result mapping. */
public enum class CombinedUploadOutcome {
    SUCCESS,
    RETRY,
    FAILURE,
}

/** Runs both upload streams and maps their failures to a worker outcome. */
public fun runCombinedUploadCore(
    runAttemptCount: Int,
    runUsageUpload: () -> Int,
    runSensorUpload: () -> Int,
    onComplete: ((usageFailures: Int, sensorFailures: Int) -> Unit)? = null,
): CombinedUploadOutcome {
    // Usage upload FIRST.
    val usageFailures = runUsageUpload()
    // Sensor upload SECOND — always runs, even when usage failed.
    val sensorFailures = runSensorUpload()

    onComplete?.invoke(usageFailures, sensorFailures)

    val usageOk = usageFailures == 0
    val sensorOk = sensorFailures == 0
    Log.i(
        TAG,
        "Combined upload complete: usageFailures=$usageFailures, sensorFailures=$sensorFailures",
    )
    return when {
        usageOk && sensorOk -> CombinedUploadOutcome.SUCCESS
        runAttemptCount > COMBINED_UPLOAD_MAX_ATTEMPTS -> {
            Log.e(
                TAG,
                "Combined upload failed after $runAttemptCount attempts, giving up",
            )
            CombinedUploadOutcome.FAILURE
        }
        else -> CombinedUploadOutcome.RETRY
    }
}
