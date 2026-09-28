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
    fun leaseHolderTakingItsOwnLockCannotDeadlockAStopThatTakesTheSameLock() {
        // Mirrors the direct-boot drain (lease -> buffer lock -> persistIf) racing a sensor
        // discard (stop -> buffer lock). Without the lease the drain took the buffer lock first.
        val barrier = ResearchPersistenceBarrier()
        val bufferLock = Any()
        val leaseHeld = CountDownLatch(1)
        val stopQueued = CountDownLatch(1)
        val persisted = AtomicBoolean()
        val erased = AtomicBoolean()

        val drain = thread {
            barrier.withReadLease {
                leaseHeld.countDown()
                assertTrue(stopQueued.await(5, TimeUnit.SECONDS))
                Thread.sleep(100) // let the stop reach the queued-writer state
                synchronized(bufferLock) {
                    barrier.persistIf({ true }) { persisted.set(true) }
                }
            }
        }
        assertTrue(leaseHeld.await(5, TimeUnit.SECONDS))
        val discard = thread {
            stopQueued.countDown()
            barrier.stop { synchronized(bufferLock) { erased.set(true) } }
        }

        drain.join(5_000)
        discard.join(5_000)
        assertFalse("drain deadlocked", drain.isAlive)
        assertFalse("discard deadlocked", discard.isAlive)
        assertTrue(persisted.get())
        assertTrue(erased.get())
    }

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
    fun refusalAccountingFinishesBeforeWithdrawalCanCloseTheBarrier() {
        val barrier = ResearchPersistenceBarrier()
        val refusedEntered = CountDownLatch(1)
        val releaseRefusal = CountDownLatch(1)
        val stopped = CountDownLatch(1)
        val owner = java.util.concurrent.atomic.AtomicReference("A")
        val accounted = AtomicInteger()

        val writer = thread {
            barrier.persistIf(
                allowed = { false },
                onRefused = {
                    if (owner.get() == "A") accounted.incrementAndGet()
                    refusedEntered.countDown()
                    assertTrue(releaseRefusal.await(5, TimeUnit.SECONDS))
                },
                persist = { error("refused write persisted") },
            )
        }
        assertTrue(refusedEntered.await(5, TimeUnit.SECONDS))
        val stopper = thread {
            barrier.stop { owner.set("B") }
            stopped.countDown()
        }
        assertFalse(stopped.await(100, TimeUnit.MILLISECONDS))
        releaseRefusal.countDown()
        writer.join(5_000)
        stopper.join(5_000)
        assertEquals(1, accounted.get())
        assertTrue(stopped.await(1, TimeUnit.SECONDS))
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
