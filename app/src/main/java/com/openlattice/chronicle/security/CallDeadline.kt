package com.openlattice.chronicle.security

import okhttp3.Call
import okhttp3.Interceptor
import java.io.InterruptedIOException
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * An overall deadline for the blocking calls the current thread makes inside [within]. The client
 * timeouts bound each socket operation, not a call, so a server trickling bytes could otherwise
 * hold the caller (and any lease it holds) without bound. At the deadline every call made in the
 * block is cancelled, which closes its socket, and later calls fail at once. Callers that never
 * enter [within] keep the client timeouts unchanged.
 */
internal class CallDeadline private constructor(private val parent: CallDeadline? = null) {
    private val calls = mutableListOf<Call>()
    private var expired = false

    private fun register(call: Call): Unit = synchronized(this) {
        if (expired) throw InterruptedIOException("Call deadline exceeded")
        calls += call
        parent?.register(call)
    }

    private fun expire() = synchronized(this) { expired = true; calls.toList() }.forEach(Call::cancel)

    companion object {
        private val current = ThreadLocal<CallDeadline?>()
        private val timer = Executors.newSingleThreadScheduledExecutor { Thread(it, "call-deadline").apply { isDaemon = true } }

        /** Application interceptor: synchronous calls run their interceptors on the calling thread. */
        val interceptor = Interceptor { chain ->
            current.get()?.register(chain.call())
            chain.proceed(chain.request())
        }

        fun <T> within(timeoutMs: Long, block: () -> T): T = cancellable(timeoutMs, {}, block)

        fun <T> cancellable(timeoutMs: Long, ready: (() -> Unit) -> Unit, block: () -> T): T {
            val deadline = CallDeadline(current.get())
            ready(deadline::expire)
            val expiry = timer.schedule(deadline::expire, timeoutMs, TimeUnit.MILLISECONDS)
            val previous = current.get()
            current.set(deadline)
            try {
                return block()
            } finally {
                expiry.cancel(false)
                current.set(previous)
            }
        }
    }
}
