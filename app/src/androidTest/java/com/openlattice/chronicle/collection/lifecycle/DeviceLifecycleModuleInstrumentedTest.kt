package com.openlattice.chronicle.collection.lifecycle

import android.content.Intent
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.openlattice.chronicle.IsolatedChronicleTestDb
import com.openlattice.chronicle.collection.core.ModuleResult
import com.openlattice.chronicle.collection.core.NoOpCollectionLog
import com.openlattice.chronicle.collection.sink.LifecycleEventSink
import com.openlattice.chronicle.models.ExtractedUsageEvent
import com.openlattice.chronicle.serialization.JsonSerializer
import com.openlattice.chronicle.storage.ChronicleDb
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Instrumented coverage for [DeviceLifecycleCollectionModule] against the real
 * SQLCipher-backed [ChronicleDb] `dataQueue` table (refactor plan §8.2 step 17 — "Android
 * instrumented queue persistence test").
 *
 * This exercises the module → [LifecycleEventSink] → real-encrypted-Room `dataQueue`
 * path that the JVM fakes cannot: it proves a lifecycle event is durably persisted as a
 * `QueueEntry` whose serialized `ChronicleData` round-trips, and that the
 * `DeviceLifecycleEventRecorderTest` event mapping survives the encrypted write.
 *
 * Requires a connected device or emulator. When none is available the run is recorded as
 * a BLOCKER; the JVM `DeviceLifecycleCollectionModuleTest` plus the existing
 * `CollectionSinkInstrumentedTest` provide the strongest local proof of the boundary.
 */
@RunWith(AndroidJUnit4::class)
class DeviceLifecycleModuleInstrumentedTest {

    private lateinit var db: ChronicleDb
    private lateinit var isolatedDb: IsolatedChronicleTestDb

    @Before
    fun setUp() {
        isolatedDb = IsolatedChronicleTestDb.create("device_lifecycle")
        db = isolatedDb.db
    }

    @After
    fun tearDown() {
        isolatedDb.close()
    }

    /**
     * Always-accept dedupe store for the persistence tests, so the assertions are about
     * the Room write boundary, not the dedupe window (which has its own test below using
     * the real [PrefsLifecycleDedupeStore]). Defined here because the JVM-unit-test
     * `FakeLifecycleCollaborators` are not on the instrumented-test classpath.
     */
    private class AcceptAllDedupeStore : LifecycleDedupeStore {
        override fun shouldPersist(event: ExtractedUsageEvent, now: Long): Boolean = true
    }

    private fun module(
        enrolled: Boolean,
        onQueueSize: (Int) -> Unit = {},
    ) = DeviceLifecycleCollectionModule(
        sink = LifecycleEventSink(db.queueEntryData(), NoOpCollectionLog),
        dedupeStore = AcceptAllDedupeStore(),
        enrolled = { enrolled },
        updateQueueSize = onQueueSize,
        serializeQueueEntry = { data ->
            com.openlattice.chronicle.serialization.JsonSerializer.serializeQueueEntry(data)
        },
        log = NoOpCollectionLog,
    )

    @Test
    fun lifecycleEventPersistsAsAQueueEntryThroughSqlCipherDataQueue() {
        var reportedDepth = -1
        val m = module(enrolled = true, onQueueSize = { reportedDepth = it })
        val event = LifecycleEventMapper.eventForBroadcastAction(
            Intent.ACTION_POWER_CONNECTED,
            System.currentTimeMillis(),
        )!!

        val result = m.persist(listOf(event))

        assertEquals(ModuleResult.Ok(1), result)
        assertEquals(1, db.queueEntryData().getSize())
        assertEquals(1, reportedDepth)

        // The persisted QueueEntry's ChronicleData round-trips back to the same event.
        val stored = db.queueEntryData().getNextEntries(1).single()
        val data = JsonSerializer.deserializeQueueEntry(stored.data)
        assertEquals(1, data.size)
    }

    @Test
    fun batchOfLifecycleEventsPersistsAsOneQueueEntryRow() {
        val m = module(enrolled = true)
        val ts = System.currentTimeMillis()
        val events = listOf(
            LifecycleEventMapper.eventForBroadcastAction(Intent.ACTION_BATTERY_LOW, ts)!!,
            LifecycleEventMapper.eventForBroadcastAction(Intent.ACTION_BATTERY_OKAY, ts)!!,
        )

        assertEquals(ModuleResult.Ok(2), m.persist(events))
        // Batch write: one QueueEntry row holding a 2-element ChronicleData.
        assertEquals(1, db.queueEntryData().getSize())
        val data = JsonSerializer.deserializeQueueEntry(db.queueEntryData().getNextEntries(1).single().data)
        assertEquals(2, data.size)
    }

    @Test
    fun nonEnrolledLifecyclePersistWritesNothingToTheRealDataQueue() {
        val m = module(enrolled = false)
        val event = LifecycleEventMapper.lowMemoryEvent(80, System.currentTimeMillis())

        val result = m.persist(listOf(event))

        assertTrue("expected Skipped, got $result", result is ModuleResult.Skipped)
        assertEquals(0, db.queueEntryData().getSize())
    }

    @Test
    fun prefsDedupeStoreSuppressesARepeatWithinTheWindow() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val store = PrefsLifecycleDedupeStore(context)
        val event = LifecycleEventMapper.buildEvent(
            "network:wifi-${System.nanoTime()}",
            "Network Connected",
            0L,
        )
        val now = System.currentTimeMillis()

        assertTrue("first occurrence persists", store.shouldPersist(event, now))
        assertTrue("repeat within 2s window is suppressed", !store.shouldPersist(event, now + 500))
        assertTrue("after the 2s window it persists again", store.shouldPersist(event, now + 3_000))
    }
}
