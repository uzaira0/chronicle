package com.openlattice.chronicle.storage

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class EnrollmentReservationMutationTest {
    private val db = Room.inMemoryDatabaseBuilder(
        ApplicationProvider.getApplicationContext<Context>(), ChronicleDb::class.java,
    ).allowMainThreadQueries().build()
    private val dao = db.uploadServerDao()
    private val request = UploadServerEntity(
        name = "Study", url = "https://localhost", studyId = "study", participantId = "participant",
        sourceRandomDeviceId = "device", pendingEnrollmentAttemptId = "attempt",
        pendingEnrollmentAccessCode = "invitation", pendingProposedApiKey = "proposed-key",
        pendingEnrollmentSourceDeviceJson = "{}",
        pendingAcceptedModuleIds = "", pendingDeclinedModuleIds = "", pendingUnavailableModuleIds = "",
        studyDisclosureJson = "{}", disclosureVersion = "v1", manifestDigest = "digest",
    )

    @After fun close() = db.close()

    @Test fun refreshAndPromotionKeepTheExactReservationAndReplayIdentity() {
        val reservation = dao.reserveSingleEnrollment(request, ownerNonce = "owner", nowEpochMillis = 1000)
        val foreign = reservation.copy(ownerNonce = "foreign")
        val revised = request.copy(name = "Updated", sourceRandomDeviceId = "updated-device")
        assertEquals(0, dao.preparePendingEnrollmentAttempt(foreign, revised))
        assertEquals("Study", dao.getById(reservation.serverId)!!.name)
        assertEquals(1, dao.preparePendingEnrollmentAttempt(reservation, revised))
        val pending = dao.getById(reservation.serverId)!!
        val issued = revised.copy(apiKey = "proposed-key", pendingAcceptedModuleIds = "",
            pendingDeclinedModuleIds = "", pendingUnavailableModuleIds = "")
        assertEquals(0, dao.persistIssuedEnrollment(foreign, "attempt", issued, 1500))
        assertEquals(0, dao.persistIssuedEnrollment(reservation, "other-attempt", issued, 1500))
        assertEquals(0, dao.persistIssuedEnrollment(reservation, "attempt", issued.copy(apiKey = "other-key"), 1500))
        assertEquals(pending, dao.getById(reservation.serverId))
        assertEquals(1, dao.persistIssuedEnrollment(reservation, "attempt", issued, 1500))
        val saved = dao.getById(reservation.serverId)!!
        assertEquals("updated-device", saved.sourceRandomDeviceId)
        assertEquals("proposed-key", saved.apiKey)
        assertEquals(1500L, saved.enrollmentIssuedAtEpochMillis)
        assertEquals("owner", saved.reservationNonce)
        assertEquals(pending.createdAt, saved.createdAt)
        assertNull(saved.pendingEnrollmentAttemptId)
        assertNull(saved.pendingEnrollmentAccessCode)
        assertNull(saved.pendingProposedApiKey)
        assertNull(saved.pendingEnrollmentSourceDeviceJson)
        assertEquals(0, dao.preparePendingEnrollmentAttempt(reservation, request))
    }

    @Test fun finalizationRefusesAnotherIdentityAndPreservesUploadProgress() {
        val reservation = dao.reserveSingleEnrollment(request,
            ownerNonce = "owner", nowEpochMillis = 1000)
        val current = dao.getById(reservation.serverId)!!
        dao.update(current.copy(lastUploadedTimestamp = 123))
        val issued = request.copy(apiKey = "issued-key")
        try {
            dao.finalizeSingleEnrollment(reservation, issued.copy(participantId = "other"))
            throw AssertionError("Finalization accepted another identity")
        } catch (_: SingleEnrollmentConflictException) {
            assertEquals(123L, dao.getById(reservation.serverId)!!.lastUploadedTimestamp)
        }
        dao.finalizeSingleEnrollment(reservation, issued)
        val saved = dao.getById(reservation.serverId)!!
        assertEquals("issued-key", saved.apiKey)
        assertEquals(123L, saved.lastUploadedTimestamp)
        assertEquals(true, saved.enrollmentSetupComplete)
        assertNull(saved.reservationNonce)
    }

    /** The invitation link is opened twice while the first attempt is in flight: the second is refused, the first keeps the slot. */
    @Test fun aSecondAttemptForTheSameInvitationIsRefusedWhileTheFirstIsInFlight() {
        val first = dao.reserveSingleEnrollment(request, ownerNonce = "owner", nowEpochMillis = 1000)
        assertThrows(SingleEnrollmentConflictException::class.java) {
            dao.reserveSingleEnrollment(request.copy(pendingEnrollmentAttemptId = "attempt-2"), ownerNonce = "second", nowEpochMillis = 2000)
        }
        val slot = dao.getById(first.serverId)!!
        assertEquals("owner", slot.reservationNonce)
        assertEquals("attempt", slot.pendingEnrollmentAttemptId)
    }

    /** Another study's invitation while an attempt is unanswered, even after its lease lapsed: the attempt stays for its replay. */
    @Test fun anotherStudysInvitationCannotReplaceAnUnansweredAttempt() {
        val first = dao.reserveSingleEnrollment(request, ownerNonce = "owner", nowEpochMillis = 1000)
        assertThrows(SingleEnrollmentConflictException::class.java) {
            dao.reserveSingleEnrollment(request.copy(studyId = "other-study", pendingEnrollmentAttemptId = "attempt-2"),
                ownerNonce = "second", nowEpochMillis = 1000 + UploadServerDao.ENROLLMENT_RESERVATION_LEASE_MILLIS + 1)
        }
        assertEquals(listOf(first.serverId), dao.getAll().map { it.id })
        assertEquals("study", dao.getById(first.serverId)!!.studyId)
    }

    /** An enrolled device refuses every new invitation, its own included, and keeps its credential. */
    @Test fun anEnrolledDeviceRefusesEveryNewReservation() {
        val reservation = dao.reserveSingleEnrollment(request, ownerNonce = "owner", nowEpochMillis = 1000)
        dao.finalizeSingleEnrollment(reservation, request.copy(apiKey = "issued-key"))
        for (invitation in listOf(request, request.copy(studyId = "other-study"))) {
            assertThrows(SingleEnrollmentConflictException::class.java) {
                dao.reserveSingleEnrollment(invitation, ownerNonce = "second", nowEpochMillis = 2000)
            }
        }
        assertEquals("issued-key", dao.getById(reservation.serverId)!!.apiKey)
    }
}
