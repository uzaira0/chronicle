package com.openlattice.chronicle.collection.state

import com.openlattice.chronicle.collection.core.ModuleResult
import com.openlattice.chronicle.collection.core.PullAccounting
import com.openlattice.chronicle.collection.core.SnapshotCollectionModule
import com.openlattice.chronicle.collection.sink.BatterySampleSink
import com.openlattice.chronicle.storage.BatterySampleDao
import com.openlattice.chronicle.storage.BatterySampleEntry
import java.lang.reflect.Proxy
import java.util.concurrent.CancellationException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.Parameterized

@RunWith(Parameterized::class)
class ObservationAdmissionFailureTest(private val caller: String, private val failAfterWrite: Boolean) {
    @Test fun admissionFailureReturnsRedactedFailedAfterRollback() {
        val error = if (caller == "sink") UnsupportedOperationException("private admission evidence")
            else IllegalStateException("private admission evidence")
        var pendingWrite = false
        var accounted = 0
        val guard = CollectionPersistenceGuard { write ->
            try {
                if (failAfterWrite) write()
                throw error
            } finally {
                pendingWrite = false
            }
        }
        val write = { pendingWrite = true; ModuleResult.Ok(1) }
        val result = if (caller == "snapshot") {
            object : SnapshotCollectionModule<Int>("test", { true }, PullAccounting(accountWriteResult = { _, result, count ->
                assertFalse("account after rollback", pendingWrite)
                assertTrue(result is ModuleResult.Failed)
                accounted += count
            }), { 1L }) {
                override fun captureAdmission() = guard
                override fun read() = 1
                override fun persist(reading: Int, now: Long) = write()
            }.sample()
        } else if (caller == "sink") {
            val dao = Proxy.newProxyInstance(
                BatterySampleDao::class.java.classLoader, arrayOf(BatterySampleDao::class.java),
            ) { _, method, _ ->
                check(method.name == "insertAll") { "Unexpected DAO call: ${method.name}" }
                pendingWrite = true
                Unit
            } as BatterySampleDao
            BatterySampleSink(dao, guard).write(listOf(BatterySampleEntry(
                "sample", "2026-01-01T00:00:00Z", "UTC", 50, "NONE", "NONE", 200, 4000, "GOOD",
            )))
        } else guard.writeObservation(write = write)
        assertTrue("$caller returned a failure", result is ModuleResult.Failed)
        result as ModuleResult.Failed
        assertSame(error, result.error)
        assertFalse(result.redactedMessage.contains("private admission evidence"))
        assertEquals("observation persistence failed: ${error.javaClass.simpleName}", result.redactedMessage)
        assertFalse(pendingWrite)
        assertEquals(if (caller == "snapshot") 1 else 0, accounted)
    }

    @Test fun cancellationIsNeverConvertedToAPersistenceFailure() {
        val cancellation = CancellationException("cancelled")
        val guard = CollectionPersistenceGuard { throw cancellation }
        assertSame(cancellation, assertThrows(CancellationException::class.java) {
            guard.writeObservation { ModuleResult.Ok(1) }
        })
    }

    companion object {
        @JvmStatic @Parameterized.Parameters(name = "{0}, failAfterWrite={1}")
        fun cases(): List<Array<Any>> = listOf("direct", "snapshot", "sink").flatMap { caller ->
            listOf(false, true).map { arrayOf(caller, it) }
        }
    }
}
