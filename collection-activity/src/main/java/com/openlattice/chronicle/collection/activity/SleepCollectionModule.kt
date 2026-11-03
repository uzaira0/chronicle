package com.openlattice.chronicle.collection.activity

import com.openlattice.chronicle.collection.AndroidSleepEvent
import com.openlattice.chronicle.collection.SleepEventType
import com.openlattice.chronicle.collection.SleepSegmentStatus
import com.openlattice.chronicle.storage.SleepSampleEntry
import java.time.OffsetDateTime

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
