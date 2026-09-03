package com.openlattice.chronicle.collection.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CollectionClockTest {

    @Test
    fun fixedClockReturnsSetValue() {
        val clock = FixedCollectionClock(1_700_000_000_000L)
        assertEquals(1_700_000_000_000L, clock.nowEpochMs())
    }

    @Test
    fun fixedClockSetAndAdvance() {
        val clock = FixedCollectionClock()
        assertEquals(0L, clock.nowEpochMs())
        clock.set(500L)
        assertEquals(500L, clock.nowEpochMs())
        clock.advance(250L)
        assertEquals(750L, clock.nowEpochMs())
    }

    @Test
    fun systemClockReturnsCurrentTime() {
        val before = System.currentTimeMillis()
        val now = SystemCollectionClock.nowEpochMs()
        val after = System.currentTimeMillis()
        assertTrue(now in before..after)
    }
}
