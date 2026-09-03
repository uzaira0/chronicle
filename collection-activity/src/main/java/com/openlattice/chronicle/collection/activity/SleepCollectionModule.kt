package com.openlattice.chronicle.collection.activity

import android.content.Context
import com.openlattice.chronicle.collection.AndroidSleepEvent
import com.openlattice.chronicle.collection.CollectionModuleDiagnostics
import com.openlattice.chronicle.collection.CollectionModuleId
import com.openlattice.chronicle.collection.CollectionPrivacyClass
import com.openlattice.chronicle.collection.SleepEventType
import com.openlattice.chronicle.collection.SleepSegmentStatus
import com.openlattice.chronicle.collection.core.CollectionModuleStatus
import com.openlattice.chronicle.collection.core.CollectionWindow
import com.openlattice.chronicle.collection.core.DataCollectionModule
import com.openlattice.chronicle.collection.core.ModuleResult
import com.openlattice.chronicle.storage.SleepSampleEntry
import java.time.OffsetDateTime

private const val QUEUE_DEPTH_UNAVAILABLE = -1

/**
 * The `sleep` collection module (Play Services Sleep API). **Push-style**: the device delivers
 * sleep updates asynchronously to an app-side receiver that persists rows, so this module does no
 * work in [poll]; it exists as the registry citizen (status/diagnostics) and the home of the
 * row → wire-DTO mapping. Registration/unregistration of the Sleep API is driven by the app's
 * capture controller (it needs the receiver's PendingIntent). HEALTH_METRICS-class.
 *
 * @param queueDepth reads the pending-row count for diagnostics (the app wires this to the sink).
 */
public class SleepCollectionModule(
    private val queueDepth: () -> Int,
) : DataCollectionModule {

    override val id: CollectionModuleId = CollectionModuleId.SLEEP
    override val privacyClass: CollectionPrivacyClass = id.privacyClass

    override fun status(): CollectionModuleStatus = CollectionModuleStatus.IDLE

    override fun diagnostics(): CollectionModuleDiagnostics = CollectionModuleDiagnostics(
        moduleId = id,
        privacyClass = privacyClass,
        lastRunEpochMs = null,
        lastResult = ModuleResult.Skipped("push-captured").label,
        itemsCollected = 0,
        queueDepth = runCatching { queueDepth() }.getOrDefault(QUEUE_DEPTH_UNAVAILABLE),
        lastError = null,
        redactedParticipantRef = null,
    )

    /** Push-style: capture is delivered to the app receiver, not polled. */
    override fun poll(context: Context, window: CollectionWindow): ModuleResult =
        ModuleResult.Skipped("sleep is push-style (Play Services Sleep API)")

    override fun start(context: Context): ModuleResult =
        ModuleResult.Skipped("sleep registration is driven by the app capture controller")

    override fun stop(context: Context): ModuleResult =
        ModuleResult.Skipped("sleep registration is driven by the app capture controller")

    override fun flush(context: Context): ModuleResult = ModuleResult.Skipped("sleep buffers nothing")
}

/**
 * Converts a stored [SleepSampleEntry] row into the [AndroidSleepEvent] wire DTO. Throws on an
 * unparseable timestamp or unknown enum name; the upload path catches this per row so one corrupt
 * row never aborts a batch.
 */
public fun SleepSampleEntry.toAndroidSleepEvent(): AndroidSleepEvent = AndroidSleepEvent(
    id = id,
    timestamp = OffsetDateTime.parse(timestamp),
    timezone = timezone,
    eventType = SleepEventType.valueOf(eventType),
    segmentStartMillis = segmentStartMillis,
    segmentEndMillis = segmentEndMillis,
    segmentStatus = segmentStatus?.let { SleepSegmentStatus.valueOf(it) },
    confidence = confidence,
    light = light,
    motion = motion,
)
