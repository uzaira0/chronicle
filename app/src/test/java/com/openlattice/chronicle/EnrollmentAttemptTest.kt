package com.openlattice.chronicle

import java.io.IOException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Test

class EnrollmentAttemptTest {
    @Test fun recoverableExceptionsBecomeResultsAndFatalErrorsEscape() {
        val recoverable = IOException("offline")
        assertSame(recoverable, enrollmentAttempt { throw recoverable }.exceptionOrNull())
        val fatal = LinkageError("native library unavailable")
        assertSame(fatal, assertThrows(LinkageError::class.java) { enrollmentAttempt { throw fatal } })
        assertEquals("completed", enrollmentAttempt { "completed" }.getOrThrow())
    }
}
