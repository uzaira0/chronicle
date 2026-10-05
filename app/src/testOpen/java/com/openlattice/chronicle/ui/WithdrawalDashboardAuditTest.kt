package com.openlattice.chronicle.ui

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.openlattice.chronicle.layout.TestStores
import com.openlattice.chronicle.services.withdrawal.WithdrawalStateStore
import com.openlattice.chronicle.services.withdrawal.WithdrawalState
import com.openlattice.chronicle.preferences.EnrollmentSettings
import com.openlattice.chronicle.data.ParticipationStatus
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
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
}
