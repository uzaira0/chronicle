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
        // C2: a non-ASCII digit or letter is not part of the URL-safe code alphabet.
        assertNull(EnrollmentLinkCredential.fromFragment("accessCode=${"a".repeat(32)}%D9%A3"))
        assertNull(EnrollmentLinkCredential.fromFragment("accessCode=${"a".repeat(32)}%C3%A9"))
    }
}
