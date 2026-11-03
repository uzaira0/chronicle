package com.openlattice.chronicle.storage

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.openlattice.chronicle.collection.CollectionModuleId
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
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
    fun reopeningKeepsTheExactBoundRequest() {
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

    /** Every database reopen preserves the sent attempt's binding and its subsequent credential promotion. */
    @Test
    fun eachReopenPreservesTheSentAttemptBindingAndCredentialPromotion() {
        val reservation = db.uploadServerDao().reserveSingleEnrollment(pendingServer(), ownerNonce = OWNER, nowEpochMillis = NOW)
        fun reopen() { db.close(); db = openDatabase() }
        fun assertReplay(firstRequest: Long?) {
            val row = db.uploadServerDao().getAll().single()
            assertEquals(reservation.serverId, row.id)
            assertEquals(listOf(OWNER, ATTEMPT, ACCESS_CODE, PROPOSED_KEY, SOURCE_DEVICE, SOURCE_DEVICE_JSON, MANIFEST_DIGEST,
                DISCLOSURE, DISCLOSURE_VERSION, UNAVAILABLE_MODULES),
                listOf(row.reservationNonce, row.pendingEnrollmentAttemptId, row.pendingEnrollmentAccessCode,
                    row.pendingProposedApiKey, row.sourceRandomDeviceId, row.pendingEnrollmentSourceDeviceJson,
                    row.manifestDigest, row.studyDisclosureJson, row.disclosureVersion, row.pendingUnavailableModuleIds))
            assertEquals(firstRequest, row.pendingEnrollmentFirstRequestAtEpochMillis)
            assertNull(row.apiKey)
            assertEquals(false, row.enrollmentSetupComplete)
        }

        reopen()
        assertReplay(firstRequest = null)
        assertEquals(1, db.uploadServerDao().markPendingEnrollmentRequestStarted(reservation.serverId, OWNER, ATTEMPT, NOW))
        // The server accepted, but its answer was lost with the process.
        reopen()
        assertReplay(firstRequest = NOW)
        reopen()
        assertReplay(firstRequest = NOW)
        assertEquals(1, db.uploadServerDao().persistIssuedEnrollment(
            reservation = reservation,
            enrollmentAttemptId = ATTEMPT,
            server = pendingServer().copy(apiKey = PROPOSED_KEY),
            issuedAtEpochMillis = NOW + 1,
        ))
        reopen()

        val issued = db.uploadServerDao().getAll().single()
        assertEquals(listOf(reservation.serverId, PROPOSED_KEY, SOURCE_DEVICE, MANIFEST_DIGEST),
            listOf(issued.id, issued.apiKey, issued.sourceRandomDeviceId, issued.manifestDigest))
        assertNotNull(issued.enrollmentIssuedAtEpochMillis)
        assertEquals(listOf(null, null, null, null), listOf(issued.pendingEnrollmentAttemptId,
            issued.pendingEnrollmentAccessCode, issued.pendingProposedApiKey, issued.pendingEnrollmentSourceDeviceJson))
        assertEquals("setup is not complete until the participant finishes it", false, issued.enrollmentSetupComplete)
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
            ),
        )

        assertEquals(
            1,
            dao.persistIssuedEnrollment(
                reservation = reservation,
                enrollmentAttemptId = ATTEMPT,
                server = pendingServer().copy(
                    name = "Study",
                    sourceRandomDeviceId = SOURCE_DEVICE,
                    apiKey = PROPOSED_KEY,
                    studyDisclosureJson = DISCLOSURE,
                    disclosureVersion = DISCLOSURE_VERSION,
                    manifestDigest = MANIFEST_DIGEST,
                    pendingAcceptedModuleIds = "",
                    pendingDeclinedModuleIds = "",
                    pendingUnavailableModuleIds = UNAVAILABLE_MODULES,
                ),
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
                reservation = reservation,
                enrollmentAttemptId = ATTEMPT,
                server = pendingServer().copy(
                    name = "Study",
                    sourceRandomDeviceId = SOURCE_DEVICE,
                    apiKey = "ck_ABCDEFGH_ABCDEFGHIJKLMNOPQRSTUVWXYZ012345",
                    studyDisclosureJson = DISCLOSURE,
                    disclosureVersion = DISCLOSURE_VERSION,
                    manifestDigest = MANIFEST_DIGEST,
                    pendingAcceptedModuleIds = "",
                    pendingDeclinedModuleIds = "",
                    pendingUnavailableModuleIds = UNAVAILABLE_MODULES,
                ),
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
            dao.deletePendingEnrollmentAttempt(reservation.serverId, OWNER, ATTEMPT, null),
        )
        assertEquals(0, dao.count())
    }

    /** Only the server ends a sent attempt; a fresh invitation cannot replace it whatever the phone clock reads. */
    @Test
    fun freshInvitationNeverReplacesASentAttempt() {
        val dao = db.uploadServerDao()
        dao.reserveSingleEnrollment(
            pendingServer().copy(pendingEnrollmentFirstRequestAtEpochMillis = NOW),
            ownerNonce = OWNER,
            nowEpochMillis = NOW,
        )

        assertThrows(SingleEnrollmentConflictException::class.java) {
            dao.reserveSingleEnrollment(
                pendingServer(attempt = REPLACEMENT_ATTEMPT, accessCode = REPLACEMENT_CODE, proposedKey = REPLACEMENT_KEY),
                ownerNonce = REPLACEMENT_OWNER,
                nowEpochMillis = NOW + 2 * DAY,
            )
        }

        val kept = dao.getAll().single()
        assertEquals(OWNER, kept.reservationNonce)
        assertEquals(ATTEMPT, kept.pendingEnrollmentAttemptId)
        assertEquals(PROPOSED_KEY, kept.pendingProposedApiKey)
    }

    /** The production SQLCipher/Keystore opener; this exercises database reopening within the instrumented process. */
    private fun openDatabase(): ChronicleDb = ChronicleDb.openEncryptedDatabase(context, TEST_DB)

    private fun pendingServer(
        attempt: String = ATTEMPT,
        accessCode: String = ACCESS_CODE,
        proposedKey: String = PROPOSED_KEY,
    ): UploadServerEntity = UploadServerEntity(
        name = "Study",
        url = "https://study.example",
        studyId = STUDY_ID,
        participantId = "participant-a",
        sourceRandomDeviceId = SOURCE_DEVICE,
        studyDisclosureJson = DISCLOSURE,
        disclosureVersion = DISCLOSURE_VERSION,
        manifestDigest = MANIFEST_DIGEST,
        pendingAcceptedModuleIds = "",
        pendingDeclinedModuleIds = "",
        pendingUnavailableModuleIds = UNAVAILABLE_MODULES,
        pendingEnrollmentAttemptId = attempt,
        pendingEnrollmentAccessCode = accessCode,
        pendingProposedApiKey = proposedKey,
        pendingEnrollmentSourceDeviceJson = SOURCE_DEVICE_JSON,
        enabled = false,
        enrollmentSetupComplete = false,
    )

    private companion object {
        const val TEST_DB = "enrollment-replay-dao-test"
        const val NOW = 2_000_000L
        const val DAY = 24 * 60 * 60 * 1_000L
        const val STUDY_ID = "11111111-1111-1111-1111-111111111111"
        const val SOURCE_DEVICE = "22222222-2222-2222-2222-222222222222"
        const val SOURCE_DEVICE_JSON = "{\"device\":\"$SOURCE_DEVICE\",\"model\":\"test\"}"
        val UNAVAILABLE_MODULES = CollectionModuleId.SENSOR_GYROSCOPE.id
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
