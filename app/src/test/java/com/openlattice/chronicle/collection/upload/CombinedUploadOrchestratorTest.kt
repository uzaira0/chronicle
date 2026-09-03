package com.openlattice.chronicle.collection.upload

import com.openlattice.chronicle.collection.core.NoOpCollectionLog
import com.openlattice.chronicle.collection.core.RecordingCollectionLog
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for [runCombinedUploadCore] — the pure combined-upload decision logic
 * extracted in Phase 8B (refactor plan §11.2 tests 11–19, guardrails 1 & 2).
 *
 * Covers every required combined-upload outcome with no Android `Context`, WorkManager,
 * or Room: both succeed, usage-fail/sensor-success, usage-success/sensor-fail,
 * both fail, repeated attempts, stats-cleanup failure, no eligible work, disabled modules,
 * immediate upload, and — the load-bearing rule — the worker never reports SUCCESS when
 * a delegate failed.
 *
 */
class CombinedUploadOrchestratorTest {

    private fun run(
        usage: Int,
        sensor: Int,
        attempt: Int = 0,
        cleanup: () -> Unit = {},
        log: com.openlattice.chronicle.collection.core.CollectionLog = NoOpCollectionLog,
        onComplete: ((Int, Int) -> Unit)? = null,
    ): CombinedUploadOutcome = runCombinedUploadCore(
        runAttemptCount = attempt,
        runUsageUpload = { usage },
        runSensorUpload = { sensor },
        cleanupStats = cleanup,
        log = log,
        onComplete = onComplete,
    )

    @Test
    fun bothSucceedYieldsSuccess() {
        assertEquals(CombinedUploadOutcome.SUCCESS, run(usage = 0, sensor = 0))
    }

    @Test
    fun usageFailSensorSuccessYieldsRetry() {
        assertEquals(CombinedUploadOutcome.RETRY, run(usage = 1, sensor = 0))
    }

    @Test
    fun usageSuccessSensorFailYieldsRetry() {
        assertEquals(CombinedUploadOutcome.RETRY, run(usage = 0, sensor = 3))
    }

    @Test
    fun bothFailYieldsRetry() {
        assertEquals(CombinedUploadOutcome.RETRY, run(usage = 2, sensor = 2))
    }

    @Test
    fun usageDelegateThrewIsTreatedAsFailure() {
        // -1 sentinel (delegate threw) is NOT 0 -> never SUCCESS.
        assertEquals(CombinedUploadOutcome.RETRY, run(usage = UPLOAD_DELEGATE_THREW, sensor = 0))
    }

    @Test
    fun sensorDelegateThrewIsTreatedAsFailure() {
        assertEquals(CombinedUploadOutcome.RETRY, run(usage = 0, sensor = UPLOAD_DELEGATE_THREW))
    }

    @Test
    fun bothDelegatesThrewIsTreatedAsFailure() {
        assertEquals(
            CombinedUploadOutcome.RETRY,
            run(usage = UPLOAD_DELEGATE_THREW, sensor = UPLOAD_DELEGATE_THREW),
        )
    }

    @Test
    fun repeatedFailureBelowCapStillRetries() {
        assertEquals(
            CombinedUploadOutcome.RETRY,
            run(usage = 1, sensor = 0, attempt = COMBINED_UPLOAD_MAX_ATTEMPTS),
        )
    }

    @Test
    fun repeatedFailureAboveCapYieldsFailure() {
        assertEquals(
            CombinedUploadOutcome.FAILURE,
            run(usage = 1, sensor = 1, attempt = COMBINED_UPLOAD_MAX_ATTEMPTS + 1),
        )
    }

    @Test
    fun successIsNeverDowngradedToFailureByAttemptCap() {
        // Even past the cap, a clean run is SUCCESS — the cap only converts RETRY->FAILURE.
        assertEquals(
            CombinedUploadOutcome.SUCCESS,
            run(usage = 0, sensor = 0, attempt = COMBINED_UPLOAD_MAX_ATTEMPTS + 5),
        )
    }

    @Test
    fun noEligibleWorkYieldsSuccess() {
        // The outer enrollment/module gate can intentionally skip both delegates.
        assertEquals(CombinedUploadOutcome.SUCCESS, run(usage = 0, sensor = 0))
    }

    @Test
    fun disabledModulesYieldSuccessWhenDelegatesReportZero() {
        // A disabled upload path runs no servers and reports 0 failures.
        assertEquals(CombinedUploadOutcome.SUCCESS, run(usage = 0, sensor = 0))
    }

    @Test
    fun immediateUploadUsesAttemptZeroAndRetriesOnFailure() {
        // The one-time immediate work starts at attempt 0; a failure retries, not fails.
        assertEquals(CombinedUploadOutcome.RETRY, run(usage = 1, sensor = 0, attempt = 0))
    }

    @Test
    fun usageRunsBeforeSensor() {
        val order = mutableListOf<String>()
        runCombinedUploadCore(
            runAttemptCount = 0,
            runUsageUpload = { order.add("usage"); 0 },
            runSensorUpload = { order.add("sensor"); 0 },
            cleanupStats = { order.add("cleanup") },
            log = NoOpCollectionLog,
        )
        assertEquals(listOf("usage", "sensor", "cleanup"), order)
    }

    @Test
    fun sensorStepRunsEvenWhenUsageFailed() {
        var sensorRan = false
        runCombinedUploadCore(
            runAttemptCount = 0,
            runUsageUpload = { UPLOAD_DELEGATE_THREW },
            runSensorUpload = { sensorRan = true; 0 },
            cleanupStats = {},
            log = NoOpCollectionLog,
        )
        assertTrue("sensor upload must run even after a usage failure", sensorRan)
    }

    @Test
    fun statsCleanupFailureDoesNotChangeASuccessfulOutcome() {
        val log = RecordingCollectionLog()
        val outcome = run(
            usage = 0,
            sensor = 0,
            cleanup = { throw RuntimeException("simulated cleanup failure") },
            log = log,
        )
        assertEquals(CombinedUploadOutcome.SUCCESS, outcome)
        assertTrue(
            "a cleanup failure must be logged, not silently swallowed",
            log.problems.any { it.message.contains("cleanup", ignoreCase = true) },
        )
    }

    @Test
    fun statsCleanupFailureDoesNotChangeAFailedOutcome() {
        val outcome = run(
            usage = 1,
            sensor = 0,
            cleanup = { throw RuntimeException("simulated cleanup failure") },
        )
        assertEquals(CombinedUploadOutcome.RETRY, outcome)
    }

    @Test
    fun onCompleteHookReceivesBothFailureCounts() {
        var seen: Pair<Int, Int>? = null
        run(usage = 4, sensor = 7, onComplete = { u, s -> seen = u to s })
        assertEquals(4 to 7, seen)
    }

    @Test
    fun workerNeverReportsSuccessWhenADelegateFailed() {
        // Exhaustive: SUCCESS iff BOTH failure counts are exactly 0.
        val failingCounts = listOf(UPLOAD_DELEGATE_THREW, 1, 2, 50)
        for (u in failingCounts) {
            for (attempt in 0..(COMBINED_UPLOAD_MAX_ATTEMPTS + 2)) {
                assertFalse(
                    "usage=$u must never yield SUCCESS",
                    run(usage = u, sensor = 0, attempt = attempt) == CombinedUploadOutcome.SUCCESS,
                )
                assertFalse(
                    "sensor=$u must never yield SUCCESS",
                    run(usage = 0, sensor = u, attempt = attempt) == CombinedUploadOutcome.SUCCESS,
                )
            }
        }
    }
}
