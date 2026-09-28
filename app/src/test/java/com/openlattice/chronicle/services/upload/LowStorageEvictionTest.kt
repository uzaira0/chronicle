package com.openlattice.chronicle.services.upload

import org.junit.Assert.assertEquals
import org.junit.Test

class LowStorageEvictionTest {
    @Test
    fun queueIsUnboundedWhileTheDeviceHasRoom() {
        assertEquals(0, lowStorageEvictionCount(LOW_STORAGE_BYTES, 5_000_000))
    }

    @Test
    fun lowStorageDropsTheOldestTenthAndAtLeastOneRow() {
        assertEquals(500, lowStorageEvictionCount(LOW_STORAGE_BYTES - 1, 5_000))
        assertEquals(1, lowStorageEvictionCount(0, 3))
        assertEquals(0, lowStorageEvictionCount(0, 0))
    }
}
