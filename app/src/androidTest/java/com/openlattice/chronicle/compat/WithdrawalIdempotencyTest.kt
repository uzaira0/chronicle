package com.openlattice.chronicle.compat

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.work.WorkManager
import com.openlattice.chronicle.services.withdrawal.ParticipantWithdrawalManager
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.TimeUnit

@RunWith(AndroidJUnit4::class)
class WithdrawalIdempotencyTest {
    @Test
    fun repeatedBeginUsesOneUniqueWithdrawalChain() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()

        assertTrue(ParticipantWithdrawalManager.begin(context))
        assertTrue(ParticipantWithdrawalManager.begin(context))

        val work = WorkManager.getInstance(context)
            .getWorkInfosForUniqueWork(ParticipantWithdrawalManager.WORK_NAME)
            .get(10, TimeUnit.SECONDS)
        assertEquals(1, work.size)
    }
}
