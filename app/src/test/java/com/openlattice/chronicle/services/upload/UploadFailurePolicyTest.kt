package com.openlattice.chronicle.services.upload

import java.net.SocketTimeoutException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class UploadFailurePolicyTest {
    @Test
    fun consecutiveFailureEvidenceIncrementsWithoutDisablingTheDestination() {
        assertEquals(1, nextConsecutiveUploadFailureCount(0))
        assertEquals(50, nextConsecutiveUploadFailureCount(49))
        assertEquals(51, nextConsecutiveUploadFailureCount(50))
    }

    @Test
    fun consecutiveFailureEvidenceSaturatesInsteadOfOverflowing() {
        assertEquals(Int.MAX_VALUE, nextConsecutiveUploadFailureCount(Int.MAX_VALUE - 1))
        assertEquals(Int.MAX_VALUE, nextConsecutiveUploadFailureCount(Int.MAX_VALUE))
    }

    @Test
    fun persistedFailureStatusNeverCopiesExceptionText() {
        val sensitiveMessage = "https://study.example/api?token=secret"
        val persisted = persistedUploadFailureCode(SocketTimeoutException(sensitiveMessage))

        assertEquals("TIMEOUT", persisted)
        assertFalse(persisted.contains(sensitiveMessage))
    }
}
