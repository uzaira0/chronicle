package com.openlattice.chronicle.ui

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.openlattice.chronicle.layout.TestStores
import com.openlattice.chronicle.services.withdrawal.WithdrawalStateStore
import com.openlattice.chronicle.services.withdrawal.WithdrawalState
import com.openlattice.chronicle.preferences.EnrollmentSettings
import com.openlattice.chronicle.data.ParticipationStatus
import com.openlattice.chronicle.collection.state.ResearchPersistenceGate
import com.openlattice.chronicle.collection.state.onPersistenceWorker
import com.openlattice.chronicle.preferences.DEVICE_IDENTITY_PREFS_FILE
import com.openlattice.chronicle.preferences.DeviceInstanceIdentity
import com.openlattice.chronicle.services.upload.LocalUploadDiagnosticsStore
import com.openlattice.chronicle.services.withdrawal.ParticipantWithdrawalWorker
import com.openlattice.chronicle.storage.ChronicleDb
import com.openlattice.chronicle.audit.auditWorkerParameters
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class WithdrawalDashboardAuditTest {
    @Test fun durablePendingWithdrawalTakesPrecedenceOverTeamTerminationCopy() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        TestStores.install(context, enrolled = true)
        val store = WithdrawalStateStore(context)
        store.beginWithdrawal()
        EnrollmentSettings(context).setParticipationStatus(ParticipationStatus.NOT_ENROLLED)
        val snapshot = DashboardDataRepository.load(context)
        assertEquals("WITHDRAWAL_PENDING", snapshot.participationStop?.name)
        assertEquals(0, snapshot.collection.active)
        assertTrue(snapshot.collection.message.contains("withdrawal", ignoreCase = true))
        assertFalse(snapshot.collection.message.contains("study team", ignoreCase = true))
        assertEquals(WithdrawalState.PENDING, WithdrawalStateStore(context).state())
    }

    @Test fun acknowledgedWithdrawalRetiresTheIdentityBeforeReenrollment() = onPersistenceWorker {
        val context = ApplicationProvider.getApplicationContext<Context>()
        TestStores.install(context, enrolled = true)
        ResearchPersistenceGate.resetAfterLocalStoreRecovery()
        ResearchPersistenceGate.initialize(context)
        val prefs = context.getSharedPreferences(DEVICE_IDENTITY_PREFS_FILE, Context.MODE_PRIVATE)
        val before = DeviceInstanceIdentity.getOrCreate(prefs)
        val db = ChronicleDb.getInstance(context)
        val owner = db.uploadServerDao().getConfiguredServer()!!
        WithdrawalStateStore(context).setState(WithdrawalState.PENDING)
        assertEquals(before, DeviceInstanceIdentity.getOrCreate(prefs))
        val worker = ParticipantWithdrawalWorker(context,
            auditWorkerParameters())
        val result = worker.javaClass.getDeclaredMethod("finishAcknowledgedWithdrawal",
            LocalUploadDiagnosticsStore::class.java,
            List::class.java, Boolean::class.javaPrimitiveType!!).apply { isAccessible = true }
            .invoke(worker, LocalUploadDiagnosticsStore.of(context), listOf(owner), false)
        assertEquals(androidx.work.ListenableWorker.Result.success(), result)
        val after = DeviceInstanceIdentity.getOrCreate(
            context.getSharedPreferences(DEVICE_IDENTITY_PREFS_FILE, Context.MODE_PRIVATE))
        assertNotEquals(before, after)
        assertEquals(WithdrawalState.COMPLETE, WithdrawalStateStore(context).state())
    }
}
