package com.openlattice.chronicle.audit

import android.content.Context
import androidx.work.*
import androidx.work.impl.utils.taskexecutor.WorkManagerTaskExecutor
import java.lang.reflect.Proxy
import java.util.UUID
import java.util.concurrent.Executor

    fun auditWorkerParameters(): WorkerParameters {
        val executor = Executor { }
        val progress = Proxy.newProxyInstance(ProgressUpdater::class.java.classLoader, arrayOf(ProgressUpdater::class.java)) { _, _, _ ->
            error("no progress expected")
        } as ProgressUpdater
        val foreground = Proxy.newProxyInstance(ForegroundUpdater::class.java.classLoader, arrayOf(ForegroundUpdater::class.java)) { _, _, _ ->
            error("no foreground expected")
        } as ForegroundUpdater
        val factory = object : WorkerFactory() {
            override fun createWorker(appContext: Context, workerClassName: String, workerParameters: WorkerParameters): ListenableWorker? = null
        }
        return WorkerParameters(UUID.randomUUID(), Data.EMPTY, emptyList(), WorkerParameters.RuntimeExtras(), 0, 0,
            executor, kotlin.coroutines.EmptyCoroutineContext, WorkManagerTaskExecutor(executor), factory, progress, foreground)
    }