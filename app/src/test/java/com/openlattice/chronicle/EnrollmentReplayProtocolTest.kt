package com.openlattice.chronicle

import com.openlattice.chronicle.sources.AndroidDevice
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class EnrollmentReplayProtocolTest {

    @Test
    fun sourceDeviceSnapshotRoundTripsExactlyAndMustMatchTheBoundHeaderId() {
        val sourceDevice = AndroidDevice(
            device = SOURCE_RANDOM_DEVICE_ID,
            model = "model-at-first-attempt",
            codename = "codename-at-first-attempt",
            brand = "brand-at-first-attempt",
            osVersion = "14",
            sdkVersion = "34",
            product = "product-at-first-attempt",
            randomDeviceId = SOURCE_RANDOM_DEVICE_ID,
            additionalInfo = emptyMap(),
            fcmRegistrationToken = "",
        )
        val snapshot = encodePendingEnrollmentSourceDevice(sourceDevice)
        val pending = pendingRequest(sourceDeviceJson = snapshot)

        assertEquals(sourceDevice, decodePendingEnrollmentSourceDevice(pending))
        assertEquals(
            null,
            decodePendingEnrollmentSourceDevice(
                pendingRequest(sourceDeviceJson = snapshot, sourceRandomDeviceId = "different-device"),
            ),
        )
    }

    @Test
    fun proposedApiKeysMatchTheServerFormatAndDoNotExposeSecretInToString() {
        repeat(64) {
            val generated = generateProposedEnrollmentApiKey()
            assertTrue(isCompatibleProposedEnrollmentApiKey(generated))
            val parts = generated.split('_')
            assertEquals(parts[1], parts[2].take(8))
        }
        assertFalse(pendingRequest().toString().contains(ACCESS_CODE))
        assertFalse(pendingRequest().toString().contains(PROPOSED_KEY))
        assertFalse(pendingRequest().toString().contains(SOURCE_DEVICE_JSON))
    }

    private fun pendingRequest(
        sourceDeviceJson: String = SOURCE_DEVICE_JSON,
        sourceRandomDeviceId: String = SOURCE_RANDOM_DEVICE_ID,
    ): PendingEnrollmentReplayRequest = PendingEnrollmentReplayRequest(
        serverId = 7,
        ownerNonce = "reservation-owner",
        serverUrl = "https://study.example",
        studyId = STUDY_ID,
        participantId = PARTICIPANT_ID,
        sourceRandomDeviceId = sourceRandomDeviceId,
        sourceDeviceJson = sourceDeviceJson,
        manifestDigest = MANIFEST_DIGEST,
        attemptId = ATTEMPT_ID,
        accessCode = ACCESS_CODE,
        proposedApiKey = PROPOSED_KEY,
        firstRequestAtEpochMillis = null,
    )

    private companion object {
        const val STUDY_ID = "11111111-1111-1111-1111-111111111111"
        const val PARTICIPANT_ID = "participant-a"
        const val SOURCE_RANDOM_DEVICE_ID = "22222222-2222-2222-2222-222222222222"
        const val SOURCE_DEVICE_JSON =
            "{\"@class\":\"com.openlattice.chronicle.sources.AndroidDevice\",\"device\":\"$SOURCE_RANDOM_DEVICE_ID\"}"
        const val MANIFEST_DIGEST =
            "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"
        const val ATTEMPT_ID = "33333333-3333-3333-3333-333333333333"
        const val ACCESS_CODE = "enrollment-secret-code"
        const val PROPOSED_KEY = "ck_AAAAAAAA_AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA"
    }
}
