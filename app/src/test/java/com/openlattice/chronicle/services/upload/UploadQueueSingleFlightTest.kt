package com.openlattice.chronicle.services.upload

import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class UploadQueueSingleFlightTest {
    @Test
    fun `same queue cannot be acquired twice until released`() {
        val owner = "test-${UUID.randomUUID()}"
        assertTrue(UploadQueueSingleFlight.tryAcquire(owner))
        try {
            assertFalse(UploadQueueSingleFlight.tryAcquire(owner))
        } finally {
            UploadQueueSingleFlight.release(owner)
        }
        assertTrue(UploadQueueSingleFlight.tryAcquire(owner))
        UploadQueueSingleFlight.release(owner)
    }

    @Test
    fun `different upload queues retain independent ownership`() {
        val prefix = UUID.randomUUID().toString()
        val first = "$prefix-first"
        val second = "$prefix-second"
        assertTrue(UploadQueueSingleFlight.tryAcquire(first))
        assertTrue(UploadQueueSingleFlight.tryAcquire(second))
        UploadQueueSingleFlight.release(first)
        UploadQueueSingleFlight.release(second)
    }

    @Test
    fun `exclusive mutation waits for every in flight upload`() {
        val owner = "test-${UUID.randomUUID()}"
        val mutationStarted = CountDownLatch(1)
        val mutationEntered = CountDownLatch(1)
        val executor = Executors.newSingleThreadExecutor()

        assertTrue(UploadQueueSingleFlight.tryAcquire(owner))
        try {
            val mutation = executor.submit {
                mutationStarted.countDown()
                UploadQueueSingleFlight.withExclusiveMutation {
                    mutationEntered.countDown()
                }
            }
            assertTrue(mutationStarted.await(5, TimeUnit.SECONDS))
            assertFalse(
                "Withdrawal/discard must not clear a queue while its uploader owns the read lease.",
                mutationEntered.await(100, TimeUnit.MILLISECONDS),
            )
            UploadQueueSingleFlight.release(owner)
            assertTrue(mutationEntered.await(5, TimeUnit.SECONDS))
            mutation.get(5, TimeUnit.SECONDS)
        } finally {
            UploadQueueSingleFlight.release(owner)
            executor.shutdownNow()
        }
    }

}
