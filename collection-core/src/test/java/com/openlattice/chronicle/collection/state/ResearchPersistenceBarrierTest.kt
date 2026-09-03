package com.openlattice.chronicle.collection.state

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ResearchPersistenceBarrierTest {

    @Test
    fun stopWaitsForAnInFlightWriteAndRejectsEveryLaterWrite() {
        val barrier = ResearchPersistenceBarrier()
        val collectionAllowed = AtomicBoolean(true)
        val writeEntered = CountDownLatch(1)
        val releaseWrite = CountDownLatch(1)
        val stopReturned = CountDownLatch(1)
        val writes = AtomicInteger()

        val writer = thread {
            barrier.persistIf(collectionAllowed::get) {
                writeEntered.countDown()
                assertTrue(releaseWrite.await(5, TimeUnit.SECONDS))
                writes.incrementAndGet()
            }
        }
        assertTrue(writeEntered.await(5, TimeUnit.SECONDS))

        val stopper = thread {
            barrier.stop {
                collectionAllowed.set(false)
            }
            stopReturned.countDown()
        }
        assertFalse(
            "Withdrawal must not return while a persistence callback still owns the read boundary",
            stopReturned.await(100, TimeUnit.MILLISECONDS),
        )

        releaseWrite.countDown()
        writer.join(5_000)
        stopper.join(5_000)
        assertTrue(stopReturned.await(1, TimeUnit.SECONDS))

        val accepted = barrier.persistIf(collectionAllowed::get) {
            writes.incrementAndGet()
        }
        assertFalse(accepted)
        assertEquals(1, writes.get())
    }

    @Test
    fun deniedWriteNeverInvokesThePersistenceCallback() {
        val barrier = ResearchPersistenceBarrier()
        val writes = AtomicInteger()

        assertFalse(barrier.persistIf({ false }) { writes.incrementAndGet() })
        assertEquals(0, writes.get())
    }

    @Test
    fun stopCannotReturnWhileAnAdmittedOutboundRequestIsInFlight() {
        val barrier = ResearchPersistenceBarrier()
        val allowed = AtomicBoolean(true)
        val requestEntered = CountDownLatch(1)
        val releaseRequest = CountDownLatch(1)
        val stopReturned = CountDownLatch(1)

        val request = thread {
            barrier.persistIf(allowed::get) {
                requestEntered.countDown()
                assertTrue(releaseRequest.await(5, TimeUnit.SECONDS))
            }
        }
        assertTrue(requestEntered.await(5, TimeUnit.SECONDS))

        val stop = thread {
            barrier.stop { allowed.set(false) }
            stopReturned.countDown()
        }
        assertFalse(stopReturned.await(100, TimeUnit.MILLISECONDS))
        releaseRequest.countDown()
        request.join(5_000)
        stop.join(5_000)
        assertTrue(stopReturned.await(1, TimeUnit.SECONDS))
        assertFalse(barrier.persistIf(allowed::get) { error("late request executed") })
    }
}
