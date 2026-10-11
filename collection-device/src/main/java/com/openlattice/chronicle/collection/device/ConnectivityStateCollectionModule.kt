package com.openlattice.chronicle.collection.device

import com.openlattice.chronicle.collection.AndroidConnectivityStateEvent
import com.openlattice.chronicle.collection.CollectionModuleId
import com.openlattice.chronicle.collection.ConnectivityEventType
import com.openlattice.chronicle.collection.NetworkTransport
import com.openlattice.chronicle.collection.core.CollectionClock
import com.openlattice.chronicle.collection.core.CollectionLog
import com.openlattice.chronicle.collection.core.ModuleResult
import com.openlattice.chronicle.collection.core.PullCollectionModule
import com.openlattice.chronicle.collection.state.writeObservation
import com.openlattice.chronicle.collection.sink.ConnectivityStateSampleSink
import com.openlattice.chronicle.storage.ConnectivityStateSampleEntry
import com.openlattice.chronicle.storage.UploadServerEntity
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.util.TimeZone
import java.util.UUID

private const val TAG = "ConnectivityStateCollectionModule"

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
    private val ownerForSample: () -> UploadServerEntity? = { null },
    private val accountWriteResult: (UploadServerEntity?, ModuleResult, Int) -> Unit = { _, _, _ -> },
    clock: CollectionClock = CollectionClock.SYSTEM,
    private val log: CollectionLog = CollectionLog.LOGCAT,
) : PullCollectionModule(CollectionModuleId.CONNECTIVITY_STATE, clock, sink::queueDepth) {

    override fun runSample(now: Long): ModuleResult {
        if (!enrolled()) return ModuleResult.Skipped("participant not enrolled")
        val observation = sink.captureAdmission()
        val owner = ownerForSample()

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

        return when (val writeResult = observation.writeObservation { sink.write(listOf(entry)) }.also {
            accountWriteResult(owner, it, 1)
        }) {
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
