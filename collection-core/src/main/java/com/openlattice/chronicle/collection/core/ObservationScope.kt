package com.openlattice.chronicle.collection.core

/**
 * One module's consented capture scope: its enrollment generation [key], the earliest admitted
 * [floorMillis], and the [suspended] wall-clock intervals (pauses, required-consent halts) whose
 * observations are never persisted. Every historical source admits what it read through this rule.
 */
public data class ObservationScope(val key: String, val floorMillis: Long, val suspended: List<LongRange> = emptyList()) {
    public fun suspends(timeMillis: Long): Boolean = suspended.any { timeMillis in it }

    public fun admits(timeMillis: Long): Boolean = timeMillis >= floorMillis && !suspends(timeMillis)

    /** Half-open [startMillis, endMillis) minus every suspended interval, for sources that read aggregate windows. */
    public fun admittedWindows(startMillis: Long, endMillis: Long): List<Pair<Long, Long>> {
        val windows = mutableListOf<Pair<Long, Long>>()
        var from = startMillis
        for (gap in suspended.filterNot(LongRange::isEmpty).sortedBy(LongRange::first)) {
            if (from >= endMillis) break
            if (gap.first > from) windows += from to minOf(gap.first, endMillis)
            if (gap.last == Long.MAX_VALUE) return windows
            from = maxOf(from, gap.last + 1)
        }
        if (from < endMillis) windows += from to endMillis
        return windows
    }
}
