package com.openlattice.chronicle.collection.lifecycle

import com.openlattice.chronicle.models.ExtractedUsageEvent

/**
 * In-memory fakes of the Phase 5 lifecycle-module seams for JVM unit tests.
 *
 * The seams are interfaces, so these fakes implement them directly — no mocking
 * framework is needed (and none is on the test classpath), matching the Phase 3/4
 * `FakeDaos` / `FakeUsageCollaborators` approach.
 */

/**
 * Fake [LifecycleDedupeStore] that reproduces the production 2-second dedupe semantics
 * in memory: a repeat of the same `(interactionType, activityClass)` event within
 * [LIFECYCLE_DEDUPE_WINDOW_MS] of the last accepted occurrence is dropped.
 *
 * Because tests drive [com.openlattice.chronicle.collection.core.FixedCollectionClock]
 * for `now`, this faithfully exercises the dedupe window without `SharedPreferences`.
 */
class FakeLifecycleDedupeStore : LifecycleDedupeStore {

    private val lastSeen = HashMap<String, Long>()

    /** Total number of events dropped as duplicates. */
    var droppedCount: Int = 0
        private set

    override fun shouldPersist(event: ExtractedUsageEvent, now: Long): Boolean {
        val key = "last:${event.interactionType}:${event.activityClass ?: ""}"
        val last = lastSeen[key]
        if (last != null && now - last < LIFECYCLE_DEDUPE_WINDOW_MS) {
            droppedCount++
            return false
        }
        lastSeen[key] = now
        return true
    }
}

/**
 * Fake [LifecycleDedupeStore] that always accepts every event — useful for tests that
 * are not exercising the dedupe window and want every event persisted.
 */
class AlwaysAcceptLifecycleDedupeStore : LifecycleDedupeStore {
    override fun shouldPersist(event: ExtractedUsageEvent, now: Long): Boolean = true
}
