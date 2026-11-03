package com.openlattice.chronicle.storage

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** `dataQueue.writeTimestamp` orders the queue, so a backward clock step must still write after every queued row. */
class QueueWriteCursorTest {

    @Test
    fun aBackwardClockStepStillWritesPastTheNewestQueuedRow() {
        val newestQueued = 1_700_000_000_000L

        // The device clock jumps a day into the past after the row at `newestQueued` was written.
        val cursor = monotonicQueueWriteTimestamp(
            wallClockMillis = newestQueued - 86_400_000L,
            highWaterMark = newestQueued,
        )

        assertTrue("a new row must sort after the newest queued row", cursor > newestQueued)
        assertEquals(newestQueued + 1, cursor)
    }

    @Test
    fun aForwardClockKeepsWallTime() {
        val highWater = 1_700_000_000_000L
        val now = highWater + 5_000L

        assertEquals(now, monotonicQueueWriteTimestamp(now, highWater))
    }

    @Test
    fun anEmptyQueueUsesWallTime() {
        assertEquals(42L, monotonicQueueWriteTimestamp(42L, null))
    }

    @Test
    fun repeatedBackwardStepsKeepAdvancing() {
        val start = 1_700_000_000_000L
        val stuckClock = start - 1_000L

        val first = monotonicQueueWriteTimestamp(stuckClock, start)
        val second = monotonicQueueWriteTimestamp(stuckClock, first)

        assertTrue(second > first)
    }
}
