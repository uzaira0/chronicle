package com.openlattice.chronicle.security

import android.content.Context
import android.util.Log
import android.os.Build
import androidx.work.WorkInfo
import androidx.work.Worker
import androidx.work.WorkerParameters

/** WorkManager stop cancels lease-held HTTP, including calls in nested privacy scopes. */
abstract class LeaseBoundWorker(context: Context, params: WorkerParameters) : Worker(context, params) {
    @Volatile private var cancelCalls: (() -> Unit)? = null

    final override fun doWork(): Result = CallDeadline.cancellable(WORK_CALL_DEADLINE_MS, { cancel ->
        cancelCalls = cancel
        if (isStopped) cancel()
    }) {
        try { runWork() } finally { cancelCalls = null }
    }

    protected abstract fun runWork(): Result

    final override fun onStopped() {
        val reason = if (Build.VERSION.SDK_INT >= 31) stopReason else WorkInfo.STOP_REASON_UNKNOWN
        Log.i("LeaseBoundWorker", "${javaClass.simpleName} stopped by WorkManager; reason=$reason")
        cancelCalls?.invoke()
        super.onStopped()
    }
}

private const val WORK_CALL_DEADLINE_MS = 120_000L
