package com.openlattice.chronicle

import android.util.Log
import android.content.Context
import android.annotation.SuppressLint
import androidx.annotation.VisibleForTesting
import androidx.work.impl.WorkManagerImpl
import androidx.work.impl.utils.ForceStopRunnable
import androidx.work.impl.WorkDatabasePathHelper
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.atomic.AtomicReference

/** Initialization failures retry locally; only successful scheduling clears the failure hint. */
internal object WorkSchedulingStatus {
    @Volatile var unavailable: Boolean = false
        private set

    private val retries = Executors.newSingleThreadScheduledExecutor { task ->
        Thread(task, "work-scheduling-retry").apply { isDaemon = true }
    }
    private var retry: ScheduledFuture<*>? = null
    private var attempt = 0
    @Volatile private var startupRecoveryRequired = false

    @Synchronized
    fun initializationFailed(error: Throwable, context: Context? = null, reconcileStartup: Boolean = false) {
        unavailable = true
        if (reconcileStartup) startupRecoveryRequired = true
        Log.e("WorkSchedulingStatus", "Work scheduling unavailable; retained work will retry after storage recovery", error)
        if (context == null || retry != null) return
        val delay = (250L shl attempt.coerceAtMost(7)).coerceAtMost(30_000L)
        attempt++
        retry = retries.schedule({
            synchronized(this) { retry = null }
            try {
                if (startupRecoveryRequired) reconcileStartup(context.applicationContext)
                EnrolledWork.rescheduleSync(context.applicationContext)
            } catch (error: Exception) {
                initializationFailed(error, context)
            }
        }, delay, TimeUnit.MILLISECONDS)
    }

    @SuppressLint("RestrictedApi")
    private fun reconcileStartup(context: Context) {
        val wm = WorkManagerImpl.getInstance(context)
        // CountDownLatch, not CompletableFuture: minSdk 23 has no CompletableFuture.
        val done = CountDownLatch(1)
        val failure = AtomicReference<Exception>()
        // Use WorkManager's serial executor and its startup reconciliation, including RUNNING
        // work and stale progress. UPDATE alone deliberately preserves a running periodic row.
        wm.workTaskExecutor.executeOnTaskThread {
            try {
                WorkDatabasePathHelper.migrateDatabase(context)
                ForceStopRunnable(context, wm).forceStopRunnable()
                startupRecoveryRequired = false
            } catch (error: Exception) {
                failure.set(error)
            } finally {
                done.countDown()
            }
        }
        done.await()
        failure.get()?.let { throw it }
    }

    @Synchronized
    fun schedulingSucceeded() {
        if (startupRecoveryRequired) return
        unavailable = false
        attempt = 0
        retry?.cancel(false)
        retry = null
    }

    /** Test isolation: drops a pending retry, waits out a running one, then forgets every failure. */
    @VisibleForTesting
    fun reset() {
        synchronized(this) { retry?.cancel(false) }
        // Single-thread executor: this no-op runs only after an in-flight retry has returned.
        retries.submit {}.get(10, TimeUnit.SECONDS)
        synchronized(this) {
            retry?.cancel(false)
            retry = null
            attempt = 0
            unavailable = false
            startupRecoveryRequired = false
        }
    }
}
