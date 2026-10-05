package com.openlattice.chronicle.collection.device

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit coverage for the pure per-module interval gate ([ExpansionPullSchedule.dueByElapsed]) — the
 * logic the periodic collection workers use to honor each module's study-configured interval.
 */
class ExpansionPullScheduleTest {

    private val tolerance = ExpansionPullSchedule.DUE_TOLERANCE_MS

    @Test fun neverRunIsAlwaysDue() {
        assertTrue(ExpansionPullSchedule.dueByElapsed(lastRunMs = null, intervalSeconds = 3600, nowMs = 1_000))
    }

    @Test fun elapsedBeyondIntervalIsDue() {
        val last = 1_000_000L
        val now = last + 3600_000L // exactly one hour later
        assertTrue(ExpansionPullSchedule.dueByElapsed(last, intervalSeconds = 3600, nowMs = now))
    }

    @Test fun elapsedWellBelowIntervalIsNotDue() {
        val last = 1_000_000L
        val now = last + 60_000L // one minute into a one-hour interval
        assertFalse(ExpansionPullSchedule.dueByElapsed(last, intervalSeconds = 3600, nowMs = now))
    }

    @Test fun driftWithinToleranceIsStillDue() {
        val last = 1_000_000L
        val intervalMs = 1800_000L // 30 min
        // Worker fires slightly early — within the tolerance window — and must still count as due,
        // otherwise a 30-min interval slips to the next 45-min tick.
        val now = last + intervalMs - (tolerance - 1)
        assertTrue(ExpansionPullSchedule.dueByElapsed(last, intervalSeconds = 1800, nowMs = now))
    }

    @Test fun earlierThanToleranceIsNotDue() {
        val last = 1_000_000L
        val intervalMs = 1800_000L
        val now = last + intervalMs - (tolerance + 60_000L) // a minute before the tolerance window
        assertFalse(ExpansionPullSchedule.dueByElapsed(last, intervalSeconds = 1800, nowMs = now))
    }

    @Test fun futureLastRunAfterClockCorrectionIsDue() {
        val now = 1_700_000_000_000L
        val futureLastRun = now + 7 * 24 * 60 * 60 * 1_000L

        assertTrue(ExpansionPullSchedule.dueByElapsed(futureLastRun, intervalSeconds = 3600, nowMs = now))
    }

    /** Another caller's claim a moment ahead (or a small NTP step back) is not a clock correction. */
    @Test fun lastRunSlightlyAheadIsNotDue() {
        val now = 1_700_000_000_000L
        assertFalse(ExpansionPullSchedule.dueByElapsed(now + 1L, intervalSeconds = 3600, nowMs = now))
        assertFalse(ExpansionPullSchedule.dueByElapsed(now + tolerance, intervalSeconds = 3600, nowMs = now))
    }
}
