package com.openlattice.chronicle.collection.lifecycle

import com.openlattice.chronicle.collection.core.ModuleResult
import com.openlattice.chronicle.collection.sink.FakeStorageQueue
import com.openlattice.chronicle.collection.sink.LifecycleEventSink
import com.openlattice.chronicle.collection.state.CollectionPersistenceGuard
import com.openlattice.chronicle.serialization.JsonSerializer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class DeviceLifecycleAdmissionAccountingTest {
    @Test fun failureBeforeAdmissionAccountsEveryCapturedEvent() = assertFailedAdmission(afterWrite = false)

    @Test fun failureAfterAdmissionAccountsOnlyTheDeduplicatedEvents() = assertFailedAdmission(afterWrite = true)

    private fun assertFailedAdmission(afterWrite: Boolean) {
        val dao = FakeStorageQueue()
        val failure = IllegalStateException("admission evidence failed")
        var depth = 0
        var losses = 0
        val guard = CollectionPersistenceGuard { write ->
            val outer = depth++ == 0
            try {
                if (!outer || afterWrite) write()
                if (outer) throw failure
                true
            } finally {
                depth--
                if (outer) dao.deleteAll()
            }
        }
        val module = DeviceLifecycleCollectionModule(
            sink = LifecycleEventSink(dao, guard),
            dedupeStore = FakeLifecycleDedupeStore(),
            enrolled = { true },
            serializeQueueEntry = { JsonSerializer.serializeQueueEntry(it) },
            now = { 50_000L },
            captureLossRecorder = { { issue, count ->
                assertEquals("LOCAL_WRITE_FAILED", issue)
                assertEquals("account after admission unwinds", 0, depth)
                assertEquals("the queue write was rolled back", 0, dao.getSize())
                losses += count
            } },
        )
        val event = LifecycleEventMapper.buildEvent("android.intent.action.BATTERY_LOW", "Battery Low", 1_700_000_000_000L)

        val result = module.persist(listOf(event, event))

        assertTrue(result is ModuleResult.Failed)
        assertSame(failure, (result as ModuleResult.Failed).error)
        assertEquals(0, dao.getSize())
        assertEquals(if (afterWrite) 1 else 2, losses)
    }
}
