package com.openlattice.chronicle.services.upload

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.locks.ReentrantReadWriteLock
import kotlin.concurrent.write

/**
 * Process-wide ownership for upload queues that have both periodic and one-time WorkManager jobs.
 *
 * A periodic work item never reaches a terminal state, so its unique-work name cannot also be used
 * by a one-time job with [androidx.work.ExistingWorkPolicy.KEEP]: the one-time job would always be
 * suppressed. The two schedules therefore retain distinct WorkManager names and acquire the same
 * queue owner here before reading/deleting rows. Chronicle's workers run in the app process, making
 * this sufficient to prevent the periodic and manual instances from uploading the same batch.
 */
internal object UploadQueueSingleFlight {
    private val activeOwners = ConcurrentHashMap.newKeySet<String>()
    private val mutationLock = ReentrantReadWriteLock(true)

    fun tryAcquire(owner: String): Boolean {
        if (!activeOwners.add(owner)) return false
        mutationLock.readLock().lock()
        return true
    }

    fun release(owner: String) {
        if (activeOwners.remove(owner)) mutationLock.readLock().unlock()
    }

    /** Serializes participant discard/withdrawal mutations against every in-flight upload. */
    fun <T> withExclusiveMutation(mutation: () -> T): T = mutationLock.write(mutation)
}
