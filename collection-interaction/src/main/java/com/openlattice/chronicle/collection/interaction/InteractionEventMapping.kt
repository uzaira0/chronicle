package com.openlattice.chronicle.collection.interaction

import com.openlattice.chronicle.collection.AndroidInteractionEvent
import com.openlattice.chronicle.collection.InteractionEventType
import com.openlattice.chronicle.collection.InteractionPositionSource
import com.openlattice.chronicle.storage.InteractionSampleEntry
import java.time.OffsetDateTime

/**
 * Converts a stored [InteractionSampleEntry] row into the [AndroidInteractionEvent] wire DTO
 * for upload (see `docs/SENSING-EXPANSION-DESIGN.md` §6).
 *
 * Throws if the row is corrupt — an unparseable [InteractionSampleEntry.timestamp] or an
 * [eventType] string that is not a known [InteractionEventType]. The upload path catches this
 * per row (via `mapNotNull`) so one corrupt row never aborts a batch, mirroring the battery and
 * sensor upload paths. [AndroidInteractionEvent]'s own `init` re-validates the grid bounds.
 */
public fun InteractionSampleEntry.toAndroidInteractionEvent(): AndroidInteractionEvent =
    AndroidInteractionEvent(
        id = id,
        timestamp = OffsetDateTime.parse(timestamp),
        timezone = timezone,
        eventType = InteractionEventType.valueOf(eventType),
        gridRows = gridRows,
        gridCols = gridCols,
        gridRow = gridRow,
        gridCol = gridCol,
        elementRole = elementRole,
        foregroundPackage = foregroundPackage,
        positionSource = positionSource?.let(InteractionPositionSource::valueOf),
        nodeBoundsLeft = nodeBoundsLeft,
        nodeBoundsTop = nodeBoundsTop,
        nodeBoundsRight = nodeBoundsRight,
        nodeBoundsBottom = nodeBoundsBottom,
        displayId = displayId,
        rawX = rawX,
        rawY = rawY,
        screenWidth = screenWidth,
        screenHeight = screenHeight,
        normalizedX = normalizedX,
        normalizedY = normalizedY,
        scrollDeltaX = scrollDeltaX,
        scrollDeltaY = scrollDeltaY,
        eventTimeMillis = eventTimeMillis,
        episodeId = episodeId,
        dwellMillisSincePrev = dwellMillisSincePrev,
        orientation = orientation,
        screenDensityDpi = screenDensityDpi,
        scrollVelocityX = scrollVelocityX,
        scrollVelocityY = scrollVelocityY,
        scrollReversed = scrollReversed,
    )
