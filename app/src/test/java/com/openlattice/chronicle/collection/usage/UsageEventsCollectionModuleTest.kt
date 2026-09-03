package com.openlattice.chronicle.collection.usage

import com.openlattice.chronicle.android.ChronicleData
import com.openlattice.chronicle.collection.CollectionModuleId
import com.openlattice.chronicle.collection.CollectionPrivacyClass
import com.openlattice.chronicle.collection.core.CollectionModuleStatus
import com.openlattice.chronicle.collection.core.CollectionWindow
import com.openlattice.chronicle.collection.core.FixedCollectionClock
import com.openlattice.chronicle.collection.core.ModuleResult
import com.openlattice.chronicle.collection.core.NoOpCollectionLog
import com.openlattice.chronicle.models.ExtractedUsageEvent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.TreeMap

/**
 * JVM unit coverage for [UsageEventsCollectionModule] — the Phase 4A poll wrapper.
 *
 * Drives the module over the [FakeUsageEventPoller] / [FakeUsagePollCheckpointStore]
 * seams (no Android `Context`, no Robolectric). Proves the wrapper preserves the
 * two-timestamp poll behaviour, the checkpoint cursor, empty-result behaviour,
 * `activityClass` preservation, diagnostics, and failure handling (refactor plan §7.1).
 */
class UsageEventsCollectionModuleTest {

    private fun module(
        poller: FakeUsageEventPoller,
        checkpointStore: FakeUsagePollCheckpointStore,
        fallback: () -> Long = { 1_000L },
        clock: FixedCollectionClock = FixedCollectionClock(9_999L),
    ) = UsageEventsCollectionModule(poller, checkpointStore, fallback, clock, NoOpCollectionLog)

    private fun window(end: Long) = CollectionWindow(startEpochMs = 0L, endEpochMs = end)

    @Test
    fun moduleDeclaresUsageEventsIdentityAndPrivacyClass() {
        val m = module(FakeUsageEventPoller(), FakeUsagePollCheckpointStore())
        assertEquals(CollectionModuleId.USAGE_EVENTS, m.id)
        assertEquals(CollectionPrivacyClass.BEHAVIORAL_METADATA, m.privacyClass)
        assertEquals(m.id.privacyClass, m.privacyClass)
    }

    @Test
    fun pollUsesCheckpointTimestampAsPreviousWhenAcheckpointExists() {
        val poller = FakeUsageEventPoller()
        val store = FakeUsagePollCheckpointStore(storedTimestamp = 5_000L)
        val m = module(poller, store, fallback = { 1_000L })

        m.pollWindow(TreeMap(), window(end = 8_000L))

        // previous = checkpoint (5000), not the fallback (1000); current = window end.
        assertEquals(5_000L, poller.lastPreviousPollTimestamp)
        assertEquals(8_000L, poller.lastCurrentPollTimestamp)
    }

    @Test
    fun pollFallsBackToSensorTimestampWhenNoCheckpointRowExists() {
        val poller = FakeUsageEventPoller()
        val store = FakeUsagePollCheckpointStore(storedTimestamp = null)
        val m = module(poller, store, fallback = { 1_234L })

        m.pollWindow(TreeMap(), window(end = 8_000L))

        // No checkpoint row → fallback used as previous (legacy `?: previousPollTimestamp()`).
        assertEquals(1_234L, poller.lastPreviousPollTimestamp)
    }

    @Test
    fun pollForwardsUsersMapToThePoller() {
        val poller = FakeUsageEventPoller()
        val users = TreeMap<Long, String>().apply { put(100L, "participant-x") }
        val m = module(poller, FakeUsagePollCheckpointStore())

        m.pollWindow(users, window(end = 8_000L))

        assertEquals(users, poller.lastUsers)
    }

    @Test
    fun emptyPollReturnsEmptyChronicleDataAndCommitsCheckpointWhenOrchestratorCommits() {
        val poller = FakeUsageEventPoller(nextResult = ChronicleData(emptyList()))
        val store = FakeUsagePollCheckpointStore()
        val m = module(poller, store)

        val outcome = m.pollWindow(TreeMap(), window(end = 8_000L))

        assertTrue(outcome.events.isEmpty())
        assertEquals(8_000L, outcome.currentPollTimestamp)
        // pollWindow itself never commits — that is the orchestrator's transactional job.
        assertEquals(0, store.commitCount)

        m.commitCheckpoint(outcome.currentPollTimestamp)
        assertEquals(1, store.commitCount)
        assertEquals(8_000L, store.storedTimestamp)
    }

    @Test
    fun activityClassIsPreservedThroughPollIntoExtractedUsageEvent() {
        val poller = FakeUsageEventPoller(nextResult = FakeUsageEventPoller.oneEvent("com.example.MainActivity"))
        val m = module(poller, FakeUsagePollCheckpointStore())

        val outcome = m.pollWindow(TreeMap(), window(end = 8_000L))

        val event = outcome.events.single() as ExtractedUsageEvent
        assertEquals("com.example.MainActivity", event.activityClass)
    }

    @Test
    fun nullActivityClassIsPreservedAsNullThroughPoll() {
        val poller = FakeUsageEventPoller(nextResult = FakeUsageEventPoller.oneEvent(null))
        val m = module(poller, FakeUsagePollCheckpointStore())

        val outcome = m.pollWindow(TreeMap(), window(end = 8_000L))

        assertNull((outcome.events.single() as ExtractedUsageEvent).activityClass)
    }

    @Test
    fun diagnosticsReportLastPollEventCountAndCheckpointTimestamp() {
        val poller = FakeUsageEventPoller(nextResult = FakeUsageEventPoller.oneEvent("com.example.Foo"))
        val store = FakeUsagePollCheckpointStore()
        val clock = FixedCollectionClock(123_456L)
        val m = module(poller, store, clock = clock)

        val outcome = m.pollWindow(TreeMap(), window(end = 8_000L))
        m.commitCheckpoint(outcome.currentPollTimestamp)

        val d = m.diagnostics()
        assertEquals(CollectionModuleId.USAGE_EVENTS, d.moduleId)
        assertEquals(123_456L, d.lastRunEpochMs)
        assertEquals(1, d.itemsCollected)
        assertEquals("OK", d.lastResult)
        assertNull(d.lastError)
        assertTrue("checkpoint timestamp expected in notTracked: ${d.notTracked}",
            d.notTracked.any { it == "checkpointTimestamp=8000" })
    }

    @Test
    fun diagnosticsBeforeAnyCheckpointCommitMarkCheckpointAsNotTracked() {
        val m = module(FakeUsageEventPoller(), FakeUsagePollCheckpointStore())
        assertTrue(m.diagnostics().notTracked.contains("checkpointTimestamp"))
    }

    @Test
    fun usageStatsManagerFailureSurfacesAndIsRecordedInDiagnosticsNotSwallowed() {
        val boom = RuntimeException("UsageStatsManager.queryEvents blew up")
        val poller = FakeUsageEventPoller(failWith = boom)
        val m = module(poller, FakeUsagePollCheckpointStore())

        var thrown: Exception? = null
        try {
            m.pollWindow(TreeMap(), window(end = 8_000L))
        } catch (e: Exception) {
            thrown = e
        }

        assertEquals(boom, thrown)
        val d = m.diagnostics()
        assertEquals("FAILED", d.lastResult)
        assertEquals(0, d.itemsCollected)
        assertTrue(d.lastError!!.contains("UsageStatsManager"))
        assertEquals(CollectionModuleStatus.FAILED, m.status())
    }

    @Test
    fun checkpointDoesNotAdvanceWhenPollFailsBeforeCommit() {
        // Crash-recovery parity: a poll failure before the orchestrator commits the
        // checkpoint must leave the cursor untouched, so the next run re-polls the window.
        val poller = FakeUsageEventPoller(failWith = RuntimeException("partial poll failure"))
        val store = FakeUsagePollCheckpointStore(storedTimestamp = 5_000L)
        val m = module(poller, store)

        try {
            m.pollWindow(TreeMap(), window(end = 9_000L))
        } catch (_: Exception) {
            // expected — orchestrator never reaches commitCheckpoint.
        }

        assertEquals(0, store.commitCount)
        assertEquals(5_000L, store.storedTimestamp) // cursor unchanged
    }

    @Test
    fun pullModuleStartStopFlushAreNoOpSkips() {
        val m = module(FakeUsageEventPoller(), FakeUsagePollCheckpointStore())
        val ctx = com.openlattice.chronicle.collection.core.TestContexts.stub()
        assertTrue(m.start(ctx) is ModuleResult.Skipped)
        assertTrue(m.stop(ctx) is ModuleResult.Skipped)
        assertTrue(m.flush(ctx) is ModuleResult.Skipped)
    }

    @Test
    fun disabledUsageModuleIsANoOpSkipForEveryOperation() {
        // When the settings resolver disables usage_events, the registered module is a
        // DisabledCollectionModule — every worker-path operation is a no-op Skipped,
        // writing nothing (design §1C.1). This is the "module disabled" worker path.
        val disabled = com.openlattice.chronicle.collection.core.DisabledCollectionModule(
            CollectionModuleId.USAGE_EVENTS,
            reason = "usage_events disabled by settings",
        )
        val ctx = com.openlattice.chronicle.collection.core.TestContexts.stub()

        assertEquals(CollectionModuleStatus.DISABLED, disabled.status())
        assertTrue(disabled.poll(ctx, window(8_000L)) is ModuleResult.Skipped)
        assertTrue(disabled.start(ctx) is ModuleResult.Skipped)
        assertEquals(0, disabled.diagnostics().itemsCollected)
    }

    @Test
    fun contractPollReturnsOkWithEventCountForIntrospection() {
        val poller = FakeUsageEventPoller(nextResult = FakeUsageEventPoller.oneEvent("com.example.Foo"))
        val m = module(poller, FakeUsagePollCheckpointStore())
        val ctx = com.openlattice.chronicle.collection.core.TestContexts.stub()

        val result = m.poll(ctx, window(end = 8_000L))

        assertEquals(ModuleResult.Ok(1), result)
        // Introspection poll must not commit the checkpoint.
        assertFalse(m.diagnostics().notTracked.any { it.startsWith("checkpointTimestamp=") })
    }
}
