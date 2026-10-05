package com.openlattice.chronicle.services.lifecycle

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.util.Log
import com.openlattice.chronicle.collection.lifecycle.DeviceLifecycleModuleHolder
import com.openlattice.chronicle.collection.CollectionModuleId
import com.openlattice.chronicle.collection.state.ResearchPersistenceGate
import com.openlattice.chronicle.collection.lifecycle.LifecycleEventMapper
import com.openlattice.chronicle.collection.lifecycle.LifecycleWorkerMigration
import com.openlattice.chronicle.data.ParticipationStatus
import com.openlattice.chronicle.models.ExtractedUsageEvent
import com.openlattice.chronicle.preferences.EnrollmentSettings
import com.openlattice.chronicle.serialization.JsonSerializer
import com.openlattice.chronicle.storage.ChronicleDb
import com.openlattice.chronicle.storage.nextQueueWriteTimestamp
import com.openlattice.chronicle.storage.QueueEntry
import com.openlattice.chronicle.utils.Utils
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import com.openlattice.chronicle.collection.state.CollectionPersistenceGuard
import com.openlattice.chronicle.collection.state.CollectionPersistenceResult
import com.openlattice.chronicle.services.upload.LocalOperationalIssue
import com.openlattice.chronicle.services.upload.LocalUploadModuleFamily
import com.openlattice.chronicle.services.upload.recordForExpectedOwner
import com.openlattice.chronicle.collection.state.ResearchErasureFence
import java.util.concurrent.ThreadLocalRandom

// ANDROID_SYSTEM_* / INTERACTION_BATTERY_* / INTERACTION_LOW_MEMORY are owned by
// :collection-base CollectionConstants (referenced by :collection-lifecycle) and
// re-exported here so existing :app import sites stay unchanged.
const val ANDROID_SYSTEM_PACKAGE = com.openlattice.chronicle.constants.ANDROID_SYSTEM_PACKAGE
const val ANDROID_SYSTEM_LABEL = com.openlattice.chronicle.constants.ANDROID_SYSTEM_LABEL

const val INTERACTION_BATTERY_LOW = com.openlattice.chronicle.constants.INTERACTION_BATTERY_LOW
const val INTERACTION_BATTERY_OKAY = com.openlattice.chronicle.constants.INTERACTION_BATTERY_OKAY
const val INTERACTION_BATTERY_CHARGING = com.openlattice.chronicle.constants.INTERACTION_BATTERY_CHARGING
const val INTERACTION_BATTERY_DISCHARGING = com.openlattice.chronicle.constants.INTERACTION_BATTERY_DISCHARGING
const val INTERACTION_POWER_SAVE_MODE_ON = "Power Save Mode On"
const val INTERACTION_POWER_SAVE_MODE_OFF = "Power Save Mode Off"
const val INTERACTION_NETWORK_CONNECTED = "Network Connected"
const val INTERACTION_NETWORK_DISCONNECTED = "Network Disconnected"
const val INTERACTION_LOW_MEMORY = com.openlattice.chronicle.constants.INTERACTION_LOW_MEMORY
const val ACTION_CONNECTIVITY_CHANGE = "android.net.conn.CONNECTIVITY_CHANGE"

// Dedupe prefs for the legacy inline recordNow path. Kept identical to
// LifecycleDedupeStore's LIFECYCLE_RECORDER_PREFS_NAME / LIFECYCLE_DEDUPE_WINDOW_MS so
// the legacy and module paths share one dedupe state and suppress identically.
private const val RECORDER_PREFS_NAME = "chronicle_lifecycle_recorder"
private const val DEDUPE_WINDOW_MS = 2_000L
private val lifecycleExecutor = ThreadPoolExecutor(1, 1, 0L, TimeUnit.MILLISECONDS, LinkedBlockingQueue())

/**
 * Compatibility shim over the Phase 5 [DeviceLifecycleModuleHolder] /
 * `DeviceLifecycleCollectionModule` (refactor plan §8, design §1C.4).
 *
 * Phase 5 moved device-lifecycle **event mapping** into
 * [com.openlattice.chronicle.collection.lifecycle.LifecycleEventMapper] (5A) and
 * **persistence** behind [com.openlattice.chronicle.collection.sink.LifecycleEventSink]
 * via `DeviceLifecycleCollectionModule` (5B). This object is **not deleted**: it stays
 * as the thin compatibility shim its existing callers — `DeviceLifecycleReceiver`,
 * `PowerSaveModeReceiver`, `HardwareSensorService.onTrimMemory`,
 * `DeviceUnlockMonitoringService.onTrimMemory` — keep calling, unchanged.
 *
 *  - The `eventForBroadcast*` / `lowMemoryEvent` / `buildEvent` builders now **delegate
 *    to [LifecycleEventMapper]** so there is exactly one mapping (5A, guardrail §8.1.2).
 *    Their signatures and returned values are unchanged.
 *  - [recordAsync] branches on [LifecycleWorkerMigration.USE_MODULE_MANAGER_LIFECYCLE_PATH]:
 *    when `false` (the default, the regression baseline) it runs the legacy inline
 *    [recordNow]; when `true` it routes through `DeviceLifecycleCollectionModule.persist`.
 *    Exactly one path runs per call — no double-write.
 *  - [recordNow], the **direct `dataQueue` writer**, is **retained** behind the shim and
 *    is **not removed**: it is the parity baseline and the `false`-branch implementation.
 *    Per refactor plan §8.2 decision #20 the direct writer is removed only after the
 *    Phase 5 parity tests *and* the migration flip prove the module path is byte-identical;
 *    that flip is a separate, separately-reviewed step. Until then `recordNow` stays.
 */
object DeviceLifecycleEventRecorder : ResearchErasureFence.Companion.Observer {
    init { ResearchErasureFence.registerObserver(this, setOf(CollectionModuleId.DEVICE_LIFECYCLE)) }
    override fun eraseResearchObservations() { lifecycleExecutor.queue.clear() }

    fun recordObserved(context: Context, observations: () -> List<ExtractedUsageEvent>) {
        val origin = ResearchPersistenceGate.captureObservation(context, CollectionModuleId.DEVICE_LIFECYCLE)
        if (!origin.isCurrent()) return
        val appContext = context.applicationContext
        val owner = ResearchPersistenceGate.captureOwner(appContext)
        lifecycleExecutor.execute {
            var observedCount = 0
            try {
                val result = origin.persistResult {
                    val events = observations()
                    observedCount = events.size
                    recordAsync(appContext, events, origin)
                }
                if (result == CollectionPersistenceResult.STORAGE_UNAVAILABLE) {
                    // Low storage refused before the observations were taken; take them now only to
                    // count the loss (broadcast mappings are pure; the state sampler self-gates).
                    recordForExpectedOwner(appContext, owner, LocalUploadModuleFamily.USAGE_LIFECYCLE,
                        LocalOperationalIssue.COLLECTION_GATE_DROPPED, observations().size)
                }
            } catch (error: Exception) {
                Log.w(TAG, "Lifecycle observation could not be persisted", error)
                recordForExpectedOwner(appContext, owner, LocalUploadModuleFamily.USAGE_LIFECYCLE,
                    LocalOperationalIssue.LOCAL_WRITE_FAILED, observedCount)
            }
        }
    }

    private val TAG = DeviceLifecycleEventRecorder::class.java.simpleName

    fun eventForBroadcast(intent: Intent): ExtractedUsageEvent? {
        return eventForBroadcastAction(intent.action, System.currentTimeMillis())
    }

    internal fun eventForBroadcastAction(action: String?, timestamp: Long): ExtractedUsageEvent? {
        return LifecycleEventMapper.eventForBroadcastAction(action, timestamp)
    }

    fun lowMemoryEvent(level: Int, timestampMillis: Long = System.currentTimeMillis()): ExtractedUsageEvent {
        return LifecycleEventMapper.lowMemoryEvent(level, timestampMillis)
    }

    fun recordAsync(context: Context, event: ExtractedUsageEvent?, origin: CollectionPersistenceGuard = ResearchPersistenceGate.captureObservation(context, CollectionModuleId.DEVICE_LIFECYCLE)) {
        if (event == null) return
        recordAsync(context, listOf(event), origin)
    }

    fun recordAsync(context: Context, events: List<ExtractedUsageEvent>, origin: CollectionPersistenceGuard = ResearchPersistenceGate.captureObservation(context, CollectionModuleId.DEVICE_LIFECYCLE)) {
        if (events.isEmpty()) return
        val appContext = context.applicationContext
        val floor = ResearchPersistenceGate.observationScope(appContext, CollectionModuleId.DEVICE_LIFECYCLE)?.second ?: Long.MAX_VALUE
        val admittedEvents = events.filter { it.timestamp.toInstant().toEpochMilli() >= floor }
        val owner = ResearchPersistenceGate.captureOwner(appContext)
        lifecycleExecutor.execute {
            try {
                val result = origin.persistResult {
                if (LifecycleWorkerMigration.USE_MODULE_MANAGER_LIFECYCLE_PATH) {
                    // Phase 5B module path: route through the sanctioned LifecycleEventSink.
                    // A ModuleResult.Failed is logged + recorded in module diagnostics by
                    // persist() itself — async failures are never silently swallowed.
                    DeviceLifecycleModuleHolder.get(appContext).persist(admittedEvents, origin)
                } else {
                    // Default path: the legacy inline direct writer — the regression baseline.
                    recordNow(appContext, admittedEvents)
                }
                }
                if (result == CollectionPersistenceResult.STORAGE_UNAVAILABLE) {
                    // Low storage refused these captured events and nothing retries them: count
                    // the loss for the enrollment that observed them (battery-sink parity).
                    recordForExpectedOwner(appContext, owner, LocalUploadModuleFamily.USAGE_LIFECYCLE,
                        LocalOperationalIssue.COLLECTION_GATE_DROPPED, admittedEvents.size)
                }
            } catch (e: Exception) {
                Log.w(TAG, "Failed to persist lifecycle batch", e)
                recordForExpectedOwner(appContext, owner, LocalUploadModuleFamily.USAGE_LIFECYCLE,
                    LocalOperationalIssue.LOCAL_WRITE_FAILED, admittedEvents.size)
                // Surface an unexpected background-executor failure in module diagnostics
                // too, so it is visible regardless of which path raised it.
                if (LifecycleWorkerMigration.USE_MODULE_MANAGER_LIFECYCLE_PATH) {
                    runCatching { DeviceLifecycleModuleHolder.get(appContext).recordAsyncFailure(e) }
                }
            }
        }
    }

    /**
     * Legacy inline direct writer to `dataQueue` — retained as the parity baseline and
     * the default ([LifecycleWorkerMigration] `false`-branch) implementation. Preserves
     * the non-enrolled skip, the 2-second dedupe window, the batch `QueueEntry` write,
     * and the post-write `Utils.updateUploadQueueSize` call.
     *
     * `DeviceLifecycleCollectionModule.persist` is the Phase 5 equivalent; this method
     * is removed only once the migration flip proves parity (refactor plan §8.2 #20).
     */
    fun recordNow(context: Context, events: List<ExtractedUsageEvent>): Boolean {
        val origin = ResearchPersistenceGate.captureObservation(context, CollectionModuleId.DEVICE_LIFECYCLE)
        var result = true
        origin.persist { result = recordNowAdmitted(context, events) }
        return result
    }

    private fun recordNowAdmitted(context: Context, events: List<ExtractedUsageEvent>): Boolean {
        if (events.isEmpty()) return true
        val settings = EnrollmentSettings(context)
        if (settings.getParticipationStatus() != ParticipationStatus.ENROLLED) {
            Log.d(TAG, "Skipping lifecycle event because participant is not enrolled")
            return true
        }
        val expectedOwner = ResearchPersistenceGate.captureOwner(context)

        val now = System.currentTimeMillis()
        val filteredEvents = events.filter { shouldPersist(context, it, now) }
        if (filteredEvents.isEmpty()) return true

        val db = ChronicleDb.getInstance(context)
        val entry = QueueEntry(
            writeTimestamp = db.nextQueueWriteTimestamp(now),
            id = ThreadLocalRandom.current().nextLong(),
            data = JsonSerializer.serializeQueueEntry(com.openlattice.chronicle.android.ChronicleData(filteredEvents))
        )
        val persisted = ResearchPersistenceGate.persistIfCollecting(
            context,
            CollectionModuleId.DEVICE_LIFECYCLE,
            records = filteredEvents.size,
            expectedOwner = expectedOwner,
            onRefused = { count ->
                com.openlattice.chronicle.services.upload.LocalUploadDiagnosticsStore.of(context).recordOperational(
                    com.openlattice.chronicle.services.upload.LocalUploadModuleFamily.USAGE_LIFECYCLE,
                    com.openlattice.chronicle.services.upload.LocalOperationalIssue.COLLECTION_GATE_DROPPED,
                    count,
                )
            },
        ) {
            db.queueEntryData().insertEntry(entry)
            Utils.updateUploadQueueSize(context, db.queueEntryData().getSize())
        }
        if (!persisted) return true
        Log.i(TAG, "Persisted ${filteredEvents.size} lifecycle event(s): ${filteredEvents.joinToString { it.interactionType }}")
        return true
    }

    private fun shouldPersist(context: Context, event: ExtractedUsageEvent, now: Long): Boolean {
        val prefs = context.getSharedPreferences(RECORDER_PREFS_NAME, Context.MODE_PRIVATE)
        val key = "last:${event.interactionType}:${event.activityClass ?: ""}"
        val last = prefs.getLong(key, Long.MIN_VALUE)
        if (last != Long.MIN_VALUE && now - last < DEDUPE_WINDOW_MS) {
            return false
        }
        prefs.edit().putLong(key, now).apply()
        return true
    }

    fun buildEvent(activityClass: String, interactionType: String, timestampMillis: Long): ExtractedUsageEvent {
        return LifecycleEventMapper.buildEvent(activityClass, interactionType, timestampMillis)
    }
}

fun deviceLifecycleIntentFilter(): IntentFilter {
    return IntentFilter().apply {
        addAction(Intent.ACTION_POWER_CONNECTED)
        addAction(Intent.ACTION_POWER_DISCONNECTED)
        addAction(Intent.ACTION_BATTERY_LOW)
        addAction(Intent.ACTION_BATTERY_OKAY)
        addAction(ACTION_CONNECTIVITY_CHANGE)
    }
}
