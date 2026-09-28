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

    public fun stop(stopAction: () -> Unit) {
        lock.write(stopAction)
    }
}

/** Wraps a sanctioned collection sink's actual database mutation in the active policy boundary. */
public fun interface CollectionPersistenceGuard {
    /** Returns false without invoking [persist] when research persistence is not currently allowed. */
    public fun persist(persist: () -> Unit): Boolean

    public companion object {
        /** Test/legacy default; production app holders must inject the enrollment-aware guard. */
        public val ALLOW: CollectionPersistenceGuard = CollectionPersistenceGuard { persist ->
            persist()
            true
        }
    }
}
