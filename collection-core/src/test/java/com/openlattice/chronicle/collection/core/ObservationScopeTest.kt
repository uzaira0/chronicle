package com.openlattice.chronicle.collection.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ObservationScopeTest {
    private val scope = ObservationScope("owner:1", 10, listOf(40L until 50L, 20L until 30L, 25L until 35L, 90L..Long.MAX_VALUE))

    @Test fun admitsOnlyAfterTheFloorAndOutsideSuspensions() {
        assertFalse(scope.admits(9))
        assertTrue(scope.admits(10))
        assertFalse(scope.admits(20))
        assertTrue(scope.admits(35))
        assertFalse(scope.admits(Long.MAX_VALUE))
    }

    @Test fun windowsSkipOverlappingAndOpenSuspensions() {
        assertEquals(listOf(10L to 20L, 35L to 40L, 50L to 90L), scope.admittedWindows(10, 100))
        assertEquals(listOf(36L to 38L), scope.admittedWindows(36, 38))
        assertEquals(emptyList<Pair<Long, Long>>(), scope.admittedWindows(41, 45))
        assertEquals(listOf(1L to 5L), ObservationScope("owner:1", 0).admittedWindows(1, 5))
    }
}
