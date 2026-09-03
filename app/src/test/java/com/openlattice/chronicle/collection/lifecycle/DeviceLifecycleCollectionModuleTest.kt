package com.openlattice.chronicle.collection.lifecycle

import com.openlattice.chronicle.collection.CollectionModuleId
import com.openlattice.chronicle.collection.CollectionPrivacyClass
import com.openlattice.chronicle.collection.core.CollectionModuleStatus
import com.openlattice.chronicle.collection.core.CollectionWindow
import com.openlattice.chronicle.collection.core.FixedCollectionClock
import com.openlattice.chronicle.collection.core.ModuleResult
import com.openlattice.chronicle.collection.core.NoOpCollectionLog
import com.openlattice.chronicle.collection.core.RecordingCollectionLog
import com.openlattice.chronicle.collection.core.TestContexts
import com.openlattice.chronicle.collection.sink.FakeStorageQueue
import com.openlattice.chronicle.collection.sink.LifecycleEventSink
import com.openlattice.chronicle.models.ExtractedUsageEvent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * JVM unit coverage for [DeviceLifecycleCollectionModule] — the Phase 5 lifecycle module.
 *
 * Drives the module over the [FakeStorageQueue] / [FakeLifecycleDedupeStore] /
 * [FixedCollectionClock] seams (no Android `Context`, no Robolectric). Proves the module
 * preserves: every lifecycle event mapping; the non-enrolled skip; the 2-second dedupe
 * window (idempotency on repeated broadcasts); the batch `QueueEntry` write; the
 * post-write queue-size update; queue-write-failure surfaced as `Failed`; async executor
 * failure visibility in diagnostics; and crash-recovery — no row leaked when the write
 * fails (refactor plan §8.1–8.2).
 */
class DeviceLifecycleCollectionModuleTest {

    private fun event(interactionType: String, activityClass: String, ts: Long = 1_700_000_000_000L) =
        LifecycleEventMapper.buildEvent(activityClass, interactionType, ts)

    private fun module(
        dao: FakeStorageQueue = FakeStorageQueue(),
        dedupe: LifecycleDedupeStore = AlwaysAcceptLifecycleDedupeStore(),
        enrolled: () -> Boolean = { true },
        onQueueSize: (Int) -> Unit = {},
        clock: FixedCollectionClock = FixedCollectionClock(50_000L),
        log: com.openlattice.chronicle.collection.core.CollectionLog = NoOpCollectionLog,
    ) = DeviceLifecycleCollectionModule(
        sink = LifecycleEventSink(dao, NoOpCollectionLog),
        dedupeStore = dedupe,
        enrolled = enrolled,
        updateQueueSize = onQueueSize,
        serializeQueueEntry = { data ->
            com.openlattice.chronicle.serialization.JsonSerializer.serializeQueueEntry(data)
        },
        clock = clock,
        log = log,
    )

    @Test
    fun moduleDeclaresDeviceLifecycleIdentityAndPrivacyClass() {
        val m = module()
        assertEquals(CollectionModuleId.DEVICE_LIFECYCLE, m.id)
        assertEquals(CollectionPrivacyClass.DEVICE_STATE_METADATA, m.privacyClass)
        assertEquals(m.id.privacyClass, m.privacyClass)
    }

    @Test
    fun emptyEventListIsIdempotentNoOpOk() {
        val dao = FakeStorageQueue()
        val m = module(dao)
        assertEquals(ModuleResult.Ok(0), m.persist(emptyList()))
        assertEquals(0, dao.getSize())
    }

    @Test
    fun nonEnrolledParticipantSkipsTheWriteAndPersistsNothing() {
        val dao = FakeStorageQueue()
        val m = module(dao, enrolled = { false })

        val result = m.persist(listOf(event("Device Startup", "android.intent.action.BOOT_COMPLETED")))

        assertTrue("expected Skipped, got $result", result is ModuleResult.Skipped)
        assertEquals("non-enrolled write must persist nothing", 0, dao.getSize())
    }

    @Test
    fun enrolledParticipantBatchWritesAllEventsAsOneQueueEntry() {
        val dao = FakeStorageQueue()
        val m = module(dao)

        val result = m.persist(
            listOf(
                event("Screen Interactive", "android.intent.action.SCREEN_ON"),
                event("Keyguard Hidden", "android.intent.action.USER_PRESENT"),
            ),
        )

        assertEquals(ModuleResult.Ok(2), result)
        // Batch write: both events land in ONE QueueEntry row (one ChronicleData), exactly
        // as the legacy recordNow built a single entry per call.
        assertEquals(1, dao.getSize())
    }

    @Test
    fun successfulWriteUpdatesUploadQueueSizeWithNewDepth() {
        val dao = FakeStorageQueue()
        var reportedDepth = -1
        val m = module(dao, onQueueSize = { reportedDepth = it })

        m.persist(listOf(event("Device Shutdown", "android.intent.action.ACTION_SHUTDOWN")))

        // Post-write side effect preserved: queue-size pref updated with the new depth.
        assertEquals(1, reportedDepth)
    }

    @Test
    fun queueWriteFailureSurfacesAsFailedAndIsRecordedInDiagnostics() {
        val dao = FakeStorageQueue().apply { failNextInsert = true }
        val log = RecordingCollectionLog()
        val m = module(dao, log = log)

        val result = m.persist(listOf(event("Battery Low", "android.intent.action.BATTERY_LOW")))

        assertTrue("expected Failed, got $result", result is ModuleResult.Failed)
        val d = m.diagnostics()
        assertEquals("FAILED", d.lastResult)
        assertEquals(CollectionModuleStatus.FAILED, m.status())
        assertTrue("failure must be logged, not swallowed", log.problems.isNotEmpty())
    }

    @Test
    fun queueWriteFailureLeavesNoRowBehindForCrashRecovery() {
        val dao = FakeStorageQueue().apply { failNextInsert = true }
        val m = module(dao)

        m.persist(listOf(event("Battery Okay", "android.intent.action.BATTERY_OKAY")))

        // Crash-recovery parity: a failed lifecycle write must not leave a partial row;
        // the broadcast is simply lost (same as the legacy recorder, which would throw
        // out of recordNow before the queue-size update).
        assertEquals(0, dao.getSize())
    }

    @Test
    fun repeatedBroadcastWithinDedupeWindowIsSuppressed() {
        val dao = FakeStorageQueue()
        val clock = FixedCollectionClock(10_000L)
        val m = module(dao, dedupe = FakeLifecycleDedupeStore(), clock = clock)
        val screenOn = event("Screen Interactive", "android.intent.action.SCREEN_ON")

        // First broadcast persists.
        assertEquals(ModuleResult.Ok(1), m.persist(listOf(screenOn)))
        // Immediate repeat within 2s — suppressed; nothing new written.
        clock.advance(500L)
        assertEquals(ModuleResult.Ok(0), m.persist(listOf(screenOn)))
        assertEquals("dedupe must suppress the repeat", 1, dao.getSize())
    }

    @Test
    fun broadcastAfterDedupeWindowIsPersistedAgain() {
        val dao = FakeStorageQueue()
        val clock = FixedCollectionClock(10_000L)
        val m = module(dao, dedupe = FakeLifecycleDedupeStore(), clock = clock)
        val screenOff = event("Screen Non-Interactive", "android.intent.action.SCREEN_OFF")

        assertEquals(ModuleResult.Ok(1), m.persist(listOf(screenOff)))
        // After the 2s window the same event is a fresh occurrence.
        clock.advance(3_000L)
        assertEquals(ModuleResult.Ok(1), m.persist(listOf(screenOff)))
        assertEquals(2, dao.getSize())
    }

    @Test
    fun allDuplicateEventsInOneCallIsANoOpOk() {
        val dao = FakeStorageQueue()
        val clock = FixedCollectionClock(10_000L)
        val m = module(dao, dedupe = FakeLifecycleDedupeStore(), clock = clock)
        val e = event("Battery Charging", "android.intent.action.ACTION_POWER_CONNECTED")

        m.persist(listOf(e))
        clock.advance(100L)
        // Second call: the only event is a duplicate → no row written, still a success.
        assertEquals(ModuleResult.Ok(0), m.persist(listOf(e)))
        assertEquals(1, dao.getSize())
    }

    @Test
    fun droppedDuplicateCountIsReportedInDiagnostics() {
        val dao = FakeStorageQueue()
        val clock = FixedCollectionClock(10_000L)
        val m = module(dao, dedupe = FakeLifecycleDedupeStore(), clock = clock)
        val e = event("Network Connected", "network:wifi")

        m.persist(listOf(e))
        clock.advance(100L)
        m.persist(listOf(e)) // dropped
        clock.advance(100L)
        m.persist(listOf(e)) // dropped

        val d = m.diagnostics()
        assertTrue(
            "dropped-duplicate count expected in notTracked: ${d.notTracked}",
            d.notTracked.any { it == "droppedDuplicateCount=2" },
        )
    }

    @Test
    fun diagnosticsReportLastEventInteractionTypeAndTimestamp() {
        val dao = FakeStorageQueue()
        val m = module(dao, clock = FixedCollectionClock(987_654L))

        m.persist(listOf(event("Device Startup", "android.intent.action.BOOT_COMPLETED")))

        val d = m.diagnostics()
        assertEquals(CollectionModuleId.DEVICE_LIFECYCLE, d.moduleId)
        assertEquals(987_654L, d.lastRunEpochMs)
        assertEquals(1, d.itemsCollected)
        assertEquals("OK", d.lastResult)
        assertTrue(
            "last event expected in notTracked: ${d.notTracked}",
            d.notTracked.any { it == "lastEvent=Device Startup@987654" },
        )
    }

    @Test
    fun diagnosticsBeforeAnyRunMarkLastEventAsNotTracked() {
        val d = module().diagnostics()
        assertTrue(d.notTracked.contains("lastEvent"))
        assertTrue(d.notTracked.any { it == "droppedDuplicateCount=0" })
    }

    @Test
    fun asyncExecutorFailureIsVisibleInDiagnosticsNotSwallowed() {
        val log = RecordingCollectionLog()
        val m = module(log = log)
        val boom = IllegalStateException("background executor blew up")

        m.recordAsyncFailure(boom)

        val d = m.diagnostics()
        assertEquals("FAILED", d.lastResult)
        assertEquals(CollectionModuleStatus.FAILED, m.status())
        assertTrue("async failure must be logged", log.problems.isNotEmpty())
        assertTrue(d.lastError!!.contains("async lifecycle persist failed"))
    }

    @Test
    fun pushAndPollContractMethodsAreNoOpSkips() {
        val m = module()
        val ctx = TestContexts.stub()
        val window = CollectionWindow(startEpochMs = 0L, endEpochMs = 1_000L)
        assertTrue(m.start(ctx) is ModuleResult.Skipped)
        assertTrue(m.stop(ctx) is ModuleResult.Skipped)
        assertTrue(m.poll(ctx, window) is ModuleResult.Skipped)
        assertTrue(m.flush(ctx) is ModuleResult.Skipped)
    }

    @Test
    fun deviceStateSamplerStyleEventsArePersistedThroughTheModule() {
        // The connectivity-change path feeds supplemental DeviceStateSampler.poll() output
        // (battery / network / power-save rows) into recordAsync → the module. Screen, keyguard,
        // startup, and shutdown remain owned by the original UsageStats timeline.
        // Those rows are ordinary lifecycle ExtractedUsageEvents and persist identically.
        val dao = FakeStorageQueue()
        val m = module(dao)
        val sampled = listOf(
            event("Battery Discharging", "battery:high:discharging"),
            event("Network Connected", "network:wifi"),
            event("Power Save Mode Off", "power-save:off"),
        )

        assertEquals(ModuleResult.Ok(3), m.persist(sampled))
        assertEquals(1, dao.getSize())
    }

    @Test
    fun lowMemoryEventPersistsThroughTheModule() {
        val dao = FakeStorageQueue()
        val m = module(dao)
        val event: ExtractedUsageEvent = LifecycleEventMapper.lowMemoryEvent(80, 1_700_000_000_000L)

        val result = m.persist(listOf(event))

        assertEquals(ModuleResult.Ok(1), result)
        assertEquals(1, dao.getSize())
        assertFalse(m.diagnostics().notTracked.contains("lastEvent"))
    }
}
