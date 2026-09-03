package com.openlattice.chronicle.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class DashboardUploadTimeTest {
    @Test
    fun `latest upload includes sensor and battery success timestamps`() {
        assertEquals(
            "2026-08-18T14:34:25Z",
            latestUploadSuccessTime(
                listOf(
                    null,
                    "2026-08-18T14:34:24Z",
                    "2026-08-18T14:34:25Z",
                ),
            ),
        )
    }

    @Test
    fun `latest upload compares instants rather than ISO text`() {
        assertEquals(
            "2026-08-18T10:00:00-05:00",
            latestUploadSuccessTime(
                listOf(
                    "2026-08-18T14:59:59Z",
                    "2026-08-18T10:00:00-05:00",
                ),
            ),
        )
    }

    @Test
    fun `invalid or absent upload timestamps do not invent success`() {
        assertNull(latestUploadSuccessTime(listOf(null, "not-a-timestamp")))
    }
}
