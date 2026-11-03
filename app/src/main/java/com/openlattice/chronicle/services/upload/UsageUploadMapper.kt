package com.openlattice.chronicle.services.upload

import com.openlattice.chronicle.android.ChronicleSample
import com.openlattice.chronicle.android.ChronicleUsageEvent
import com.openlattice.chronicle.models.ExtractedUsageEvent
import java.util.UUID

/** Gives a queued usage event its client event ID; assigned once, before the row is first persisted or sent. */
internal fun withClientEventId(sample: ChronicleSample): ChronicleSample =
    if (sample is ExtractedUsageEvent && sample.clientEventId == null) sample.copy(clientEventId = UUID.randomUUID()) else sample

/**
 * Adds the study, participant and upload identity to each framework event. The phone's queue-write time is not
 * sent: the server records its own receipt time, and two clocks never meet in one row.
 */
internal fun mapUsageSamplesForUpload(
    data: List<ChronicleSample>,
    studyId: UUID,
    participantId: String,
): List<ChronicleSample> =
    data.mapNotNull { datum ->
        when (datum) {
            is ExtractedUsageEvent -> ChronicleUsageEvent(
                studyId = studyId,
                participantId = participantId,
                appPackageName = datum.appPackageName,
                interactionType = datum.interactionType,
                eventType = datum.eventType,
                timestamp = datum.timestamp,
                timezone = datum.timezone,
                user = datum.user,
                applicationLabel = datum.applicationLabel,
                activityClass = datum.activityClass,
                clientEventId = datum.clientEventId,
            )
            else -> null
        }
    }
