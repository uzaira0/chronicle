package com.openlattice.chronicle.collection.device

import android.content.Context
import com.openlattice.chronicle.collection.AndroidConnectivityStateEvent
import com.openlattice.chronicle.collection.CollectionModuleDiagnostics
import com.openlattice.chronicle.collection.CollectionModuleId
import com.openlattice.chronicle.collection.ConnectivityEventType
import com.openlattice.chronicle.collection.CollectionPrivacyClass
import com.openlattice.chronicle.collection.NetworkTransport
import com.openlattice.chronicle.collection.core.CollectionClock
import com.openlattice.chronicle.collection.core.CollectionLog
import com.openlattice.chronicle.collection.core.CollectionModuleStatus
import com.openlattice.chronicle.collection.core.CollectionWindow
import com.openlattice.chronicle.collection.core.DataCollectionModule
import com.openlattice.chronicle.collection.core.ModuleResult
import com.openlattice.chronicle.collection.sink.ConnectivityStateSampleSink
import com.openlattice.chronicle.storage.ConnectivityStateSampleEntry
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.util.TimeZone
import java.util.UUID

private const val TAG = "ConnectivityStateCollectionModule"
private const val QUEUE_DEPTH_UNAVAILABLE = -1

/**
 * The `connectivity_state` collection module — a **pull-style** module mirroring
 * `BatteryTelemetryCollectionModule`. Each [sample] reads the current connectivity state
 * through the injected [ConnectivityStateSource] and persists one SNAPSHOT row through
 * [ConnectivityStateSampleSink]. DEVICE_STATE_METADATA-class, opt-in (default OFF).
 */
public class ConnectivityStateCollectionModule(
    private val sink: ConnectivityStateSampleSink,
    private val source: ConnectivityStateSource,
    private val enrolled: () -> Boolean,
    private val clock: CollectionClock = CollectionClock.SYSTEM,
    private val log: CollectionLog = CollectionLog.LOGCAT,
) : DataCollectionModule {

    override val id: CollectionModuleId = CollectionModuleId.CONNECTIVITY_STATE
    override val privacyClass: CollectionPrivacyClass = id.privacyClass

    private data class SampleState(
        val lastRunEpochMs: Long?,
        val lastResult: ModuleResult,
        val itemsCollected: Int,
        val lastError: String?,
    )

    @Volatile
    private var state: SampleState = SampleState(null, ModuleResult.Skipped("not yet run"), 0, null)

    public fun sample(): ModuleResult {
        val now = clock.nowEpochMs()
        val result = runSample(now)
        state = SampleState(
            lastRunEpochMs = now,
            lastResult = result,
            itemsCollected = if (result is ModuleResult.Ok) result.items else 0,
            lastError = if (result is ModuleResult.Failed) result.redactedMessage else null,
        )
        return result
    }

    private fun runSample(now: Long): ModuleResult {
        if (!enrolled()) return ModuleResult.Skipped("participant not enrolled")

        val reading: ConnectivityStateReading? = try {
            source.read()
        } catch (e: Exception) {
            log.error(TAG, "Connectivity source threw while reading state", e)
            return ModuleResult.Failed(e, redactedMessage = "connectivity source read failed: ${e.javaClass.simpleName}")
        }
        if (reading == null) {
            log.warn(TAG, "Connectivity state unavailable; will retry on the next poll")
            return ModuleResult.Retry("connectivity state unavailable")
        }

        val entry = ConnectivityStateSampleEntry(
            id = UUID.randomUUID().toString(),
            timestamp = OffsetDateTime.ofInstant(Instant.ofEpochMilli(now), ZoneOffset.UTC).toString(),
            timezone = TimeZone.getDefault().id,
            eventType = ConnectivityEventType.SNAPSHOT.name,
            transport = reading.transport.name,
            connected = reading.connected,
            metered = reading.metered,
            validated = reading.validated,
        )

        return when (val writeResult = sink.write(listOf(entry))) {
            is ModuleResult.Ok -> {
                log.info(TAG, "Persisted 1 connectivity sample (transport=${reading.transport})")
                ModuleResult.Ok(1)
            }
            is ModuleResult.Failed -> {
                log.error(TAG, "Failed to persist connectivity sample", writeResult.error)
                writeResult
            }
            else -> writeResult
        }
    }

    override fun status(): CollectionModuleStatus = when (state.lastResult) {
        is ModuleResult.Failed -> CollectionModuleStatus.FAILED
        is ModuleResult.Ok -> CollectionModuleStatus.IDLE
        is ModuleResult.Retry -> CollectionModuleStatus.DEGRADED
        is ModuleResult.Skipped -> CollectionModuleStatus.IDLE
    }

    override fun diagnostics(): CollectionModuleDiagnostics {
        val snapshot = state
        return CollectionModuleDiagnostics(
            moduleId = id,
            privacyClass = privacyClass,
            lastRunEpochMs = snapshot.lastRunEpochMs,
            lastResult = snapshot.lastResult.label,
            itemsCollected = snapshot.itemsCollected,
            queueDepth = runCatching { sink.queueDepth() }.getOrDefault(QUEUE_DEPTH_UNAVAILABLE),
            lastError = snapshot.lastError,
            redactedParticipantRef = null,
        )
    }

    override fun poll(context: Context, window: CollectionWindow): ModuleResult = sample()
    override fun start(context: Context): ModuleResult = ModuleResult.Skipped("connectivity_state is pull-style")
    override fun stop(context: Context): ModuleResult = ModuleResult.Skipped("connectivity_state is pull-style")
    override fun flush(context: Context): ModuleResult = ModuleResult.Skipped("connectivity_state buffers nothing")
}

/**
 * Converts a stored [ConnectivityStateSampleEntry] row into the [AndroidConnectivityStateEvent]
 * wire DTO. Throws if the row carries an unparseable timestamp or unknown enum name; the upload
 * path catches this per row so one corrupt row never aborts a batch.
 */
public fun ConnectivityStateSampleEntry.toAndroidConnectivityStateEvent(): AndroidConnectivityStateEvent =
    AndroidConnectivityStateEvent(
        id = id,
        timestamp = OffsetDateTime.parse(timestamp),
        timezone = timezone,
        eventType = ConnectivityEventType.valueOf(eventType),
        transport = NetworkTransport.valueOf(transport),
        connected = connected,
        metered = metered,
        validated = validated,
    )
