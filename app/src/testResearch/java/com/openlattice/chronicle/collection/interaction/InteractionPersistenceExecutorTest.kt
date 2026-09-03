package com.openlattice.chronicle.collection.interaction

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class InteractionPersistenceExecutorTest {
    @Test
    fun `queue is bounded and shutdown reports abandoned work`() {
        val dropped = AtomicInteger()
        val firstStarted = CountDownLatch(1)
        val releaseFirst = CountDownLatch(1)
        val executor = BoundedInteractionTaskExecutor(capacity = 1) { dropped.addAndGet(it) }

        assertTrue(executor.execute {
            firstStarted.countDown()
            try {
                releaseFirst.await()
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
            }
        })
        assertTrue(firstStarted.await(2, TimeUnit.SECONDS))
        assertTrue(executor.execute { /* fills the sole queue slot */ })

        assertFalse(executor.execute { error("must be rejected") })
        assertEquals(1, dropped.get())

        assertEquals(1, executor.shutdownNow())
        assertEquals(2, dropped.get())
        releaseFirst.countDown()

        assertFalse(executor.execute { error("must be rejected after shutdown") })
        assertEquals(3, dropped.get())
    }
}
