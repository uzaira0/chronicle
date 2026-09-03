package com.openlattice.chronicle.collection.device

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test

class HealthMetricReadCoordinatorTest {
    @Test
    fun successfulWindowAdvancesCheckpointAfterRead() {
        val checkpoint = FakeCheckpoint()
        val coordinator = HealthMetricReadCoordinator(checkpoint, defaultBackfillMillis = 1_000)

        val records = coordinator.read(nowMillis = 5_000) { start, end ->
            assertEquals(4_000L, start)
            assertEquals(5_000L, end)
            assertNull(checkpoint.value)
            listOf("record")
        }

        assertEquals(listOf("record"), records)
        assertNull(checkpoint.value)
        coordinator.acknowledge()
        assertEquals(5_000L, checkpoint.value)
    }

    @Test
    fun failedWindowDoesNotAdvanceCheckpoint() {
        val checkpoint = FakeCheckpoint(2_000)
        val coordinator = HealthMetricReadCoordinator(checkpoint)

        assertThrows(IllegalStateException::class.java) {
            coordinator.read<String>(nowMillis = 5_000) { _, _ -> error("read failed") }
        }

        assertEquals(2_000L, checkpoint.value)
    }

    @Test
    fun futureCheckpointSkipsReadAndDoesNotRewriteCheckpoint() {
        val checkpoint = FakeCheckpoint(6_000)
        val coordinator = HealthMetricReadCoordinator(checkpoint)
        var reads = 0

        val records = coordinator.read(nowMillis = 5_000) { _, _ ->
            reads += 1
            listOf("unexpected")
        }

        assertEquals(emptyList<String>(), records)
        assertEquals(0, reads)
        assertEquals(6_000L, checkpoint.value)
        assertEquals(0, checkpoint.writes)
    }

    @Test
    fun rejectedWindowCanBeRetriedWithoutAdvancingCheckpoint() {
        val checkpoint = FakeCheckpoint(2_000)
        val coordinator = HealthMetricReadCoordinator(checkpoint)

        coordinator.read<String>(5_000) { _, _ -> listOf("first") }
        coordinator.reject()
        val retried = coordinator.read(6_000) { start, end ->
            assertEquals(2_000L, start)
            assertEquals(6_000L, end)
            listOf("retry")
        }

        assertEquals(listOf("retry"), retried)
        assertEquals(2_000L, checkpoint.value)
    }

    @Test
    fun pagerReturnsAllPagesInOrder() = runBlocking {
        val requestedTokens = mutableListOf<String?>()

        val records = readAllHealthMetricPages { token ->
            requestedTokens += token
            when (token) {
                null -> HealthMetricPage(listOf(1, 2), "next")
                "next" -> HealthMetricPage(listOf(3), null)
                else -> error("unexpected token")
            }
        }

        assertEquals(listOf(null, "next"), requestedTokens)
        assertEquals(listOf(1, 2, 3), records)
    }

    @Test
    fun pagerRejectsRepeatedToken() {
        assertThrows(IllegalStateException::class.java) {
            runBlocking {
                readAllHealthMetricPages<Int> { HealthMetricPage(emptyList(), "same") }
            }
        }
    }

    private class FakeCheckpoint(initial: Long? = null) : HealthMetricCheckpoint {
        var value: Long? = initial
        var writes: Int = 0

        override fun read(): Long? = value

        override fun write(endMillis: Long) {
            value = endMillis
            writes += 1
        }
    }
}
