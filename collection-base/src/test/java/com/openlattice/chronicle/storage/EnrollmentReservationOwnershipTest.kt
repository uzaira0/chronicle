package com.openlattice.chronicle.storage

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class EnrollmentReservationOwnershipTest {

    @Test
    fun onlyThePersistedNonceOwnsAnUnexpiredReservation() {
        val now = 1_000_000L
        val row = reservationRow(
            reservationNonce = "owner-a",
            reservationExpiresAtEpochMillis = now + 60_000,
        )

        assertTrue(row.reservationIsOwnedBy("owner-a", now))
        assertFalse(row.reservationIsOwnedBy("owner-b", now))
        assertFalse(row.reservationIsOwnedBy("owner-a", now + 60_001))
    }

    @Test
    fun anIssuedCredentialRemainsRecoverableUntilLocalEnrollmentCompletes() {
        val row = reservationRow(
            reservationNonce = "owner-a",
            reservationExpiresAtEpochMillis = 2_000_000L,
        ).copy(
            apiKey = "issued-device-key",
            authMode = AUTH_MODE_API_KEY,
            enrollmentIssuedAtEpochMillis = 1_500_000L,
            studyDisclosureJson = "{\"studyId\":\"study-a\"}",
            manifestDigest = "manifest-digest",
            enabled = false,
            enrollmentSetupComplete = false,
        )

        assertTrue(row.hasRecoverableIssuedEnrollment())
        assertFalse(row.copy(enrollmentSetupComplete = true).hasRecoverableIssuedEnrollment())
        assertFalse(row.copy(apiKey = null).hasRecoverableIssuedEnrollment())
    }

    private fun reservationRow(
        reservationNonce: String,
        reservationExpiresAtEpochMillis: Long,
    ) = UploadServerEntity(
        name = "Study",
        url = "https://research.example",
        studyId = "11111111-1111-1111-1111-111111111111",
        participantId = "participant-a",
        sourceDeviceId = "device-a",
        enabled = false,
        reservationNonce = reservationNonce,
        reservationExpiresAtEpochMillis = reservationExpiresAtEpochMillis,
    )
}
