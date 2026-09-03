package com.openlattice.chronicle

import com.openlattice.chronicle.sources.AndroidDevice
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class EnrollmentReplayProtocolTest {

    @Test
    fun deathBeforeFirstRequestPersistsTimingAndReplaysTheExactRequest() {
        val pending = pendingRequest()

        val plan = planEnrollmentReplay(pending, NOW)

        assertTrue(plan is EnrollmentReplayPlan.Send)
        val replay = (plan as EnrollmentReplayPlan.Send).request
        assertEquals(NOW, replay.firstRequestAtEpochMillis)
        assertEquals(NOW + ENROLLMENT_REPLAY_WINDOW_MILLIS, replay.replayDeadlineEpochMillis)
        assertExactRequest(pending, replay)
        assertTrue(plan.persistFirstRequestTiming)
    }

    @Test
    fun lostResponseReplaysWithoutRotatingAttemptOrCredential() {
        val pending = pendingRequest(
            firstRequestAtEpochMillis = NOW - 1_000,
            replayDeadlineEpochMillis = NOW - 1_000 + ENROLLMENT_REPLAY_WINDOW_MILLIS,
        )

        val plan = planEnrollmentReplay(pending, NOW) as EnrollmentReplayPlan.Send

        assertExactRequest(pending, plan.request)
        assertFalse(plan.persistFirstRequestTiming)
    }

    @Test
    fun remoteSuccessFollowedByLocalPersistenceFailureRemainsReplayable() {
        val durableStateAfterFailure = pendingRequest(
            firstRequestAtEpochMillis = NOW - 5_000,
            replayDeadlineEpochMillis = NOW - 5_000 + ENROLLMENT_REPLAY_WINDOW_MILLIS,
        )

        val restartPlan = planEnrollmentReplay(durableStateAfterFailure, NOW)

        assertTrue(restartPlan is EnrollmentReplayPlan.Send)
        assertExactRequest(
            durableStateAfterFailure,
            (restartPlan as EnrollmentReplayPlan.Send).request,
        )
    }

    @Test
    fun startupReplayCarriesEveryServerBoundField() {
        val pending = pendingRequest(
            firstRequestAtEpochMillis = NOW - 500,
            replayDeadlineEpochMillis = NOW - 500 + ENROLLMENT_REPLAY_WINDOW_MILLIS,
        )

        val replay = (planEnrollmentReplay(pending, NOW) as EnrollmentReplayPlan.Send).request

        assertEquals(ATTEMPT_ID, replay.attemptId)
        assertEquals(ACCESS_CODE, replay.accessCode)
        assertEquals(PROPOSED_KEY, replay.proposedApiKey)
        assertEquals(STUDY_ID, replay.studyId)
        assertEquals(PARTICIPANT_ID, replay.participantId)
        assertEquals(SOURCE_DEVICE_ID, replay.sourceDeviceId)
        assertEquals(SOURCE_DEVICE_JSON, replay.sourceDeviceJson)
        assertEquals(MANIFEST_DIGEST, replay.manifestDigest)
    }

    @Test
    fun sourceDeviceSnapshotRoundTripsExactlyAndMustMatchTheBoundHeaderId() {
        val sourceDevice = AndroidDevice(
            device = SOURCE_DEVICE_ID,
            model = "model-at-first-attempt",
            codename = "codename-at-first-attempt",
            brand = "brand-at-first-attempt",
            osVersion = "14",
            sdkVersion = "34",
            product = "product-at-first-attempt",
            deviceId = SOURCE_DEVICE_ID,
            additionalInfo = emptyMap(),
            fcmRegistrationToken = "",
        )
        val snapshot = encodePendingEnrollmentSourceDevice(sourceDevice)
        val pending = pendingRequest(sourceDeviceJson = snapshot)

        assertEquals(sourceDevice, decodePendingEnrollmentSourceDevice(pending))
        assertEquals(
            null,
            decodePendingEnrollmentSourceDevice(
                pendingRequest(sourceDeviceJson = snapshot, sourceDeviceId = "different-device"),
            ),
        )
    }

    @Test
    fun matchingResponsePromotesOnlyTheOriginallyProposedCredential() {
        val pending = pendingRequest()

        assertEquals(
            EnrollmentResponseDisposition.Promote(PROPOSED_KEY),
            evaluateEnrollmentResponse(pending, PROPOSED_KEY),
        )
        assertEquals(
            EnrollmentResponseDisposition.Cleanup(EnrollmentCleanupReason.CREDENTIAL_MISMATCH),
            evaluateEnrollmentResponse(pending, "ck_ABCDEFGH_ABCDEFGHIJKLMNOPQRSTUVWXYZ012345"),
        )
        assertEquals(
            EnrollmentResponseDisposition.Cleanup(EnrollmentCleanupReason.CREDENTIAL_MISMATCH),
            evaluateEnrollmentResponse(pending, null),
        )
    }

    @Test
    fun invitationExpiryBeforeFirstRequestAndReplayDeadlineExpiryAreTerminal() {
        val beforeFirstRequest = pendingRequest(inviteExpiresAtEpochMillis = NOW)
        val afterPossibleRequest = pendingRequest(
            inviteExpiresAtEpochMillis = NOW - 60_000,
            firstRequestAtEpochMillis = NOW - ENROLLMENT_REPLAY_WINDOW_MILLIS,
            replayDeadlineEpochMillis = NOW,
        )

        assertEquals(
            EnrollmentReplayPlan.Cleanup(EnrollmentCleanupReason.EXPIRED),
            planEnrollmentReplay(beforeFirstRequest, NOW),
        )
        assertEquals(
            EnrollmentReplayPlan.Cleanup(EnrollmentCleanupReason.EXPIRED),
            planEnrollmentReplay(afterPossibleRequest, NOW),
        )
    }

    @Test
    fun aPossibleFirstRequestUsesReplayDeadlineInsteadOfOriginalInviteExpiry() {
        val pending = pendingRequest(
            inviteExpiresAtEpochMillis = NOW - 1,
            firstRequestAtEpochMillis = NOW - 1_000,
            replayDeadlineEpochMillis = NOW - 1_000 + ENROLLMENT_REPLAY_WINDOW_MILLIS,
        )

        assertTrue(planEnrollmentReplay(pending, NOW) is EnrollmentReplayPlan.Send)
    }

    @Test
    fun persistedReplayTimingCannotExtendOrShortenTheServerWindow() {
        val invalid = runCatching {
            pendingRequest(
                firstRequestAtEpochMillis = NOW - 1_000,
                replayDeadlineEpochMillis = NOW + ENROLLMENT_REPLAY_WINDOW_MILLIS,
            )
        }

        assertTrue(invalid.isFailure)
    }

    @Test
    fun explicitCancelRequestsSecretCleanup() {
        assertEquals(
            EnrollmentReplayPlan.Cleanup(EnrollmentCleanupReason.CANCELLED),
            cancelPendingEnrollmentReplay(),
        )
    }

    @Test
    fun onlyTheServersUniformAuthorizationRejectionIsTerminal() {
        assertTrue(enrollmentHttpFailureIsTerminal(401))
        assertFalse(enrollmentHttpFailureIsTerminal(400))
        assertFalse(enrollmentHttpFailureIsTerminal(429))
        assertFalse(enrollmentHttpFailureIsTerminal(500))
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

    private fun assertExactRequest(
        expected: PendingEnrollmentReplayRequest,
        actual: PendingEnrollmentReplayRequest,
    ) {
        assertEquals(expected.attemptId, actual.attemptId)
        assertEquals(expected.accessCode, actual.accessCode)
        assertEquals(expected.proposedApiKey, actual.proposedApiKey)
        assertEquals(expected.studyId, actual.studyId)
        assertEquals(expected.participantId, actual.participantId)
        assertEquals(expected.sourceDeviceId, actual.sourceDeviceId)
        assertEquals(expected.sourceDeviceJson, actual.sourceDeviceJson)
        assertEquals(expected.manifestDigest, actual.manifestDigest)
    }

    private fun pendingRequest(
        inviteExpiresAtEpochMillis: Long = NOW + 60_000,
        firstRequestAtEpochMillis: Long? = null,
        replayDeadlineEpochMillis: Long? = null,
        sourceDeviceJson: String = SOURCE_DEVICE_JSON,
        sourceDeviceId: String = SOURCE_DEVICE_ID,
    ): PendingEnrollmentReplayRequest = PendingEnrollmentReplayRequest(
        serverId = 7,
        ownerNonce = "reservation-owner",
        serverUrl = "https://study.example",
        mobileSigningSecretOverride = null,
        studyId = STUDY_ID,
        participantId = PARTICIPANT_ID,
        sourceDeviceId = sourceDeviceId,
        sourceDeviceJson = sourceDeviceJson,
        manifestDigest = MANIFEST_DIGEST,
        attemptId = ATTEMPT_ID,
        accessCode = ACCESS_CODE,
        proposedApiKey = PROPOSED_KEY,
        inviteExpiresAtEpochMillis = inviteExpiresAtEpochMillis,
        firstRequestAtEpochMillis = firstRequestAtEpochMillis,
        replayDeadlineEpochMillis = replayDeadlineEpochMillis,
    )

    private companion object {
        const val NOW = 2_000_000L
        const val STUDY_ID = "11111111-1111-1111-1111-111111111111"
        const val PARTICIPANT_ID = "participant-a"
        const val SOURCE_DEVICE_ID = "22222222-2222-2222-2222-222222222222"
        const val SOURCE_DEVICE_JSON =
            "{\"@class\":\"com.openlattice.chronicle.sources.AndroidDevice\",\"device\":\"$SOURCE_DEVICE_ID\"}"
        const val MANIFEST_DIGEST =
            "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"
        const val ATTEMPT_ID = "33333333-3333-3333-3333-333333333333"
        const val ACCESS_CODE = "enrollment-secret-code"
        const val PROPOSED_KEY = "ck_AAAAAAAA_AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA"
    }
}
