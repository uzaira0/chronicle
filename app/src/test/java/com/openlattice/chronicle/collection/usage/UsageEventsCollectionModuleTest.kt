package com.openlattice.chronicle.collection.usage

import com.openlattice.chronicle.android.ChronicleData
import com.openlattice.chronicle.collection.core.CollectionWindow
import com.openlattice.chronicle.models.ExtractedUsageEvent
import org.junit.Assert.assertEquals
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
    ) = UsageEventsCollectionModule(poller, checkpointStore, fallback)

    private fun window(end: Long) = CollectionWindow(startEpochMs = 0L, endEpochMs = end)

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

        val outcome = requireNotNull(m.pollWindow(TreeMap(), window(end = 8_000L)))

        assertTrue(outcome.events.isEmpty())
        assertEquals(8_000L, outcome.currentPollTimestamp)
        // pollWindow itself never commits — that is the orchestrator's transactional job.
        assertEquals(0, store.commitCount)

        m.commitCheckpoint(outcome.currentPollTimestamp)
        assertEquals(1, store.commitCount)
        assertEquals(8_000L, store.storedTimestamp)
    }

    /**
     * A poll stamps its end before reading the cursor; a settings flush may commit a later end in between. The poll then
     * reads an empty, inverted window, and committing its end would rewind the cursor and re-queue the flushed events.
     */
    @Test
    fun aPollWhoseEndAnotherPollAlreadyPassedNeverRewindsTheCursor() {
        val store = FakeUsagePollCheckpointStore(storedTimestamp = 5_000L)
        val m = module(FakeUsageEventPoller(), store)
        store.commitPollTimestamp(9_000L) // the flush, between this poll's end stamp and its cursor read

        val outcome = requireNotNull(m.pollWindow(TreeMap(), window(end = 8_000L)))
        m.commitCheckpoint(outcome)

        assertEquals(9_000L, store.storedTimestamp)
    }

    

    @Test
    fun activityClassIsPreservedThroughPollIntoExtractedUsageEvent() {
        val poller = FakeUsageEventPoller(nextResult = FakeUsageEventPoller.oneEvent("com.example.MainActivity"))
        val m = module(poller, FakeUsagePollCheckpointStore())

        val outcome = requireNotNull(m.pollWindow(TreeMap(), window(end = 8_000L)))

        val event = outcome.events.single() as ExtractedUsageEvent
        assertEquals("com.example.MainActivity", event.activityClass)
    }

    @Test
    fun nullActivityClassIsPreservedAsNullThroughPoll() {
        val poller = FakeUsageEventPoller(nextResult = FakeUsageEventPoller.oneEvent(null))
        val m = module(poller, FakeUsagePollCheckpointStore())

        val outcome = requireNotNull(m.pollWindow(TreeMap(), window(end = 8_000L)))

        assertNull((outcome.events.single() as ExtractedUsageEvent).activityClass)
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

    

}
