package com.openlattice.chronicle.services.withdrawal

import android.app.Application
import android.content.SharedPreferences
import androidx.test.core.app.ApplicationProvider
import androidx.work.ListenableWorker
import androidx.work.WorkManager
import com.openlattice.chronicle.EnrollmentTestStores
import com.openlattice.chronicle.WorkManagerRule
import com.openlattice.chronicle.audit.AuditStores
import com.openlattice.chronicle.audit.auditWorkerParameters
import com.openlattice.chronicle.audit.studyApi
import com.openlattice.chronicle.collection.state.BackgroundPersistenceRule
import com.openlattice.chronicle.collection.state.ResearchPersistenceGate
import com.openlattice.chronicle.storage.ChronicleDb
import com.openlattice.chronicle.study.EnrollmentWithdrawalResponse
import java.util.UUID
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Leave study when the encrypted preferences cannot commit (disk full, keystore failure). */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class WithdrawalStorageFailureTest {
    @get:Rule val workManagerRule = WorkManagerRule()
    @get:Rule val persistence = BackgroundPersistenceRule()
    private val context = ApplicationProvider.getApplicationContext<Application>()
    private lateinit var healthy: SharedPreferences

    @Before fun enroll() {
        AuditStores.install(context, enrolled = true)
        healthy = EnrollmentTestStores.prefs!!
    }

    @After fun closeStore() {
        ResearchPersistenceGate.resetAfterLocalStoreRecovery()
        EnrollmentTestStores.db?.close()
        EnrollmentTestStores.db = null
        EnrollmentTestStores.prefs = null
        EnrollmentTestStores.api = null
    }

    /** Commits fail while they write the withdrawal state [value]; every other write succeeds. */
    private fun refuseWithdrawalState(value: WithdrawalState) {
        val prefs = healthy
        EnrollmentTestStores.prefs = object : SharedPreferences by prefs {
            override fun edit(): SharedPreferences.Editor {
                val editor = prefs.edit()
                return object : SharedPreferences.Editor by editor {
                    private var fail = false
                    override fun putString(key: String, text: String?): SharedPreferences.Editor {
                        fail = fail || (key == "participant_withdrawal_state" && text == value.name)
                        editor.putString(key, text)
                        return this
                    }
                    override fun remove(key: String): SharedPreferences.Editor { editor.remove(key); return this }
                    override fun commit(): Boolean = !fail && editor.commit()
                }
            }
        }
    }

    private fun withdrawalWork() = WorkManager.getInstance(context)
        .getWorkInfosForUniqueWork(ParticipantWithdrawalManager.WORK_NAME).get()

    @Test fun leaveStudyThatCannotPersistItsRequestReportsFailureAndCanBeRetried() {
        refuseWithdrawalState(WithdrawalState.PENDING)

        assertFalse(ParticipantWithdrawalManager.begin(context))
        assertEquals(WithdrawalState.NONE, WithdrawalStateStore(context).state())
        assertTrue("no deletion request may run without its durable identity", withdrawalWork().isEmpty())

        EnrollmentTestStores.prefs = healthy
        assertTrue(ParticipantWithdrawalManager.begin(context))
        assertEquals(WithdrawalState.PENDING, WithdrawalStateStore(context).state())
        assertEquals(1, withdrawalWork().size)
    }

    @Test fun acknowledgedWithdrawalWhoseFinalStateCannotCommitRetriesAndThenCompletes() {
        assertTrue(ParticipantWithdrawalManager.begin(context))
        val requestIds = mutableListOf<String>()
        EnrollmentTestStores.api = studyApi { method, args ->
            check(method == "withdrawCurrentEnrollment")
            requestIds += args!![2] as String
            EnrollmentWithdrawalResponse(requestId = UUID.fromString(requestIds.last()), deletionJobIds = emptyList(),
                alreadyWithdrawn = false)
        }
        refuseWithdrawalState(WithdrawalState.COMPLETE)

        assertEquals(ListenableWorker.Result.retry(), ParticipantWithdrawalWorker(context, auditWorkerParameters()).doWork())
        assertEquals(WithdrawalState.PENDING, WithdrawalStateStore(context).state())

        EnrollmentTestStores.prefs = healthy
        assertEquals(ListenableWorker.Result.success(), ParticipantWithdrawalWorker(context, auditWorkerParameters()).doWork())
        assertEquals(WithdrawalState.COMPLETE, WithdrawalStateStore(context).state())
        assertEquals("the server acknowledged once and is not asked again", 1, requestIds.size)
        assertNull(ChronicleDb.getInstance(context).uploadServerDao().getConfiguredServer())
    }

    /**
     * The server acknowledges the withdrawal but the full disk refuses the acknowledgment record: the run retries, and
     * the retry asks the server again with the same request identity, which it answers idempotently.
     */
    @Test fun anAcknowledgmentTheFullDiskRefusesIsRequestedAgainWithTheSameIdentity() {
        assertTrue(ParticipantWithdrawalManager.begin(context))
        val requestIds = mutableListOf<String>()
        EnrollmentTestStores.api = studyApi { method, args ->
            check(method == "withdrawCurrentEnrollment")
            requestIds += args!![2] as String
            EnrollmentWithdrawalResponse(requestId = UUID.fromString(requestIds.last()), deletionJobIds = emptyList(),
                alreadyWithdrawn = requestIds.size > 1)
        }
        val prefs = healthy
        EnrollmentTestStores.prefs = object : SharedPreferences by prefs {
            override fun edit(): SharedPreferences.Editor {
                val editor = prefs.edit()
                return object : SharedPreferences.Editor by editor {
                    private var fail = false
                    override fun putStringSet(key: String, values: MutableSet<String>?): SharedPreferences.Editor {
                        fail = fail || key == "participant_withdrawal_acknowledged_servers"
                        editor.putStringSet(key, values)
                        return this
                    }
                    override fun commit(): Boolean = !fail && editor.commit()
                }
            }
        }

        assertEquals(ListenableWorker.Result.retry(), ParticipantWithdrawalWorker(context, auditWorkerParameters()).doWork())
        assertEquals(WithdrawalState.PENDING, WithdrawalStateStore(context).state())

        EnrollmentTestStores.prefs = healthy
        assertEquals(ListenableWorker.Result.success(), ParticipantWithdrawalWorker(context, auditWorkerParameters()).doWork())
        assertEquals(WithdrawalState.COMPLETE, WithdrawalStateStore(context).state())
        assertEquals(2, requestIds.size)
        assertEquals("the retry reuses the request identity", 1, requestIds.toSet().size)
    }
}
