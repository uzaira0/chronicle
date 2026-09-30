package com.openlattice.chronicle.collection.state

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
public class ResearchPersistenceBarrier {
    private val lock = ReentrantReadWriteLock(true)

    /**
     * Holds a read lease across [block]. A caller that takes its own lock inside the lease keeps
     * the barrier-then-lock order that [stop] uses, so the two cannot deadlock. Nested
     * [persistIf] calls re-enter the read side even while a stop is queued.
     */
    public fun <T> withReadLease(block: () -> T): T = lock.read(block)

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

    public fun stop(stopAction: () -> Unit) {
        checkCanStop()
        lock.write(stopAction)
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

    public companion object {
        /** Test/legacy default; production app holders must inject the enrollment-aware guard. */
        public val ALLOW: CollectionPersistenceGuard = CollectionPersistenceGuard { persist ->
            persist()
            true
        }
    }
}

/** Keep origin validation and the complete write/acknowledgement in the same read lease. */
public fun CollectionPersistenceGuard.writeObservation(
    write: () -> com.openlattice.chronicle.collection.core.ModuleResult,
): com.openlattice.chronicle.collection.core.ModuleResult {
    var result: com.openlattice.chronicle.collection.core.ModuleResult? = null
    return when (persistResult { result = write() }) {
        CollectionPersistenceResult.PERSISTED -> checkNotNull(result)
        CollectionPersistenceResult.REFUSED -> com.openlattice.chronicle.collection.core.ModuleResult.Skipped("observation authorization retired")
        CollectionPersistenceResult.STORAGE_UNAVAILABLE -> com.openlattice.chronicle.collection.core.ModuleResult.Retry("local storage temporarily unavailable")
    }
}
