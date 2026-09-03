package com.openlattice.chronicle.storage

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class EnrollmentReplayDaoTest {
    private lateinit var context: Context
    private lateinit var db: ChronicleDb

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        context.deleteDatabase(TEST_DB)
        db = openDatabase()
    }

    @After
    fun tearDown() {
        db.close()
        context.deleteDatabase(TEST_DB)
    }

    @Test
    fun processDeathKeepsTheExactBoundRequest() {
        val reservation = db.uploadServerDao().reserveSingleEnrollment(
            pendingServer(),
            ownerNonce = OWNER,
            nowEpochMillis = NOW,
        )

        db.close()
        db = openDatabase()

        val restored = db.uploadServerDao().getById(reservation.serverId)
        assertNotNull(restored)
        assertEquals(OWNER, restored?.reservationNonce)
        assertEquals(ATTEMPT, restored?.pendingEnrollmentAttemptId)
        assertEquals(ACCESS_CODE, restored?.pendingEnrollmentAccessCode)
        assertEquals(PROPOSED_KEY, restored?.pendingProposedApiKey)
        assertEquals(SOURCE_DEVICE_JSON, restored?.pendingEnrollmentSourceDeviceJson)
        assertEquals(MANIFEST_DIGEST, restored?.manifestDigest)
        assertEquals(UNAVAILABLE_MODULES, restored?.pendingUnavailableModuleIds)
    }

    @Test
    fun matchingPromotionAtomicallyRemovesReplaySecrets() {
        val dao = db.uploadServerDao()
        val reservation = dao.reserveSingleEnrollment(
            pendingServer(),
            ownerNonce = OWNER,
            nowEpochMillis = NOW,
        )
        assertEquals(
            1,
            dao.markPendingEnrollmentRequestStarted(
                reservation.serverId,
                OWNER,
                ATTEMPT,
                NOW,
                NOW + REPLAY_WINDOW,
            ),
        )

        assertEquals(
            1,
            dao.persistIssuedEnrollment(
                id = reservation.serverId,
                ownerNonce = OWNER,
                name = "Study",
                sourceDeviceId = SOURCE_DEVICE,
                authMode = AUTH_MODE_API_KEY,
                apiKey = PROPOSED_KEY,
                mobileSigningSecretOverride = null,
                studyDisclosureJson = DISCLOSURE,
                disclosureVersion = DISCLOSURE_VERSION,
                manifestDigest = MANIFEST_DIGEST,
                pendingAcceptedModuleIds = "",
                pendingDeclinedModuleIds = "",
                pendingUnavailableModuleIds = UNAVAILABLE_MODULES,
                enrollmentAttemptId = ATTEMPT,
                issuedAtEpochMillis = NOW + 1,
            ),
        )

        val issued = dao.getById(reservation.serverId)
        assertEquals(PROPOSED_KEY, issued?.apiKey)
        assertNotNull(issued?.enrollmentIssuedAtEpochMillis)
        assertNull(issued?.pendingEnrollmentAttemptId)
        assertNull(issued?.pendingEnrollmentAccessCode)
        assertNull(issued?.pendingProposedApiKey)
        assertNull(issued?.pendingEnrollmentSourceDeviceJson)
        assertNull(issued?.pendingEnrollmentFirstRequestAtEpochMillis)
        assertNull(issued?.pendingEnrollmentReplayDeadlineEpochMillis)
        assertEquals(UNAVAILABLE_MODULES, issued?.pendingUnavailableModuleIds)
    }

    @Test
    fun mismatchedPromotionRetainsExactReplayAndOwnerCancelDeletesIt() {
        val dao = db.uploadServerDao()
        val reservation = dao.reserveSingleEnrollment(
            pendingServer(),
            ownerNonce = OWNER,
            nowEpochMillis = NOW,
        )

        assertEquals(
            0,
            dao.persistIssuedEnrollment(
                id = reservation.serverId,
                ownerNonce = OWNER,
                name = "Study",
                sourceDeviceId = SOURCE_DEVICE,
                authMode = AUTH_MODE_API_KEY,
                apiKey = "ck_ABCDEFGH_ABCDEFGHIJKLMNOPQRSTUVWXYZ012345",
                mobileSigningSecretOverride = null,
                studyDisclosureJson = DISCLOSURE,
                disclosureVersion = DISCLOSURE_VERSION,
                manifestDigest = MANIFEST_DIGEST,
                pendingAcceptedModuleIds = "",
                pendingDeclinedModuleIds = "",
                pendingUnavailableModuleIds = UNAVAILABLE_MODULES,
                enrollmentAttemptId = ATTEMPT,
                issuedAtEpochMillis = NOW + 1,
            ),
        )
        assertEquals(ACCESS_CODE, dao.getById(reservation.serverId)?.pendingEnrollmentAccessCode)
        assertEquals(
            SOURCE_DEVICE_JSON,
            dao.getById(reservation.serverId)?.pendingEnrollmentSourceDeviceJson,
        )
        assertEquals(
            1,
            dao.deletePendingEnrollmentAttempt(reservation.serverId, OWNER, ATTEMPT),
        )
        assertEquals(0, dao.count())
    }

    @Test
    fun freshInvitationCanReplaceAnAttemptAtTheStrictReplayDeadline() {
        val dao = db.uploadServerDao()
        val firstRequestAt = NOW
        val deadline = firstRequestAt + REPLAY_WINDOW
        dao.reserveSingleEnrollment(
            pendingServer().copy(
                pendingEnrollmentFirstRequestAtEpochMillis = firstRequestAt,
                pendingEnrollmentReplayDeadlineEpochMillis = deadline,
            ),
            ownerNonce = OWNER,
            nowEpochMillis = NOW,
        )

        val replacement = dao.reserveSingleEnrollment(
            pendingServer(
                attempt = REPLACEMENT_ATTEMPT,
                accessCode = REPLACEMENT_CODE,
                proposedKey = REPLACEMENT_KEY,
            ),
            ownerNonce = REPLACEMENT_OWNER,
            nowEpochMillis = deadline,
        )

        assertEquals(1, dao.count())
        val restored = dao.getById(replacement.serverId)
        assertEquals(REPLACEMENT_OWNER, restored?.reservationNonce)
        assertEquals(REPLACEMENT_ATTEMPT, restored?.pendingEnrollmentAttemptId)
        assertEquals(REPLACEMENT_CODE, restored?.pendingEnrollmentAccessCode)
        assertEquals(REPLACEMENT_KEY, restored?.pendingProposedApiKey)
        assertEquals(SOURCE_DEVICE_JSON, restored?.pendingEnrollmentSourceDeviceJson)
    }

    private fun openDatabase(): ChronicleDb = Room.databaseBuilder(
        context,
        ChronicleDb::class.java,
        TEST_DB,
    ).allowMainThreadQueries().build()

    private fun pendingServer(
        attempt: String = ATTEMPT,
        accessCode: String = ACCESS_CODE,
        proposedKey: String = PROPOSED_KEY,
    ): UploadServerEntity = UploadServerEntity(
        name = "Study",
        url = "https://study.example",
        studyId = STUDY_ID,
        participantId = "participant-a",
        sourceDeviceId = SOURCE_DEVICE,
        studyDisclosureJson = DISCLOSURE,
        disclosureVersion = DISCLOSURE_VERSION,
        manifestDigest = MANIFEST_DIGEST,
        pendingAcceptedModuleIds = "",
        pendingDeclinedModuleIds = "",
        pendingUnavailableModuleIds = UNAVAILABLE_MODULES,
        pendingEnrollmentAttemptId = attempt,
        pendingEnrollmentAccessCode = accessCode,
        pendingEnrollmentInviteExpiresAtEpochMillis = NOW + REPLAY_WINDOW,
        pendingProposedApiKey = proposedKey,
        pendingEnrollmentSourceDeviceJson = SOURCE_DEVICE_JSON,
        enabled = false,
        enrollmentSetupComplete = false,
    )

    private companion object {
        const val TEST_DB = "enrollment-replay-dao-test"
        const val NOW = 2_000_000L
        const val REPLAY_WINDOW = 24 * 60 * 60 * 1_000L
        const val STUDY_ID = "11111111-1111-1111-1111-111111111111"
        const val SOURCE_DEVICE = "22222222-2222-2222-2222-222222222222"
        const val SOURCE_DEVICE_JSON = "{\"device\":\"$SOURCE_DEVICE\",\"model\":\"test\"}"
        const val UNAVAILABLE_MODULES = "sensor_gyroscope"
        const val OWNER = "owner-a"
        const val ATTEMPT = "33333333-3333-3333-3333-333333333333"
        const val ACCESS_CODE = "one-time-code-a"
        const val PROPOSED_KEY = "ck_AAAAAAAA_AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA"
        const val REPLACEMENT_OWNER = "owner-b"
        const val REPLACEMENT_ATTEMPT = "44444444-4444-4444-4444-444444444444"
        const val REPLACEMENT_CODE = "one-time-code-b"
        const val REPLACEMENT_KEY = "ck_ABCDEFGH_ABCDEFGHIJKLMNOPQRSTUVWXYZ012345"
        const val MANIFEST_DIGEST =
            "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"
        const val DISCLOSURE_VERSION = "v1"
        const val DISCLOSURE = "{\"manifest\":\"test-only\"}"
    }
}
