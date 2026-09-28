package com.openlattice.chronicle.collection.activity

import android.util.Log
import com.openlattice.chronicle.collection.core.ModuleResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SleepActivityWriteOutcomeTest {
    @Test
    fun onlyOkReportsPersisted() {
        assertEquals(Log.INFO to "Persisted 3 sleep sample(s)", SleepActivityReceiver.writeOutcome("sleep sample(s)", 3, ModuleResult.Ok(3)))
    }

    @Test
    fun failedAndRetryAreErrorsAndNeverClaimPersisted() {
        for (result in listOf(ModuleResult.Failed(IllegalStateException("disk full")), ModuleResult.Retry("busy"))) {
            val (priority, text) = SleepActivityReceiver.writeOutcome("sleep sample(s)", 3, result)
            assertEquals(Log.ERROR, priority)
            assertTrue(text, !text.startsWith("Persisted"))
        }
    }
}
