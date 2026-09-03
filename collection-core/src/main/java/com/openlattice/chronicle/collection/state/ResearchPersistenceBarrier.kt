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

    public fun persistIf(allowed: () -> Boolean, persist: () -> Unit): Boolean = lock.read {
        if (!allowed()) return@read false
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
