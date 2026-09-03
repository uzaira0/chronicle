package com.openlattice.chronicle.collection.usage

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.openlattice.chronicle.IsolatedChronicleTestDb
import com.openlattice.chronicle.android.ChronicleData
import com.openlattice.chronicle.collection.core.CollectionWindow
import com.openlattice.chronicle.collection.core.NoOpCollectionLog
import com.openlattice.chronicle.collection.sink.UsageEventSink
import com.openlattice.chronicle.models.ExtractedUsageEvent
import com.openlattice.chronicle.serialization.JsonSerializer
import com.openlattice.chronicle.services.usage.buildUsageQueueEntries
import com.openlattice.chronicle.storage.ChronicleDb
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.time.OffsetDateTime
import java.util.NavigableMap
import java.util.TreeMap

/**
 * Instrumented-test [UsageEventPoller] returning a fixed [ChronicleData]. The JVM
 * `FakeUsageEventPoller` lives in `src/test/` and is not visible to `androidTest`, so
 * this minimal stand-in is declared here.
 */
private class StubUsageEventPoller(private val result: ChronicleData) : UsageEventPoller {
    override fun poll(
        previousPollTimestamp: Long,
        currentPollTimestamp: Long,
        users: NavigableMap<Long, String>,
    ): ChronicleData = result
}

/**
 * Phase 4B instrumented coverage: drives [UsageEventsCollectionModule] +
 * [UsageEventSink] + [UsageModulePersistence] against the real SQLCipher-backed
 * [ChronicleDb] (refactor plan §7.2 step 18 — "Android instrumented test for real Room
 * write").
 *
 * Proves the module path lands both the `dataQueue` row and the `usage_poll_checkpoints`
 * row through the real encrypted Room schema v9 and the real `runInTransaction`. Requires
 * a connected device/emulator; when none is available the run is a BLOCKER and the JVM
 * `UsageEventsCollectionModuleTest` / `UsageModulePersistenceTest` are the strongest
 * local proof.
 */
@RunWith(AndroidJUnit4::class)
class UsageEventsModuleInstrumentedTest {

    private lateinit var db: ChronicleDb
    private lateinit var isolatedDb: IsolatedChronicleTestDb

    @Before
    fun setUp() {
        isolatedDb = IsolatedChronicleTestDb.create("usage_events")
        db = isolatedDb.db
    }

    @After
    fun tearDown() {
        isolatedDb.close()
    }

    private fun event(activityClass: String?) = ExtractedUsageEvent(
        appPackageName = "com.example.app",
        interactionType = "Activity Resumed",
        timestamp = OffsetDateTime.parse("2026-05-20T00:00:00Z"),
        timezone = "UTC",
        user = "",
        applicationLabel = "Example",
        activityClass = activityClass,
    )

    @Test
    fun moduleManagerPathWritesUsageRowAndCheckpointThroughRealRoom() {
        val current = System.currentTimeMillis()
        val poller = StubUsageEventPoller(ChronicleData(listOf(event("com.example.RealActivity"))))
        val module = UsageEventsCollectionModule(
            poller = poller,
            checkpointStore = DaoUsagePollCheckpointStore(db.usagePollCheckpointDao()),
            previousPollTimestampFallback = { current - 900_000L },
            log = NoOpCollectionLog,
        )
        val sink = UsageEventSink(db.queueEntryData(), NoOpCollectionLog)

        val outcome = module.pollWindow(TreeMap(), CollectionWindow(0L, current))
        val entries = buildUsageQueueEntries(outcome.events, System.currentTimeMillis()) { System.nanoTime() }
        UsageModulePersistence.persist(
            entries = entries,
            currentPollTimestamp = current,
            sink = sink,
            commitCheckpoint = module::commitCheckpoint,
            transaction = { body -> db.runInTransaction(body) },
            log = NoOpCollectionLog,
        )

        // dataQueue row landed and activityClass survived the on-disk byte payload.
        val stored = db.queueEntryData().getNextEntries(10)
        assertEquals(1, stored.size)
        val restored = JsonSerializer.deserializeQueueEntry(stored.single().data)
        assertEquals("com.example.RealActivity", (restored.single() as ExtractedUsageEvent).activityClass)

        // usage_poll_checkpoints row advanced to the current poll timestamp.
        assertEquals(
            current,
            db.usagePollCheckpointDao()
                .getLastPollTimestamp(com.openlattice.chronicle.sensors.USAGE_EVENTS_SENSOR_CHECKPOINT),
        )
    }

    @Test
    fun emptyPollAdvancesCheckpointWithoutWritingRowsThroughRealRoom() {
        val current = System.currentTimeMillis()
        val module = UsageEventsCollectionModule(
            poller = StubUsageEventPoller(ChronicleData(emptyList())),
            checkpointStore = DaoUsagePollCheckpointStore(db.usagePollCheckpointDao()),
            previousPollTimestampFallback = { current - 900_000L },
            log = NoOpCollectionLog,
        )
        val sink = UsageEventSink(db.queueEntryData(), NoOpCollectionLog)

        val outcome = module.pollWindow(TreeMap(), CollectionWindow(0L, current))
        UsageModulePersistence.persist(
            entries = buildUsageQueueEntries(outcome.events, System.currentTimeMillis()) { System.nanoTime() },
            currentPollTimestamp = current,
            sink = sink,
            commitCheckpoint = module::commitCheckpoint,
            transaction = { body -> db.runInTransaction(body) },
            log = NoOpCollectionLog,
        )

        assertTrue(db.queueEntryData().getNextEntries(10).isEmpty())
        assertEquals(
            current,
            db.usagePollCheckpointDao()
                .getLastPollTimestamp(com.openlattice.chronicle.sensors.USAGE_EVENTS_SENSOR_CHECKPOINT),
        )
    }
}
