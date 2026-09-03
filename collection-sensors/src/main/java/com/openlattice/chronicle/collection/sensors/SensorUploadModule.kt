package com.openlattice.chronicle.collection.sensors

/**
 * Upload-module interface wrapping the sensor data upload (refactor plan §9.3 step 3 —
 * "wrap delegate in an upload module interface").
 *
 * `SensorUploadWorkerDelegate` executes the `/android/sensors` route in immutable
 * oldest-first batches, retains explicit TTL + cap cleanup, and on the normal delivery path
 * deletes exact IDs only after every configured destination has a durable acknowledgement.
 * Malformed rows are quarantined and counted. This interface is the seam the upload callers
 * (`CombinedUploadWorker`, `SensorUploadWorker`) talk to when the Phase 6C migration
 * switch is on, so the upload is a module-owned boundary rather than a free function call.
 *
 * The active sensor queue is bounded at 4,000,000 rows: slightly above the 3,628,800-row
 * seven-day worst-case envelope at six aggregate samples/second, and roughly fifteen days at
 * the observed three/second Pixel rate. TTL/cap trimming runs in deterministic 10,000-row SQL
 * chunks, so cleanup never materializes millions of sample IDs in the Android heap. Capacity
 * drops remain explicit in the delegate's cleanup result and warning log.
 *
 */
public interface SensorUploadModule {

    /**
     * Runs one sensor-upload pass.
     *
     * @return a [SensorUploadResult] carrying the per-server failure count (the value the
     *   legacy `SensorUploadWorkerDelegate.execute()` returned) and the malformed-sample
     *   count quarantined during this run.
     */
    public fun upload(): SensorUploadResult
}

/**
 * Outcome of a [SensorUploadModule.upload] pass.
 *
 * @property serverFailureCount number of servers that failed during the run; `0` is full
 *   success. Callers use this for retry policy exactly as they used the legacy
 *   `SensorUploadWorkerDelegate.execute()` return value — the combined-upload
 *   retry/failure semantics are unchanged.
 * @property malformedSampleCount number of corrupt sensor samples moved to the encrypted local
 *   dead-letter table during the run. A corrupt row does not abort the valid remainder of the batch.
 */
public data class SensorUploadResult(
    val serverFailureCount: Int,
    val malformedSampleCount: Int,
) {
    /** Whether every server succeeded. */
    public val isFullSuccess: Boolean get() = serverFailureCount == 0
}
