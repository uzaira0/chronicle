package com.openlattice.chronicle.collection.interaction

/** Raw, signed accessibility-node bounds in the logical display's screen coordinate space. */
public data class InteractionNodeBounds(
    val left: Int,
    val top: Int,
    val right: Int,
    val bottom: Int,
) {
    init {
        require(left <= right) { "left must not exceed right" }
        require(top <= bottom) { "top must not exceed bottom" }
    }
}

/** Required legacy grid values derived deterministically from authoritative [bounds]. */
public data class LegacyInteractionGridPosition(
    val gridRow: Int,
    val gridCol: Int,
)

/**
 * Derives the required legacy grid representation from raw node bounds.
 *
 * Chronicle stores [bounds] as the observation. The center is used only transiently to choose a
 * grid cell; it is intentionally not returned or persisted as a misleading "raw" coordinate.
 */
public fun deriveLegacyInteractionGridPosition(
    bounds: InteractionNodeBounds,
    displayWidth: Int,
    displayHeight: Int,
    gridRows: Int,
    gridCols: Int,
): LegacyInteractionGridPosition {
    require(displayWidth >= 1) { "displayWidth must be positive" }
    require(displayHeight >= 1) { "displayHeight must be positive" }
    val centerX = ((bounds.left.toLong() + bounds.right.toLong()) / 2L)
        .coerceIn(Int.MIN_VALUE.toLong(), Int.MAX_VALUE.toLong())
        .toInt()
    val centerY = ((bounds.top.toLong() + bounds.bottom.toLong()) / 2L)
        .coerceIn(Int.MIN_VALUE.toLong(), Int.MAX_VALUE.toLong())
        .toInt()
    val (gridRow, gridCol) = InteractionGridReducer.cellFor(
        centerX = centerX,
        centerY = centerY,
        screenWidth = displayWidth,
        screenHeight = displayHeight,
        gridRows = gridRows,
        gridCols = gridCols,
    )
    return LegacyInteractionGridPosition(
        gridRow = gridRow,
        gridCol = gridCol,
    )
}
