package com.openlattice.chronicle.models

import com.fasterxml.jackson.annotation.JsonCreator
import com.openlattice.chronicle.android.ChronicleSample
import java.time.OffsetDateTime
import java.util.*

data class ExtractedUsageEvent @JsonCreator constructor(
    val appPackageName: String,
    val interactionType: String,
    /** Raw Android event type, or the reserved supplemental lifecycle type. */
    val eventType: Int,
    val timestamp: OffsetDateTime,
    val timezone: String,
    val user: String,
    val applicationLabel: String,
    val activityClass: String? = null,
    /** Assigned once when the event is queued; the server stores each ID once, so retries cannot duplicate it. */
    val clientEventId: UUID? = null,
) : ChronicleSample
