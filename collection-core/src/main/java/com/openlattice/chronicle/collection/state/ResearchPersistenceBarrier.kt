package com.openlattice.chronicle.collection.state

import android.util.Log
import com.openlattice.chronicle.collection.core.ModuleResult
import java.util.concurrent.CancellationException
import java.util.concurrent.locks.ReentrantReadWriteLock
import kotlin.concurrent.read
import kotlin.concurrent.write

/**
 * Process-wide ordering primitive for research-data persistence and participant stop decisions.
 *
 * A collector owns the read side from its final policy check through the database write. A
 * withdrawal or another participant stop decision owns the write side while it durably closes the
 * gate. Consequently, once [stop] returns, every write that started under the old decision has
 * finished and every later write observes the closed policy.
 */
public class ResearchPersistenceBarrier(private val lock: ReentrantReadWriteLock = ReentrantReadWriteLock(true)) {
    private val cancellations = mutableSetOf<() -> Unit>()
    private var pendingStops = 0

    /**
     * Holds a read lease across [block]. A caller that takes its own lock inside the lease keeps
     * the barrier-then-lock order that [stop] uses, so the two cannot deadlock. Nested
     * [persistIf] calls re-enter the read side even while a stop is queued.
     */
    public fun <T> withReadLease(block: () -> T): T = lock.read(block)

    /** A queued stop closes network sockets before waiting for their read leases to drain. */
    public fun <T> withReadLease(cancel: () -> Unit, block: () -> T): T = lock.read {
        val stopping = synchronized(cancellations) {
            cancellations.add(cancel)
            pendingStops > 0
        }
        try {
            if (stopping) cancel()
            block()
        } finally { synchronized(cancellations) { cancellations.remove(cancel) } }
    }

    public fun persistIf(
        allowed: () -> Boolean,
        onRefused: () -> Unit = {},
        persist: () -> Unit,
    ): Boolean = lock.read {
        if (!allowed()) {
            onRefused()
            return@read false
        }
        persist()
        true
    }

    public fun checkCanStop() {
        check(lock.readHoldCount == 0 || lock.isWriteLockedByCurrentThread) {
            "Cannot stop research persistence while holding a read lease"
        }
    }

    /**
     * A routine stop waits for admitted read leases (and their uploads) to finish. Only a stop that
     * must keep data from leaving the device, [cancelReaders], closes their sockets first.
     */
    public fun stop(cancelReaders: Boolean = false, stopAction: () -> Unit) {
        checkCanStop()
        if (!cancelReaders) return lock.write(stopAction)
        val active = synchronized(cancellations) { pendingStops++; cancellations.toList() }
        try {
            active.forEach { it() }
            lock.write(stopAction)
        } finally { synchronized(cancellations) { pendingStops-- } }
    }
}

public enum class CollectionPersistenceResult { PERSISTED, REFUSED, STORAGE_UNAVAILABLE }

/** Wraps a sanctioned collection sink's actual database mutation in the active policy boundary. */
public fun interface CollectionPersistenceGuard {
    /** Returns false without invoking [persist] when research persistence is not currently allowed. */
    public fun persist(persist: () -> Unit): Boolean

    public fun persistResult(persist: () -> Unit): CollectionPersistenceResult =
        if (persist(persist)) CollectionPersistenceResult.PERSISTED else CollectionPersistenceResult.REFUSED

    /** Capture admission before acquiring an observation or registering a callback. */
    public fun capture(): CollectionPersistenceGuard = this

    /** False only when the captured authorization was erased or its enrollment retired. */
    public fun isCurrent(): Boolean = true

    /** Full validation on a persistence worker, inside its read lease. Exceptions retain the batch. */
    public fun validate(): Boolean = isCurrent()

    /**
     * Inside [persist]: the [rows] whose capture time ([capturedAt], phone epoch millis; null when unreadable, kept)
     * lies outside every capture window the status poll published. A row captured before a window was applied and
     * written after it is refused here, the write-time half of the rule that drops rows already queued inside one.
     */
    public fun <T> outsideCaptureWindows(rows: List<T>, capturedAt: (T) -> Long?): List<T> = rows

    public companion object {
        /** Test/legacy default; production app holders must inject the enrollment-aware guard. */
        public val ALLOW: CollectionPersistenceGuard = CollectionPersistenceGuard { persist ->
            persist()
            true
        }
    }
}

public const val SINK_PERSISTENCE_REFUSAL = "active enrollment persistence gate closed"

/**
 * Keep origin validation and the complete write/acknowledgement in the same read lease.
 * Collection boundary: any admission or write failure (SQLCipher, Room, Keystore, mapping) becomes one accounted
 * [ModuleResult.Failed], never an escaped exception that skips the module result.
 */
@Suppress("TooGenericExceptionCaught")
public fun CollectionPersistenceGuard.writeObservation(
    refusedReason: String = "observation authorization retired",
    write: () -> ModuleResult,
): ModuleResult = try {
    var result: ModuleResult? = null
    when (persistResult { result = write() }) {
        CollectionPersistenceResult.PERSISTED -> checkNotNull(result)
        CollectionPersistenceResult.REFUSED -> ModuleResult.Skipped(refusedReason)
        CollectionPersistenceResult.STORAGE_UNAVAILABLE -> ModuleResult.Retry(
            "local storage temporarily unavailable")
    }
} catch (error: CancellationException) {
    throw error
} catch (error: Exception) {
    val message = "observation persistence failed: ${error.javaClass.simpleName}"
    Log.e("CollectionPersistenceGuard", message)
    ModuleResult.Failed(error, redactedMessage = message)
}
