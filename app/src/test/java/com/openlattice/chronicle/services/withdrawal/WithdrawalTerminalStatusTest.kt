package com.openlattice.chronicle.services.withdrawal

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import com.openlattice.chronicle.EnrollmentRecoveryResult
import com.openlattice.chronicle.EnrollmentRecoveryManager
import com.openlattice.chronicle.EnrollmentTestStores
import com.openlattice.chronicle.generated.EnrollmentAttemptContracts
import com.openlattice.chronicle.storage.ChronicleDb
import com.openlattice.chronicle.storage.QueueEntry
import com.openlattice.chronicle.WorkManagerRule
import com.openlattice.chronicle.audit.AuditStores
import com.openlattice.chronicle.audit.auditWorkerParameters
import com.openlattice.chronicle.audit.studyApi
import com.openlattice.chronicle.collection.state.BackgroundPersistenceRule
import com.openlattice.chronicle.serialization.ChronicleCallException
import androidx.work.ListenableWorker
import com.openlattice.chronicle.study.EnrollmentWithdrawalResponse
import java.net.HttpURLConnection
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class WithdrawalTerminalStatusTest {
    @get:Rule val workManagerRule = WorkManagerRule()
    @get:Rule val persistence = BackgroundPersistenceRule()
    private val context = ApplicationProvider.getApplicationContext<Application>()
    @Before fun enroll() {
        AuditStores.install(context, enrolled = true)
        WithdrawalStateStore(context).beginWithdrawal()
    }
    @After fun closeStore() {
        EnrollmentTestStores.db?.close()
        EnrollmentTestStores.db = null
        EnrollmentTestStores.prefs = null
        EnrollmentTestStores.api = null
    }

    @Test fun transientAuthenticationRefusalKeepsRetryingTheSameWithdrawal() {
        val requests = mutableListOf<String>()
        EnrollmentTestStores.api = studyApi { method, args ->
            check(method == "withdrawCurrentEnrollment")
            requests += args!![2] as String
            if (requests.size == 1) throw ChronicleCallException("POST", "request", "", HttpURLConnection.HTTP_UNAUTHORIZED)
            EnrollmentWithdrawalResponse(
                requestId = java.util.UUID.fromString(requests.last()), deletionJobIds = emptyList(), alreadyWithdrawn = false)
        }
        ParticipantWithdrawalWorker(context, auditWorkerParameters()).doWork()
        assertEquals(WithdrawalState.PENDING, WithdrawalStateStore(context).state())
        ParticipantWithdrawalWorker(context, auditWorkerParameters()).doWork()
        assertEquals(WithdrawalState.COMPLETE, WithdrawalStateStore(context).state())
        assertEquals(2, requests.size)
        assertEquals(1, requests.toSet().size)
    }
    @Test fun retiredCredentialRequiresSupport() = assertState(HttpURLConnection.HTTP_GONE, WithdrawalState.NEEDS_SUPPORT)

    /**
     * Leave study on an attempt the study team cancelled is answered 410 ENROLLMENT_CANCELLED. The
     * enrollment ends as cancelled through the same local end as the status poll and the ack: queue cleared, the
     * "attempt cancelled" message, a new invitation accepted.
     */
    @Test fun leaveStudyOnACancelledAttemptEndsTheEnrollmentAsCancelled() {
        val db = ChronicleDb.getInstance(context)
        db.queueEntryData().insertEntry(QueueEntry(1, 1, ByteArray(1)))
        assertState(HttpURLConnection.HTTP_GONE, WithdrawalState.CANCELLED,
            body = """{"status":410,"code":"${EnrollmentAttemptContracts.ENROLLMENT_CANCELLED_CODE}"}""")
        assertNull(db.uploadServerDao().getConfiguredServer())
        assertEquals("the queue was cleared", 0, db.queueEntryData().getSize())
        assertEquals(EnrollmentRecoveryResult.CANCELLED, EnrollmentRecoveryManager.resumeIfNeeded(context))
    }
    @Test fun temporaryFailureRetainsTheRetry() = assertState(HttpURLConnection.HTTP_UNAVAILABLE, WithdrawalState.PENDING)

    /**
     * The enrollment row holds no credential (its setup stopped before the key was installed): no request can be
     * authenticated, so none is sent, the local data is still erased and the participant is sent to the study team.
     */
    @Test fun aMissingCredentialNeedsSupportWithoutSendingARequest() {
        val dao = ChronicleDb.getInstance(context).uploadServerDao()
        dao.update(dao.getConfiguredServer()!!.copy(apiKey = null))
        val requests = mutableListOf<String>()
        EnrollmentTestStores.api = studyApi { method, _ -> requests += method; error("no request expected") }

        assertEquals(ListenableWorker.Result.success(), ParticipantWithdrawalWorker(context, auditWorkerParameters()).doWork())

        assertEquals(emptyList<String>(), requests)
        assertEquals(WithdrawalState.NEEDS_SUPPORT, WithdrawalStateStore(context).state())
        assertNull(dao.getConfiguredServer())
    }

    private fun assertState(status: Int, expected: WithdrawalState, body: String = "") {
        EnrollmentTestStores.api = studyApi { method, _ ->
            check(method == "withdrawCurrentEnrollment")
            throw ChronicleCallException("POST", "request", body, status)
        }
        ParticipantWithdrawalWorker(context, auditWorkerParameters()).doWork()
        assertEquals(expected, WithdrawalStateStore(context).state())
    }
}
