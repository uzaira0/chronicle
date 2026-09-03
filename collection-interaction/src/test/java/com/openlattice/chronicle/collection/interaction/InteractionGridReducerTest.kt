package com.openlattice.chronicle.collection.interaction

import com.openlattice.chronicle.collection.InteractionEventType
import com.openlattice.chronicle.collection.InteractionPositionSource
import com.openlattice.chronicle.storage.InteractionSampleEntry
import org.junit.Assert.assertEquals
import org.junit.Test

class InteractionGridReducerTest {

    @Test fun topLeftMapsToCellZeroZero() {
        assertEquals(0 to 0, InteractionGridReducer.cellFor(0, 0, 1080, 1920, 4, 3))
    }

    @Test fun bottomRightMapsToLastCell() {
        // The far corner clamps to the last row/col, never out of bounds.
        assertEquals(3 to 2, InteractionGridReducer.cellFor(1079, 1919, 1080, 1920, 4, 3))
    }

    @Test fun centerMapsToMiddleColumn() {
        // x in the middle third -> column 1 of 3; y in the second quarter -> row 1 of 4.
        assertEquals(1 to 1, InteractionGridReducer.cellFor(540, 700, 1080, 1920, 4, 3))
    }

    @Test fun outOfBoundsPointIsClamped() {
        assertEquals(0 to 0, InteractionGridReducer.cellFor(-50, -50, 1080, 1920, 4, 3))
        assertEquals(3 to 2, InteractionGridReducer.cellFor(9999, 9999, 1080, 1920, 4, 3))
    }

    @Test fun degenerateScreenOrGridNeverThrows() {
        assertEquals(0 to 0, InteractionGridReducer.cellFor(10, 10, 0, 0, 0, 0))
    }

    @Test fun normalizedPositionIsExactFraction() {
        val (x, y) = InteractionGridReducer.normalizedFor(540, 480, 1080, 1920)
        assertEquals(0.5, x, 1e-9)
        assertEquals(0.25, y, 1e-9)
    }

    @Test fun normalizedPositionIsClampedToUnitRange() {
        assertEquals(0.0 to 0.0, InteractionGridReducer.normalizedFor(-50, -50, 1080, 1920))
        assertEquals(1.0 to 1.0, InteractionGridReducer.normalizedFor(9999, 9999, 1080, 1920))
    }

    @Test fun normalizedPositionDegenerateScreenNeverThrows() {
        val (x, y) = InteractionGridReducer.normalizedFor(10, 10, 0, 0)
        assertEquals(1.0, x, 1e-9)
        assertEquals(1.0, y, 1e-9)
    }

    @Test fun rawNodeBoundsAreAuthoritativeAndOnlyLegacyGridIsDerived() {
        val derived = deriveLegacyInteractionGridPosition(
            bounds = InteractionNodeBounds(left = 500, top = 900, right = 542, bottom = 1168),
            displayWidth = 1080,
            displayHeight = 1920,
            gridRows = 4,
            gridCols = 3,
        )

        assertEquals(2, derived.gridRow)
        assertEquals(1, derived.gridCol)
    }

    @Test fun entryMapsToWireEventPreservingFields() {
        val entry = InteractionSampleEntry(
            id = "evt-1",
            timestamp = "2026-06-18T12:00:00Z",
            timezone = "UTC",
            eventType = "SCROLL",
            gridRows = 4,
            gridCols = 3,
            gridRow = 2,
            gridCol = 1,
            elementRole = "android.widget.ScrollView",
            foregroundPackage = "com.example.app",
            positionSource = InteractionPositionSource.ACCESSIBILITY_NODE_BOUNDS.name,
            nodeBoundsLeft = 500,
            nodeBoundsTop = 900,
            nodeBoundsRight = 542,
            nodeBoundsBottom = 1168,
            displayId = 0,
            rawX = 521,
            rawY = 1034,
            screenWidth = 1080,
            screenHeight = 1920,
            normalizedX = 0.4827,
            normalizedY = 0.5391,
            scrollDeltaX = -10,
            scrollDeltaY = 120,
            eventTimeMillis = 555_000L,
            episodeId = "ep-1",
            dwellMillisSincePrev = 240L,
            orientation = 0,
            screenDensityDpi = 420,
            scrollVelocityX = -41.6,
            scrollVelocityY = 500.0,
            scrollReversed = false,
        )
        val event = entry.toAndroidInteractionEvent()
        assertEquals("evt-1", event.id)
        assertEquals(InteractionEventType.SCROLL, event.eventType)
        assertEquals(2, event.gridRow)
        assertEquals(1, event.gridCol)
        assertEquals("android.widget.ScrollView", event.elementRole)
        assertEquals(InteractionPositionSource.ACCESSIBILITY_NODE_BOUNDS, event.positionSource)
        assertEquals(500, event.nodeBoundsLeft)
        assertEquals(900, event.nodeBoundsTop)
        assertEquals(542, event.nodeBoundsRight)
        assertEquals(1168, event.nodeBoundsBottom)
        assertEquals(0, event.displayId)
        assertEquals(521, event.rawX)
        assertEquals(1034, event.rawY)
        assertEquals(1080, event.screenWidth)
        assertEquals(1920, event.screenHeight)
        assertEquals(0.4827, event.normalizedX!!, 1e-9)
        assertEquals(0.5391, event.normalizedY!!, 1e-9)
        assertEquals(120, event.scrollDeltaY)
        assertEquals(555_000L, event.eventTimeMillis)
        assertEquals("ep-1", event.episodeId)
        assertEquals(240L, event.dwellMillisSincePrev)
        assertEquals(0, event.orientation)
        assertEquals(420, event.screenDensityDpi)
        assertEquals(500.0, event.scrollVelocityY!!, 1e-9)
        assertEquals(false, event.scrollReversed)
    }
}
