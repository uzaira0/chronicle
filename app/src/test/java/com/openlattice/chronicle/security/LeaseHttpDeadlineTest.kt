package com.openlattice.chronicle.security

import com.openlattice.chronicle.collection.state.ResearchPersistenceBarrier
import com.openlattice.chronicle.utils.Utils
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import android.content.Context
import androidx.work.*
import androidx.work.impl.utils.taskexecutor.WorkManagerTaskExecutor
import java.util.UUID
import java.util.concurrent.Executor
import java.lang.reflect.Proxy
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class LeaseHttpDeadlineTest {
    @Test fun tricklingTransportHasOverallDeadlineAndReleasesPrivacyLease() {
        val production = Utils.createRetrofitAdapter("https://localhost").callFactory() as OkHttpClient
        assertEquals(30_000, production.callTimeoutMillis)
        val entered = CountDownLatch(1)
        val barrier = ResearchPersistenceBarrier()
        val client = production.newBuilder().callTimeout(150, TimeUnit.MILLISECONDS).apply {
            interceptors().clear()
            addInterceptor { chain ->
                entered.countDown()
                while (!chain.call().isCanceled()) Thread.sleep(5)
                throw IOException("cancelled fake transport")
            }
        }.build()
        val pool = Executors.newFixedThreadPool(2)
        try {
            val call = pool.submit {
                barrier.withReadLease {
                    try { client.newCall(Request.Builder().url("https://localhost/upload").build()).execute().close() }
                    catch (_: IOException) { }
                }
            }
            assertTrue(entered.await(2, TimeUnit.SECONDS))
            val withdrawal = pool.submit { barrier.stop {} }
            withdrawal.get(2, TimeUnit.SECONDS)
            call.get(2, TimeUnit.SECONDS)
            val workEntered = CountDownLatch(1)
            val workerClient = production.newBuilder().callTimeout(20, TimeUnit.SECONDS).apply {
                interceptors().clear()
                addInterceptor(CallDeadline.interceptor)
                addInterceptor { chain ->
                    workEntered.countDown()
                    while (!chain.call().isCanceled()) Thread.sleep(5)
                    throw IOException("cancelled stopped work")
                }
            }.build()
            val worker = object : LeaseBoundWorker(androidx.test.core.app.ApplicationProvider.getApplicationContext(), workerParameters()) {
                override fun runWork(): Result {
                    barrier.withReadLease {
                        try { CallDeadline.within(20_000) {
                            workerClient.newCall(Request.Builder().url("https://localhost/upload").build()).execute().close()
                        } } catch (_: IOException) { }
                    }
                    return Result.success()
                }
            }
            val work = pool.submit { worker.doWork() }
            assertTrue(workEntered.await(2, TimeUnit.SECONDS))
            worker.onStopped()
            pool.submit { barrier.stop {} }.get(2, TimeUnit.SECONDS)
            work.get(2, TimeUnit.SECONDS)
        } finally { pool.shutdownNow() }
    }
    private fun workerParameters(): WorkerParameters {
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
}
