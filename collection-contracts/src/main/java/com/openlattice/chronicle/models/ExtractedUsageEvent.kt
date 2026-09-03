package com.openlattice.chronicle.models

import com.fasterxml.jackson.annotation.JsonCreator
import com.openlattice.chronicle.android.ChronicleSample
import java.time.OffsetDateTime
import java.util.*

data class ExtractedUsageEvent @JsonCreator constructor(
    val appPackageName: String,
    val interactionType: String,
    /**
     * The raw Android `UsageEvents.Event` event type integer, carried verbatim so the backend
     * stores the authoritative numeric type (the `event_type` column) instead of reverse-parsing
     * it from [interactionType]. Defaults to -1 for backward-compatibility when deserializing
     * events queued by an older app build that did not carry it.
     */
    val eventType: Int = -1,
    val timestamp: OffsetDateTime,
    val timezone: String,
    val user: String,
    val applicationLabel: String,
    val activityClass: String? = null,
) : ChronicleSample
