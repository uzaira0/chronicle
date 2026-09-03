package com.openlattice.chronicle.collection.usage

import com.openlattice.chronicle.android.ChronicleSample
import com.openlattice.chronicle.collection.core.CollectionWindow
import com.openlattice.chronicle.collection.core.NoOpCollectionLog
import com.openlattice.chronicle.models.ExtractedUsageEvent
import com.openlattice.chronicle.serialization.JsonSerializer
import com.openlattice.chronicle.services.usage.buildUsageQueueEntries
import org.junit.Assert.assertEquals
import org.junit.Test
import java.util.TreeMap

/**
 * End-to-end `activityClass` preservation: usage poll → [ExtractedUsageEvent] →
 * `QueueEntry` serialization → deserialization (refactor plan §7.1 step 19, guardrail 1).
 *
 * This is the parity proof that the Android event `className` survives all the way
 * through the module poll and the on-disk `QueueEntry` byte payload, so that
 * `UploadExecutor.mapToModel` can still forward `datum.activityClass` into
 * `ChronicleUsageEvent` (refactor plan decision #12).
 */
class UsageActivityClassPreservationTest {

    @Test
    fun activityClassSurvivesPollThenQueueEntrySerializationRoundTrip() {
        val poller = FakeUsageEventPoller(nextResult = FakeUsageEventPoller.oneEvent("com.example.DeepActivity"))
        val module = UsageEventsCollectionModule(
            poller = poller,
            checkpointStore = FakeUsagePollCheckpointStore(),
            previousPollTimestampFallback = { 1_000L },
            log = NoOpCollectionLog,
        )

        // 1. Poll through the module.
        val outcome = module.pollWindow(TreeMap(), CollectionWindow(0L, 8_000L))
        val polled = outcome.events.single() as ExtractedUsageEvent
        assertEquals("com.example.DeepActivity", polled.activityClass)

        // 2. Serialize through the exact QueueEntry builder the worker uses.
        val entries = buildUsageQueueEntries(outcome.events, firstWriteTimestamp = 100L) { 1L }
        assertEquals(1, entries.size)

        // 3. Deserialize the on-disk byte payload and confirm activityClass survived.
        val restored = JsonSerializer.deserializeQueueEntry(entries.single().data)
        val event = restored.single() as ExtractedUsageEvent
        assertEquals("com.example.DeepActivity", event.activityClass)
    }

    @Test
    fun nullActivityClassRoundTripsAsNull() {
        val poller = FakeUsageEventPoller(nextResult = FakeUsageEventPoller.oneEvent(null))
        val module = UsageEventsCollectionModule(
            poller = poller,
            checkpointStore = FakeUsagePollCheckpointStore(),
            previousPollTimestampFallback = { 1_000L },
            log = NoOpCollectionLog,
        )

        val outcome = module.pollWindow(TreeMap(), CollectionWindow(0L, 8_000L))
        val entries = buildUsageQueueEntries(outcome.events, firstWriteTimestamp = 100L) { 1L }
        val restored: List<ChronicleSample> = JsonSerializer.deserializeQueueEntry(entries.single().data)

        assertEquals(null, (restored.single() as ExtractedUsageEvent).activityClass)
    }
}
