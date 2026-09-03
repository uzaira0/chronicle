package com.openlattice.chronicle

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class EnrollmentLinkCredentialTest {
    @Test
    fun readsValidCodeFromFragmentOnly() {
        val code = "Abc_123-".repeat(8)
        assertEquals(code, EnrollmentLinkCredential.fromFragment("accessCode=$code"))
        assertEquals(code, EnrollmentLinkCredential.fromFragment("ignored=value&accessCode=$code"))
    }

    @Test
    fun rejectsMissingMalformedOrShortCodes() {
        assertNull(EnrollmentLinkCredential.fromFragment(null))
        assertNull(EnrollmentLinkCredential.fromFragment("accessCode=short"))
        assertNull(EnrollmentLinkCredential.fromFragment("accessCode=${"a".repeat(32)}%2F"))
    }
}
