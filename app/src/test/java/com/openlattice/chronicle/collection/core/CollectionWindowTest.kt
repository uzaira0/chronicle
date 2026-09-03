package com.openlattice.chronicle.collection.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class CollectionWindowTest {

    @Test
    fun validWindowExposesDuration() {
        val window = CollectionWindow(startEpochMs = 1_000L, endEpochMs = 4_000L)
        assertEquals(3_000L, window.durationMs)
    }

    @Test
    fun invertedWindowIsRejected() {
        try {
            CollectionWindow(startEpochMs = 5_000L, endEpochMs = 1_000L)
            fail("an inverted window must be rejected")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message!!.contains("greater than"))
        }
    }

    @Test
    fun emptyWindowIsRejected() {
        try {
            CollectionWindow(startEpochMs = 2_000L, endEpochMs = 2_000L)
            fail("an empty window must be rejected")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message!!.contains("greater than"))
        }
    }
}
