package com.openlattice.chronicle.services.lifecycle

import com.openlattice.chronicle.collection.core.ModuleResult
import com.openlattice.chronicle.constants.ANDROID_SYSTEM_LABEL as CONTRACT_ANDROID_SYSTEM_LABEL
import com.openlattice.chronicle.constants.ANDROID_SYSTEM_PACKAGE as CONTRACT_ANDROID_SYSTEM_PACKAGE
import com.openlattice.chronicle.constants.INTERACTION_BATTERY_CHARGING as CONTRACT_INTERACTION_BATTERY_CHARGING
import com.openlattice.chronicle.constants.INTERACTION_BATTERY_DISCHARGING as CONTRACT_INTERACTION_BATTERY_DISCHARGING
import com.openlattice.chronicle.constants.INTERACTION_BATTERY_LOW as CONTRACT_INTERACTION_BATTERY_LOW
import com.openlattice.chronicle.constants.INTERACTION_BATTERY_OKAY as CONTRACT_INTERACTION_BATTERY_OKAY
import com.openlattice.chronicle.constants.INTERACTION_LOW_MEMORY as CONTRACT_INTERACTION_LOW_MEMORY

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.util.Log
import com.openlattice.chronicle.collection.CollectionModuleId
import com.openlattice.chronicle.collection.lifecycle.DeviceLifecycleModuleHolder
import com.openlattice.chronicle.collection.lifecycle.LifecycleEventMapper
import com.openlattice.chronicle.collection.state.CollectionPersistenceGuard
import com.openlattice.chronicle.collection.state.CollectionPersistenceResult
import com.openlattice.chronicle.collection.state.ResearchErasureFence
import com.openlattice.chronicle.collection.state.ResearchPersistenceGate
import com.openlattice.chronicle.models.ExtractedUsageEvent
import com.openlattice.chronicle.services.upload.LocalOperationalIssue
import com.openlattice.chronicle.services.upload.LocalUploadModuleFamily
import com.openlattice.chronicle.services.upload.recordForExpectedOwner
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit

// ANDROID_SYSTEM_* / INTERACTION_BATTERY_* / INTERACTION_LOW_MEMORY are owned by
// :collection-base CollectionConstants (referenced by :collection-lifecycle) and
// re-exported here so existing :app import sites stay unchanged.
const val ANDROID_SYSTEM_PACKAGE = CONTRACT_ANDROID_SYSTEM_PACKAGE
const val ANDROID_SYSTEM_LABEL = CONTRACT_ANDROID_SYSTEM_LABEL

const val INTERACTION_BATTERY_LOW = CONTRACT_INTERACTION_BATTERY_LOW
const val INTERACTION_BATTERY_OKAY = CONTRACT_INTERACTION_BATTERY_OKAY
const val INTERACTION_BATTERY_CHARGING = CONTRACT_INTERACTION_BATTERY_CHARGING
const val INTERACTION_BATTERY_DISCHARGING = CONTRACT_INTERACTION_BATTERY_DISCHARGING
const val INTERACTION_POWER_SAVE_MODE_ON = "Power Save Mode On"
const val INTERACTION_POWER_SAVE_MODE_OFF = "Power Save Mode Off"
const val INTERACTION_NETWORK_CONNECTED = "Network Connected"
const val INTERACTION_NETWORK_DISCONNECTED = "Network Disconnected"
const val INTERACTION_LOW_MEMORY = CONTRACT_INTERACTION_LOW_MEMORY
const val ACTION_CONNECTIVITY_CHANGE = "android.net.conn.CONNECTIVITY_CHANGE"

private val lifecycleExecutor = ThreadPoolExecutor(1, 1, 0L, TimeUnit.MILLISECONDS, LinkedBlockingQueue())

/** Broadcast adapters and asynchronous persistence through the lifecycle module. */
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
                Log.e(TAG, "Lifecycle observation could not be persisted: ${error.javaClass.simpleName}")
                recordForExpectedOwner(appContext, owner, LocalUploadModuleFamily.USAGE_LIFECYCLE,
                    LocalOperationalIssue.LOCAL_WRITE_FAILED, observedCount.coerceAtLeast(1))
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

    fun lowMemoryEvent(level: Int, timestampMillis: Long = System.currentTimeMillis()): ExtractedUsageEvent? {
        return LifecycleEventMapper.lowMemoryEvent(level, timestampMillis)
    }

    fun recordAsync(context: Context, event: ExtractedUsageEvent?, origin: CollectionPersistenceGuard = ResearchPersistenceGate.captureObservation(
        context, CollectionModuleId.DEVICE_LIFECYCLE)) {
        if (event == null) return
        recordAsync(context, listOf(event), origin)
    }

    fun recordAsync(context: Context, events: List<ExtractedUsageEvent>, origin: CollectionPersistenceGuard =
        ResearchPersistenceGate.captureObservation(context, CollectionModuleId.DEVICE_LIFECYCLE)) {
        if (events.isEmpty()) return
        val appContext = context.applicationContext
        val floor = ResearchPersistenceGate.observationScope(appContext, CollectionModuleId.DEVICE_LIFECYCLE)?.floorMillis ?: Long.MAX_VALUE
        val admittedEvents = events.filter { it.timestamp.toInstant().toEpochMilli() >= floor }
        val owner = ResearchPersistenceGate.captureOwner(appContext)
        lifecycleExecutor.execute {
            try {
                val storageUnavailable = DeviceLifecycleModuleHolder.get(appContext).persist(admittedEvents, origin) is
                    ModuleResult.Retry
                if (storageUnavailable) {
                    // Low storage refused these captured events and nothing retries them: count
                    // the loss for the enrollment that observed them (battery-sink parity).
                    recordForExpectedOwner(appContext, owner, LocalUploadModuleFamily.USAGE_LIFECYCLE,
                        LocalOperationalIssue.COLLECTION_GATE_DROPPED, admittedEvents.size)
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to persist lifecycle batch: ${e.javaClass.simpleName}")
                recordForExpectedOwner(appContext, owner, LocalUploadModuleFamily.USAGE_LIFECYCLE,
                    LocalOperationalIssue.LOCAL_WRITE_FAILED, admittedEvents.size)
                // Surface an unexpected background-executor failure in module diagnostics
                // too, so it is visible regardless of which path raised it.
                runCatching { DeviceLifecycleModuleHolder.get(appContext).recordAsyncFailure(e) }
            }
        }
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
