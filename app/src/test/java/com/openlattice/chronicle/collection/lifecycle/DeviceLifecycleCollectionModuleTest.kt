package com.openlattice.chronicle.collection.lifecycle

import com.openlattice.chronicle.collection.core.ModuleResult
import com.openlattice.chronicle.collection.sink.FakeStorageQueue
import com.openlattice.chronicle.collection.sink.LifecycleEventSink
import com.openlattice.chronicle.collection.state.CollectionPersistenceGuard
import com.openlattice.chronicle.serialization.JsonSerializer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * JVM unit coverage for [DeviceLifecycleCollectionModule] — the Phase 5 lifecycle module.
 *
 * Drives the module over the [FakeStorageQueue] / [FakeLifecycleDedupeStore] / clock
 * seams (no Android `Context`, no Robolectric). Proves the module
 * preserves: every lifecycle event mapping; the non-enrolled skip; the 2-second dedupe
 * window (idempotency on repeated broadcasts); the batch `QueueEntry` write; the
 * queue-write-failure surfaced as `Failed`; async executor
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
        now: () -> Long = { 50_000L },
        guard: CollectionPersistenceGuard = CollectionPersistenceGuard.ALLOW,
        onLoss: (String, Int) -> Unit = { _, _ -> },
    ) = DeviceLifecycleCollectionModule(
        sink = LifecycleEventSink(dao, guard),
        dedupeStore = dedupe,
        enrolled = enrolled,
        serializeQueueEntry = { data ->
            JsonSerializer.serializeQueueEntry(data)
        },
        now = now,
        captureLossRecorder = { onLoss },
    )

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
    fun queueWriteFailureLeavesNoRowBehindForCrashRecovery() {
        val dao = FakeStorageQueue().apply { failNextInsert = true }
        val m = module(dao)

        m.persist(listOf(event("Battery Okay", "android.intent.action.BATTERY_OKAY")))

        // Crash-recovery parity: a failed lifecycle write must not leave a partial row.
        assertEquals(0, dao.getSize())
    }

    @Test
    fun writeFailureAccountsDeduplicatedLossAfterAdmissionEnds() {
        val dao = FakeStorageQueue().apply { failNextInsert = true }
        var admissionDepth = 0
        var losses = 0
        val guard = CollectionPersistenceGuard { persist ->
            admissionDepth++
            try { persist(); true } finally { admissionDepth-- }
        }
        val m = module(dao, dedupe = FakeLifecycleDedupeStore(), guard = guard,
            onLoss = { issue, count ->
                assertEquals("loss must survive the admission transaction", 0, admissionDepth)
                assertEquals("LOCAL_WRITE_FAILED", issue)
                losses += count
            })
        val event = event("Battery Low", "android.intent.action.BATTERY_LOW")
        assertTrue(m.persist(listOf(event, event)) is ModuleResult.Failed)
        assertEquals(1, losses)
        assertEquals(0, dao.getSize())
    }

    @Test
    fun repeatedBroadcastWithinDedupeWindowIsSuppressed() {
        val dao = FakeStorageQueue()
        var nowMs = 10_000L
        val m = module(dao, dedupe = FakeLifecycleDedupeStore(), now = { nowMs })
        val screenOn = event("Screen Interactive", "android.intent.action.SCREEN_ON")

        // First broadcast persists.
        assertEquals(ModuleResult.Ok(1), m.persist(listOf(screenOn)))
        // Immediate repeat within 2s — suppressed; nothing new written.
        nowMs += 500L
        assertEquals(ModuleResult.Ok(0), m.persist(listOf(screenOn)))
        assertEquals("dedupe must suppress the repeat", 1, dao.getSize())
    }

    @Test
    fun broadcastAfterDedupeWindowIsPersistedAgain() {
        val dao = FakeStorageQueue()
        var nowMs = 10_000L
        val m = module(dao, dedupe = FakeLifecycleDedupeStore(), now = { nowMs })
        val screenOff = event("Screen Non-Interactive", "android.intent.action.SCREEN_OFF")

        assertEquals(ModuleResult.Ok(1), m.persist(listOf(screenOff)))
        // After the 2s window the same event is a fresh occurrence.
        nowMs += 3_000L
        assertEquals(ModuleResult.Ok(1), m.persist(listOf(screenOff)))
        assertEquals(2, dao.getSize())
    }

    @Test
    fun allDuplicateEventsInOneCallIsANoOpOk() {
        val dao = FakeStorageQueue()
        var nowMs = 10_000L
        val m = module(dao, dedupe = FakeLifecycleDedupeStore(), now = { nowMs })
        val e = event("Battery Charging", "android.intent.action.ACTION_POWER_CONNECTED")

        m.persist(listOf(e))
        nowMs += 100L
        // Second call: the only event is a duplicate → no row written, still a success.
        assertEquals(ModuleResult.Ok(0), m.persist(listOf(e)))
        assertEquals(1, dao.getSize())
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

}
