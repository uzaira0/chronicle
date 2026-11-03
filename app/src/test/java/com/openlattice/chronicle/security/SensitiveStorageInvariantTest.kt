package com.openlattice.chronicle.security

import com.openlattice.chronicle.api.ChronicleStudyApi
import com.openlattice.chronicle.generated.ChronicleHeaders
import org.junit.Assert.assertTrue
import org.junit.Test
import retrofit2.http.Header

/** Public Retrofit enrollment contract; storage behavior is exercised by EnrollmentStorageBehaviorTest. */
class SensitiveStorageInvariantTest {
    @Test
    fun enrollmentApiCarriesThePreviewDigestAttemptAndProposedCredential() {
        val methods = ChronicleStudyApi::class.java.methods
        val headers = methods.single { it.name == "enroll" }.parameterAnnotations
            .flatMap { annotations -> annotations.filterIsInstance<Header>() }.map { it.value }
        assertTrue(methods.any { it.name == "getEnrollmentPreview" })
        assertTrue(headers.containsAll(listOf(ChronicleHeaders.MANIFEST_DIGEST_HEADER,
            ChronicleHeaders.ENROLLMENT_ATTEMPT_ID_HEADER, ChronicleHeaders.PROPOSED_API_KEY_HEADER)))
    }
}
