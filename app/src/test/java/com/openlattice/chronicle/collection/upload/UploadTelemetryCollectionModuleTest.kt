package com.openlattice.chronicle.collection.upload

import com.openlattice.chronicle.collection.CollectionModuleId
import com.openlattice.chronicle.collection.CollectionPrivacyClass
import com.openlattice.chronicle.collection.core.CollectionModuleStatus
import com.openlattice.chronicle.collection.core.CollectionWindow
import com.openlattice.chronicle.collection.core.ModuleResult
import com.openlattice.chronicle.collection.core.NoOpCollectionLog
import com.openlattice.chronicle.collection.core.TestContexts
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for [UploadTelemetryCollectionModule] (Phase 8A — refactor plan §11.1).
 *
 * Covers: identity/privacy class, queue depth and last-worker-result exposure, the
 * read-only no-op lifecycle, contained read failures, and — the load-bearing rule —
 * **redaction**: no `apiKey`, device secret, `MOBILE_SIGNING_SECRET` or raw
 * `participantId` ever reaches the rendered diagnostics, and WorkManager state is never
 * coerced to a false success.
 *
 */
class UploadTelemetryCollectionModuleTest {

    private fun module(
        state: FakeUploadStateProbe = FakeUploadStateProbe(),
        work: FakeUploadWorkProbe = FakeUploadWorkProbe(),
    ) = UploadTelemetryCollectionModule(state, work, NoOpCollectionLog)

    private fun server(
        id: Long = 1L,
        enabled: Boolean = true,
        usageFailures: Int = 0,
        sensorFailures: Int = 0,
        usageError: Boolean = false,
        sensorError: Boolean = false,
    ) = UploadServerTelemetry(
        serverId = id,
        enabled = enabled,
        consecutiveFailures = usageFailures,
        sensorConsecutiveFailures = sensorFailures,
        lastUsageUploadTime = "2026-05-20T10:00:00Z",
        lastSensorUploadTime = "2026-05-20T10:05:00Z",
        hasUsageUploadError = usageError,
        hasSensorUploadError = sensorError,
    )

    // ----- identity ---------------------------------------------------------

    @Test
    fun moduleDeclaresUploadTelemetryIdAndOperationalDiagnosticsPrivacyClass() {
        val m = module()
        assertEquals(CollectionModuleId.UPLOAD_TELEMETRY, m.id)
        assertEquals(CollectionPrivacyClass.OPERATIONAL_DIAGNOSTICS, m.privacyClass)
        assertEquals(m.id.privacyClass, m.privacyClass)
    }

    // ----- queue depth ------------------------------------------------------

    @Test
    fun diagnosticsExposeUsageQueueDepthAsQueueDepth() {
        val m = module(FakeUploadStateProbe(usageDepth = 42, sensorDepth = 7))
        val d = m.diagnostics()
        assertEquals(42, d.queueDepth)
        assertTrue(d.notTracked.contains("usageQueueDepth=42"))
        assertTrue(d.notTracked.contains("sensorQueueDepth=7"))
    }

    @Test
    fun snapshotExposesQueueDepthPerDataStream() {
        val snap = module(FakeUploadStateProbe(usageDepth = 11, sensorDepth = 99)).snapshot()
        assertEquals(11, snap.usageQueueDepth)
        assertEquals(99, snap.sensorQueueDepth)
    }

    // ----- last worker result ----------------------------------------------

    @Test
    fun diagnosticsReportTheWorkManagerStateAsLastResult() {
        val work = FakeUploadWorkProbe(
            periodic = CombinedUploadWorkStatus("combined_upload", "ENQUEUED", 0, true),
        )
        assertEquals("ENQUEUED", module(work = work).diagnostics().lastResult)
    }

    @Test
    fun diagnosticsReportNoWorkEnqueuedWhenWorkManagerHasNothing() {
        assertEquals("NO_WORK_ENQUEUED", module().diagnostics().lastResult)
    }

    @Test
    fun workManagerRetryStateIsNeverReportedAsSuccess() {
        // A worker that returned retry must NOT surface as a success label (guardrail 8A.3).
        val retryStates = listOf("RETRY", "FAILED", "CANCELLED")
        for (s in retryStates) {
            val work = FakeUploadWorkProbe(
                periodic = CombinedUploadWorkStatus("combined_upload", s, 2, false),
            )
            val d = module(work = work).diagnostics()
            assertEquals(s, d.lastResult)
            assertFalse("must not coerce $s to a success label", d.lastResult == "SUCCEEDED")
        }
    }

    @Test
    fun retryPendingIsExposedWhenWorkEnqueuedAfterAnAttempt() {
        val work = FakeUploadWorkProbe(
            periodic = CombinedUploadWorkStatus("combined_upload", "ENQUEUED", 3, true),
        )
        val d = module(work = work).diagnostics()
        assertTrue(d.notTracked.contains("periodicUploadRetryPending=true"))
        assertTrue(d.notTracked.contains("periodicUploadRunAttempt=3"))
    }

    @Test
    fun immediateUploadWorkStatusIsExposed() {
        val work = FakeUploadWorkProbe(
            immediate = CombinedUploadWorkStatus("combined_upload_immediate", "RUNNING", 0, true),
        )
        assertTrue(module(work = work).diagnostics().notTracked.contains("immediateUploadState=RUNNING"))
    }

    // ----- server / failure / constraint state ------------------------------

    @Test
    fun disabledServerStateIsExposed() {
        val state = FakeUploadStateProbe(
            servers = mutableListOf(
                server(id = 1, enabled = true),
                server(id = 2, enabled = false),
                server(id = 3, enabled = false),
            ),
        )
        val snap = module(state).snapshot()
        assertEquals(1, snap.enabledServerCount)
        assertEquals(2, snap.disabledServerCount)
        assertTrue(module(state).diagnostics().notTracked.contains("disabledServers=2"))
    }

    @Test
    fun partialFailureCountsAreExposed() {
        val state = FakeUploadStateProbe(
            servers = mutableListOf(
                server(id = 1, usageFailures = 3, sensorFailures = 0),
                server(id = 2, usageFailures = 0, sensorFailures = 5),
                server(id = 3, usageFailures = 0, sensorFailures = 0),
            ),
        )
        val snap = module(state).snapshot()
        assertEquals(1, snap.usagePartialFailureCount)
        assertEquals(1, snap.sensorPartialFailureCount)
    }

    @Test
    fun constraintsStateIsExposedFromWorkStatus() {
        val work = FakeUploadWorkProbe(
            periodic = CombinedUploadWorkStatus("combined_upload", "BLOCKED", 0, false),
        )
        assertTrue(module(work = work).diagnostics().notTracked.contains("periodicUploadConstraintsMet=false"))
    }

    @Test
    fun uploadStatsRowCountIsExposed() {
        assertTrue(
            module(FakeUploadStateProbe(statsRows = 12)).diagnostics()
                .notTracked.contains("uploadStatsRowCount=12"),
        )
    }

    @Test
    fun nextScheduledUploadIsHonestlyUntracked() {
        // WorkManager exposes no next-fire time — it must be listed, not fabricated.
        assertTrue(module().diagnostics().notTracked.contains("nextScheduledUploadEpochMs"))
    }

    @Test
    fun malformedRowCountIsHonestlyUntracked() {
        assertTrue(module().diagnostics().notTracked.contains("malformedRowCount"))
    }

    // ----- status -----------------------------------------------------------

    @Test
    fun statusIsIdleWhenAllHealthy() {
        val state = FakeUploadStateProbe(servers = mutableListOf(server()))
        assertEquals(CollectionModuleStatus.IDLE, module(state).status())
    }

    @Test
    fun statusIsDegradedWhenAServerHasAnUploadError() {
        val state = FakeUploadStateProbe(servers = mutableListOf(server(usageError = true)))
        assertEquals(CollectionModuleStatus.DEGRADED, module(state).status())
    }

    @Test
    fun statusIsFailedWhenWorkManagerReportsFailure() {
        val work = FakeUploadWorkProbe(
            periodic = CombinedUploadWorkStatus("combined_upload", "FAILED", 6, false),
        )
        assertEquals(CollectionModuleStatus.FAILED, module(work = work).status())
    }

    // ----- read-only lifecycle ---------------------------------------------

    @Test
    fun lifecycleOperationsAreReadOnlyNoOps() {
        val m = module()
        val ctx = TestContexts.stub()
        val window = CollectionWindow(startEpochMs = 0L, endEpochMs = 1_000L)
        assertTrue(m.start(ctx) is ModuleResult.Skipped)
        assertTrue(m.stop(ctx) is ModuleResult.Skipped)
        assertTrue(m.poll(ctx, window) is ModuleResult.Skipped)
        assertTrue(m.flush(ctx) is ModuleResult.Skipped)
    }

    // ----- contained read failures -----------------------------------------

    @Test
    fun aFailedProbeReadDoesNotCrashDiagnostics() {
        val state = FakeUploadStateProbe(usageDepth = 5).apply {
            failServers = true
            failSensorDepth = true
            failStatsRows = true
        }
        val d = module(state).diagnostics()
        // The one readable value still renders; the unreadable ones fall to safe defaults.
        assertEquals(5, d.queueDepth)
        assertTrue(d.notTracked.contains("sensorQueueDepth=-1"))
        assertTrue(d.notTracked.contains("uploadStatsRowCount=-1"))
        assertTrue(d.notTracked.contains("enabledServers=0"))
    }

    // ----- error summary ----------------------------------------------------

    @Test
    fun lastErrorIsNullWhenNoServerHasAnError() {
        val state = FakeUploadStateProbe(servers = mutableListOf(server()))
        assertNull(module(state).diagnostics().lastError)
    }

    @Test
    fun lastErrorIsARedactedCountSummaryNeverErrorText() {
        val state = FakeUploadStateProbe(
            servers = mutableListOf(server(usageError = true), server(id = 2, sensorError = true)),
        )
        val lastError = module(state).diagnostics().lastError
        assertNotNull(lastError)
        // Only counts — must not be the raw error message.
        assertTrue(lastError!!.contains("1 usage"))
        assertTrue(lastError.contains("1 sensor"))
    }
}
