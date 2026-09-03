package com.openlattice.chronicle.collection.interaction

/**
 * Pure screen-region grid reduction for interaction salience (`docs/SENSING-EXPANSION-DESIGN.md`
 * §6). Derives legacy normalized/grid values from the center of the interacted accessibility
 * element's raw screen bounds. The bounds are the primary observation; this reducer exists for
 * backward compatibility and grid-only study policies.
 *
 * No Android dependency, so it is JVM-unit-testable. All inputs are pixels.
 */
object InteractionGridReducer {

    /**
     * Maps the point ([centerX], [centerY]) onto a [gridRows] x [gridCols] grid, returning
     * `(row, col)`. Each index is clamped into the grid, and the grid dimensions and screen
     * size are floored at 1, so a degenerate input (zero-size screen, out-of-bounds point,
     * non-positive grid) can never yield an invalid cell.
     */
    fun cellFor(
        centerX: Int,
        centerY: Int,
        screenWidth: Int,
        screenHeight: Int,
        gridRows: Int,
        gridCols: Int,
    ): Pair<Int, Int> {
        val rows = gridRows.coerceAtLeast(1)
        val cols = gridCols.coerceAtLeast(1)
        val width = screenWidth.coerceAtLeast(1)
        val height = screenHeight.coerceAtLeast(1)
        val col = ((centerX.toLong().coerceAtLeast(0) * cols) / width).toInt().coerceIn(0, cols - 1)
        val row = ((centerY.toLong().coerceAtLeast(0) * rows) / height).toInt().coerceIn(0, rows - 1)
        return row to col
    }

    /**
     * Maps the derived point ([centerX], [centerY]) to normalized display coordinates `(x, y)` =
     * `(centerX / screenWidth, centerY / screenHeight)`, each clamped to `[0.0, 1.0]`. This is a
     * compatibility representation of the accessibility node center, not a pointer coordinate.
     * The screen size is floored at 1 so a degenerate input cannot divide by zero.
     */
    fun normalizedFor(
        centerX: Int,
        centerY: Int,
        screenWidth: Int,
        screenHeight: Int,
    ): Pair<Double, Double> {
        val width = screenWidth.coerceAtLeast(1)
        val height = screenHeight.coerceAtLeast(1)
        val x = (centerX.toDouble() / width).coerceIn(0.0, 1.0)
        val y = (centerY.toDouble() / height).coerceIn(0.0, 1.0)
        return x to y
    }
}
