package com.openlattice.chronicle.storage

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * `dataQueue.writeTimestamp` is the upload cursor. `StorageQueue.getEntriesAfter` skips anything
 * at or below `UploadServerEntity.lastUploadedTimestamp` and `deleteEntriesBeforeOrAt` deletes it,
 * so a row stamped with a backward-stepped wall clock is silently dropped.
 */
class QueueWriteCursorTest {

    @Test
    fun aBackwardClockStepStillWritesPastTheUploadCursor() {
        val uploadedCursor = 1_700_000_000_000L

        // The device clock jumps a day into the past after the batch at `uploadedCursor` shipped.
        val cursor = monotonicQueueWriteTimestamp(
            wallClockMillis = uploadedCursor - 86_400_000L,
            highWaterMark = uploadedCursor,
        )

        assertTrue("a new row must sort after the upload cursor", cursor > uploadedCursor)
        assertEquals(uploadedCursor + 1, cursor)
    }

    @Test
    fun aForwardClockKeepsWallTime() {
        val highWater = 1_700_000_000_000L
        val now = highWater + 5_000L

        assertEquals(now, monotonicQueueWriteTimestamp(now, highWater))
    }

    @Test
    fun anEmptyQueueWithNoUploadHistoryUsesWallTime() {
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
