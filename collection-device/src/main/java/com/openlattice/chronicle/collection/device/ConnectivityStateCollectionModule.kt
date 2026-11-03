package com.openlattice.chronicle.collection.device

import com.openlattice.chronicle.collection.AndroidConnectivityStateEvent
import com.openlattice.chronicle.collection.ConnectivityEventType
import com.openlattice.chronicle.collection.NetworkTransport
import com.openlattice.chronicle.collection.core.ModuleResult
import com.openlattice.chronicle.collection.core.PullAccounting
import com.openlattice.chronicle.collection.core.SnapshotCollectionModule
import com.openlattice.chronicle.collection.sink.ConnectivityStateSampleSink
import com.openlattice.chronicle.collection.state.CollectionPersistenceGuard
import com.openlattice.chronicle.storage.ConnectivityStateSampleEntry
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.util.TimeZone
import java.util.UUID

/**
 * The `connectivity_state` collection module — a **pull-style** module mirroring
 * `BatteryTelemetryCollectionModule`. Each [sample] reads the current connectivity state
 * through the injected [ConnectivityStateSource] and persists one SNAPSHOT row through
 * [ConnectivityStateSampleSink]. DEVICE_STATE_METADATA-class, opt-in (default OFF).
 */
public class ConnectivityStateCollectionModule(
    private val sink: ConnectivityStateSampleSink,
    private val source: ConnectivityStateSource,
    enrolled: () -> Boolean,
    accounting: PullAccounting = PullAccounting(),
    now: () -> Long = System::currentTimeMillis,
) : SnapshotCollectionModule<ConnectivityStateReading>("connectivity", enrolled, accounting, now) {
    override fun captureAdmission(): CollectionPersistenceGuard = sink.captureAdmission()

    override fun read(): ConnectivityStateReading? = source.read()

    override fun persist(reading: ConnectivityStateReading, now: Long): ModuleResult = sink.write(listOf(
        ConnectivityStateSampleEntry(
            id = UUID.randomUUID().toString(),
            timestamp = OffsetDateTime.ofInstant(Instant.ofEpochMilli(now), ZoneOffset.UTC).toString(),
            timezone = TimeZone.getDefault().id,
            eventType = ConnectivityEventType.SNAPSHOT.name,
            transport = reading.transport.name,
            connected = reading.connected,
            metered = reading.metered,
            validated = reading.validated,
        ),
    ))
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
