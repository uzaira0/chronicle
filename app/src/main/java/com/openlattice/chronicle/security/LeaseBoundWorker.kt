package com.openlattice.chronicle.security

import android.content.Context
import androidx.work.Worker
import androidx.work.WorkerParameters

/** WorkManager stop cancels lease-held HTTP, including calls in nested privacy scopes. */
abstract class LeaseBoundWorker(context: Context, params: WorkerParameters) : Worker(context, params) {
    @Volatile private var cancelCalls: (() -> Unit)? = null

    final override fun doWork(): Result = CallDeadline.cancellable(30_000, { cancel ->
        cancelCalls = cancel
        if (isStopped) cancel()
    }) {
        try { runWork() } finally { cancelCalls = null }
    }

    protected abstract fun runWork(): Result

    final override fun onStopped() {
        cancelCalls?.invoke()
        super.onStopped()
    }
}
