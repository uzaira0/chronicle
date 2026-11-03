package com.openlattice.chronicle

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.Parameterized

/** Uses only JVM parsing: no Android views, runtime, or Robolectric runner. */
@RunWith(Parameterized::class)
class EnrollmentLinkParserTest(private val case: Case) {
    data class Case(val label: String, val uri: String, val message: Int? = null) {
        override fun toString() = label
    }

    @Test fun validatesDestinationAndPrefill() {
        val code = "a".repeat(40)
        val parsed = parseEnrollmentLink(case.uri, code)
        if (case.message != null) {
            assertTrue(parsed is EnrollmentLink.Invalid)
            assertEquals(case.message, (parsed as EnrollmentLink.Invalid).message)
        } else {
            assertTrue(parsed is EnrollmentLink.Prefill)
            parsed as EnrollmentLink.Prefill
            assertEquals(if (case.label == "optional identifiers") null else STUDY_ID, parsed.studyId?.toString())
            assertEquals(if (case.label == "optional identifiers") null else "participant", parsed.participantId)
            assertEquals("https://localhost", parsed.serverUrl)
            assertTrue(parsed.accessCode == code)
        }
    }

    companion object {
        private const val STUDY_ID = "11111111-1111-1111-1111-111111111111"
        private const val DESTINATION = "chronicle://enroll"
        private const val IDENTIFIERS = "studyId=$STUDY_ID&participantId=participant"

        @JvmStatic @Parameterized.Parameters(name = "{0}")
        fun cases() = listOf(
            Case("malformed URI", "$DESTINATION?serverUrl=%", R.string.enrollment_invitation_unverified),
            Case("wrong scheme", "https://enroll?$IDENTIFIERS", R.string.enrollment_invitation_unverified),
            Case("wrong host", "chronicle://other?$IDENTIFIERS", R.string.enrollment_invitation_unverified),
            Case("wrong path", "$DESTINATION/extra?$IDENTIFIERS", R.string.enrollment_invitation_unverified),
            Case("slash path", "$DESTINATION/?$IDENTIFIERS", R.string.enrollment_invitation_unverified),
            Case("non-hierarchical", "chronicle:enroll?$IDENTIFIERS", R.string.enrollment_invitation_unverified),
            Case("bad UUID", "$DESTINATION?studyId=invalid&serverUrl=https%3A%2F%2Flocalhost", R.string.invalid_study_id_format),
            Case("UUID suffix", "$DESTINATION?studyId=${STUDY_ID}extra&serverUrl=https%3A%2F%2Flocalhost", R.string.invalid_study_id_format),
            Case("missing server", "$DESTINATION?$IDENTIFIERS", R.string.enrollment_missing_server_url),
            Case("blank server", "$DESTINATION?$IDENTIFIERS&serverUrl=", R.string.enrollment_missing_server_url),
            Case("untrusted server", "$DESTINATION?$IDENTIFIERS&serverUrl=http%3A%2F%2Fexample.com", R.string.enrollment_untrusted_server_url),
            Case("server credentials", "$DESTINATION?$IDENTIFIERS&serverUrl=https%3A%2F%2Fu%3Ap%40localhost",
                R.string.enrollment_untrusted_server_url),
            Case("participant the server refuses", "$DESTINATION?studyId=$STUDY_ID&participantId=a%2Fb&serverUrl=https%3A%2F%2Flocalhost",
                R.string.invalid_participant),
            Case("participant over the server's length", "$DESTINATION?studyId=$STUDY_ID&participantId=${"p".repeat(256)}" +
                "&serverUrl=https%3A%2F%2Flocalhost", R.string.invalid_participant),
            Case("accepted", "$DESTINATION?$IDENTIFIERS&serverUrl=https%3A%2F%2FLOCALHOST%3A443%2F"),
            Case("optional identifiers", "$DESTINATION?serverUrl=https%3A%2F%2Flocalhost"),
        )
    }
}
