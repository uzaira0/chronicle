package com.openlattice.chronicle.collection.core

/**
 * A half-open `[startEpochMs, endEpochMs)` time window for a pull-style poll
 * (design §1C.1 — `poll(ctx, window)`).
 *
 * Used by pull modules (usage events, device lifecycle) to bound a collection pass.
 * The window is validated at construction so an inverted or empty window cannot enter
 * the collection core silently.
 *
 */
public data class CollectionWindow(
    /** Inclusive start of the window, epoch millis. */
    val startEpochMs: Long,
    /** Exclusive end of the window, epoch millis. Must be greater than [startEpochMs]. */
    val endEpochMs: Long,
) {
    init {
        require(endEpochMs > startEpochMs) {
            "CollectionWindow.endEpochMs ($endEpochMs) must be greater than startEpochMs ($startEpochMs)"
        }
    }

    /** Window length in milliseconds. */
    public val durationMs: Long get() = endEpochMs - startEpochMs
}
