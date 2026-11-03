package com.openlattice.chronicle.security

import okhttp3.Call
import okhttp3.Interceptor
import com.openlattice.chronicle.services.upload.CollectedUploadBody
import java.io.InterruptedIOException
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.WeakHashMap

/**
 * An overall deadline for the blocking calls the current thread makes inside [within]. The client
 * timeouts bound each socket operation, not a call, so a server trickling bytes could otherwise
 * hold the caller (and any lease it holds) without bound. At the deadline registered calls are
 * cancelled, which closes their sockets, and later registrations fail at once. Collected bodies
 * retain idle I/O timeouts; explicit cancellation still closes them on worker or privacy stop.
 */
internal class CallDeadline private constructor(private val parent: CallDeadline? = null) {
    // OkHttp owns active calls; completed 64 MiB bodies must not be retained for the worker's lifetime.
    private val calls = WeakHashMap<Call, Boolean>()
    private var expired = false
    private var cancelled = false

    private fun register(call: Call): Unit = synchronized(this) {
        val collected = call.request().body is CollectedUploadBody<*>
        if (cancelled || expired && !collected) throw InterruptedIOException("Call scope closed")
        calls[call] = collected
        parent?.register(call)
    }

    private fun expire() = synchronized(this) {
        expired = true
        calls.filterValues { !it }.keys.toList()
    }.forEach(Call::cancel)

    private fun cancel() = synchronized(this) {
        cancelled = true
        calls.keys.toList()
    }.forEach(Call::cancel)

    companion object {
        const val INTERACTIVE_TIMEOUT_MS = 30_000L
        private val current = ThreadLocal<CallDeadline?>()
        private val timer = Executors.newSingleThreadScheduledExecutor { Thread(it, "call-deadline").apply { isDaemon = true } }

        /** Application interceptor: synchronous calls run their interceptors on the calling thread. */
        val interceptor = Interceptor { chain ->
            current.get()?.register(chain.call())
            chain.proceed(chain.request())
        }

        fun <T> within(timeoutMs: Long, block: () -> T): T = cancellable(timeoutMs, {}, block)

        fun <T> cancellable(timeoutMs: Long?, ready: (() -> Unit) -> Unit, block: () -> T): T {
            val deadline = CallDeadline(current.get())
            ready(deadline::cancel)
            val expiry = timeoutMs?.let { timer.schedule(deadline::expire, it, TimeUnit.MILLISECONDS) }
            val previous = current.get()
            current.set(deadline)
            try {
                return block()
            } finally {
                expiry?.cancel(false)
                current.set(previous)
            }
        }
    }
}
