package com.openlattice.chronicle.services.upload

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class LocalUploadDiagnosticsImportTest {
    @Test fun importsLargeOldHistoryAndQuarantinesMalformedElements() {
        val valid = List(601) { index ->
            """{"day":"2001-01-01","moduleFamily":"FUTURE_FAMILY","issue":"FUTURE_CODE","count":${index + 1},"id":"id-$index","firstOccurredAt":"2001-01-01T00:00:00Z","lastOccurredAt":"2001-01-01T00:00:00Z"}"""
        }
        val parsed = parseLegacyDiagnosticJson("[${valid.joinToString(",")},{\"bad\":true}]", "digest", true)

        assertEquals(601, parsed.buckets.size)
        assertEquals(1, parsed.quarantined.size)
        assertEquals("FUTURE_CODE", parsed.buckets.first().issue)
        assertEquals(601, parsed.buckets.last().count)
        assertTrue(parsed.quarantined.single().second.contains("bad"))
    }

    @Test fun uncertainOwnershipQuarantinesEveryLegacyBucket() {
        val parsed = parseLegacyDiagnosticJson(
            """[{"day":"2001-01-01","moduleFamily":"SENSOR","issue":"APP_CRASH","count":1,"id":"old","firstOccurredAt":"2001-01-01T00:00:00Z","lastOccurredAt":"2001-01-01T00:00:00Z"}]""",
            "digest", false,
        )
        assertTrue(parsed.buckets.isEmpty())
        assertEquals(1, parsed.quarantined.size)
    }
}
