package com.openlattice.chronicle

import java.util.UUID
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.Parameterized

@RunWith(Parameterized::class)
internal class EnrollmentInputTest(private val case: Case) {
    data class Case(
        val study: String = STUDY,
        val participant: String = "participant",
        val server: String = "https://LOCALHOST:443/",
        val code: String? = "invitation",
        val error: EnrollmentInputResult.Invalid? = null,
    )

    @Test fun validatesAndNormalizesManualInput() {
        val result = validateEnrollmentInput(case.study, case.participant, case.server, case.code)
        if (case.error != null) assertEquals(case.error, result)
        else {
            assertTrue(result is EnrollmentInputResult.Valid)
            val invitation = (result as EnrollmentInputResult.Valid).invitation
            assertEquals(UUID.fromString(STUDY), invitation.studyId)
            assertEquals("participant", invitation.participantId)
            assertEquals("https://localhost", invitation.serverUrl)
            assertEquals(case.code, invitation.accessCode)
        }
    }

    companion object {
        private const val STUDY = "11111111-1111-1111-1111-111111111111"

        @JvmStatic @Parameterized.Parameters(name = "{index}: {0}")
        fun cases() = listOf(
            Case(),
            Case(study = " $STUDY ", participant = " participant "),
            Case(study = " ", error = EnrollmentInputResult.Invalid(R.string.invalid_study_id_blank, EnrollmentField.STUDY)),
            Case(study = "invalid", error = EnrollmentInputResult.Invalid(R.string.invalid_study_id_format, EnrollmentField.STUDY)),
            Case(study = "${STUDY}suffix", error = EnrollmentInputResult.Invalid(R.string.invalid_study_id_format, EnrollmentField.STUDY)),
            Case(participant = " ", error = EnrollmentInputResult.Invalid(R.string.invalid_participant, EnrollmentField.PARTICIPANT)),
            Case(participant = "p".repeat(257), error = EnrollmentInputResult.Invalid(R.string.invalid_participant, EnrollmentField.PARTICIPANT)),
            Case(server = " ", error = EnrollmentInputResult.Invalid(R.string.enrollment_missing_server_url, EnrollmentField.SERVER)),
            Case(server = "http://example.com",
                error = EnrollmentInputResult.Invalid(R.string.enrollment_untrusted_server_url, EnrollmentField.SERVER)),
            Case(server = "https://u:p@localhost",
                error = EnrollmentInputResult.Invalid(R.string.enrollment_untrusted_server_url, EnrollmentField.SERVER)),
            Case(code = null, error = EnrollmentInputResult.Invalid(R.string.enrollment_link_required)),
            Case(code = " ", error = EnrollmentInputResult.Invalid(R.string.enrollment_link_required)),
        )
    }
}
