package com.openlattice.chronicle.storage

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SingleEnrollmentPolicyTest {
    private fun server(
        id: Long = 1,
        url: String = "https://research.example",
        studyId: String = "11111111-1111-1111-1111-111111111111",
        participantId: String = "participant-a",
    ) = UploadServerEntity(
        id = id,
        name = "Research study",
        url = url,
        studyId = studyId,
        participantId = participantId,
        sourceDeviceId = "device-a",
    )

    @Test
    fun emptyDatabaseAllowsFirstEnrollment() {
        assertEquals(
            SingleEnrollmentResolution.Insert,
            resolveSingleEnrollment(emptyList(), "https://research.example", "study-a", "participant-a"),
        )
    }

    @Test
    fun activeExactEnrollmentStillRequiresWithdrawal() {
        val existing = server()

        assertTrue(
            resolveSingleEnrollment(
                listOf(existing),
                existing.url,
                existing.studyId,
                existing.participantId,
            ) is SingleEnrollmentResolution.Reject,
        )
    }

    @Test
    fun exactUnissuedProvisionalEnrollmentCanResume() {
        val existing = server().copy(
            enabled = false,
            enrollmentSetupComplete = false,
            reservationNonce = "owner-a",
        )

        assertEquals(
            SingleEnrollmentResolution.Refresh(existing.id),
            resolveSingleEnrollment(
                listOf(existing),
                existing.url,
                existing.studyId,
                existing.participantId,
            ),
        )
    }

    @Test
    fun exactPendingAttemptCanBeSupersededOnlyAfterItsReplayDeadline() {
        val now = 2_000_000L
        val pending = server().copy(
            enabled = false,
            enrollmentSetupComplete = false,
            reservationNonce = "owner-a",
            pendingEnrollmentAttemptId = "33333333-3333-3333-3333-333333333333",
            pendingEnrollmentAccessCode = "one-time-code",
            pendingEnrollmentInviteExpiresAtEpochMillis = now - 60_000,
            pendingProposedApiKey = "ck_AAAAAAAA_AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA",
            pendingEnrollmentFirstRequestAtEpochMillis = now - 86_400_000,
            pendingEnrollmentReplayDeadlineEpochMillis = now,
        )

        assertEquals(
            SingleEnrollmentResolution.ReplaceProvisional(pending.id),
            resolveSingleEnrollment(
                listOf(pending),
                pending.url,
                pending.studyId,
                pending.participantId,
                now,
            ),
        )
        assertEquals(
            SingleEnrollmentResolution.Refresh(pending.id),
            resolveSingleEnrollment(
                listOf(pending),
                pending.url,
                pending.studyId,
                pending.participantId,
                now - 1,
            ),
        )
    }

    @Test
    fun secondServerIsRejectedEvenForTheSameStudy() {
        val existing = server()

        assertTrue(
            resolveSingleEnrollment(
                listOf(existing),
                "https://other-research.example",
                existing.studyId,
                existing.participantId,
            ) is SingleEnrollmentResolution.Reject,
        )
    }

    @Test
    fun secondStudyOnTheSameServerIsRejected() {
        val existing = server()

        assertTrue(
            resolveSingleEnrollment(
                listOf(existing),
                existing.url,
                "22222222-2222-2222-2222-222222222222",
                existing.participantId,
            ) is SingleEnrollmentResolution.Reject,
        )
    }

    @Test
    fun differentParticipantIdentityIsRejectedUntilWithdrawal() {
        val existing = server()

        assertTrue(
            resolveSingleEnrollment(
                listOf(existing),
                existing.url,
                existing.studyId,
                "participant-b",
            ) is SingleEnrollmentResolution.Reject,
        )
    }

    @Test
    fun abandonedProvisionalSlotDoesNotBlockANewEnrollment() {
        val abandoned = server().copy(
            enabled = false,
            apiKey = null,
            enrollmentSetupComplete = false,
            enrollmentIssuedAtEpochMillis = null,
        )

        assertEquals(
            SingleEnrollmentResolution.ReplaceProvisional(abandoned.id),
            resolveSingleEnrollment(
                listOf(abandoned),
                "https://other-research.example",
                "22222222-2222-2222-2222-222222222222",
                "participant-b",
            ),
        )
    }

    @Test
    fun disabledConfiguredLegacyEnrollmentStillRequiresWithdrawal() {
        val legacy = server().copy(
            authMode = AUTH_MODE_DEVICE_ID,
            apiKey = null,
            enabled = false,
            enrollmentSetupComplete = true,
            enrollmentIssuedAtEpochMillis = null,
        )

        assertTrue(
            resolveSingleEnrollment(
                listOf(legacy),
                "https://other-research.example",
                "22222222-2222-2222-2222-222222222222",
                "participant-b",
            ) is SingleEnrollmentResolution.Reject,
        )
    }

    @Test
    fun legacyMultipleRowsFailClosed() {
        assertTrue(
            resolveSingleEnrollment(
                listOf(server(id = 1), server(id = 2)),
                "https://research.example",
                "11111111-1111-1111-1111-111111111111",
                "participant-a",
            ) is SingleEnrollmentResolution.Reject,
        )
    }
}
