package com.openlattice.chronicle.collection.core

/**
 * Time source abstraction for the data collection core (design §1C, refactor plan §6.1 step 14).
 *
 * Modules, sinks and the resolver never call [System.currentTimeMillis] directly: they
 * read the current epoch-millis through a [CollectionClock] so tests can supply a
 * deterministic, fixed clock without touching real wall-clock time.
 *
 * Phase 3 is purely additive — this abstraction is wired into the new collection core
 * only; no existing collection code is changed.
 *
 */
public interface CollectionClock {
    /** Current time as epoch milliseconds. */
    public fun nowEpochMs(): Long

    public companion object {
        /** Production clock backed by [System.currentTimeMillis]. */
        public val SYSTEM: CollectionClock = SystemCollectionClock
    }
}

/**
 * Production [CollectionClock] backed by the system wall clock.
 *
 * It holds no Android [android.content.Context] and no mutable state, so it is safe to
 * expose as a singleton object.
 */
public object SystemCollectionClock : CollectionClock {
    override fun nowEpochMs(): Long = System.currentTimeMillis()
}

/**
 * Deterministic [CollectionClock] for tests. The returned time can be set explicitly
 * and advanced; it never reads the real clock.
 */
public class FixedCollectionClock(private var current: Long = 0L) : CollectionClock {
    override fun nowEpochMs(): Long = current

    /** Sets the clock to an absolute epoch-millis value. */
    public fun set(epochMs: Long) {
        current = epochMs
    }

    /** Advances the clock by [deltaMs] milliseconds. */
    public fun advance(deltaMs: Long) {
        current += deltaMs
    }
}
