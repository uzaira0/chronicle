package com.openlattice.chronicle.collection.activity

import com.openlattice.chronicle.collection.ActivityTransitionType
import com.openlattice.chronicle.collection.AndroidActivityRecognitionEvent
import com.openlattice.chronicle.collection.DetectedActivityType
import com.openlattice.chronicle.storage.ActivityRecognitionSampleEntry
import java.time.OffsetDateTime

/**
 * Converts a stored [ActivityRecognitionSampleEntry] row into the
 * [AndroidActivityRecognitionEvent] wire DTO. Throws on an unparseable timestamp or unknown enum
 * name; the upload path catches this per row so one corrupt row never aborts a batch.
 */
public fun ActivityRecognitionSampleEntry.toAndroidActivityRecognitionEvent(): AndroidActivityRecognitionEvent =
    AndroidActivityRecognitionEvent(
        id = id,
        timestamp = OffsetDateTime.parse(timestamp),
        timezone = timezone,
        activityType = DetectedActivityType.valueOf(activityType),
        confidence = confidence,
        transitionType = transitionType?.let { ActivityTransitionType.valueOf(it) },
    )
