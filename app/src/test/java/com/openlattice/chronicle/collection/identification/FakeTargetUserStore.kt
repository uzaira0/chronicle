package com.openlattice.chronicle.collection.identification

import com.openlattice.chronicle.storage.UserQueueEntry
import java.util.NavigableMap
import java.util.TreeMap

/**
 * In-memory [TargetUserStore] for JVM unit tests of [UserIdentificationCollectionModule].
 *
 * [TargetUserStore] is an interface, so this fake implements it directly — no mocking
 * framework is needed (and none is on the test classpath). It reproduces the dual-store
 * behaviour the module depends on: a `userQueue`-style list of [UserQueueEntry] rows and
 * a single `current_user` pref slot.
 *
 * [failNextInsert] forces the next [insertUserQueueEntry] to throw, to exercise the
 * module's failure path. [recordedUsers] exposes the raw labels written so a test can
 * assert what reached *storage* — diagnostics, by contrast, must never carry them.
 */
class FakeTargetUserStore : TargetUserStore {

    /** Every [UserQueueEntry] inserted, in insertion order. */
    val queueRows = mutableListOf<UserQueueEntry>()

    /** The `current_user` pref slot; `null` until first written. */
    var currentUserPref: String? = null
        private set

    /** When `true`, the next [insertUserQueueEntry] throws and resets the flag. */
    var failNextInsert = false

    /** The raw user labels handed to [insertUserQueueEntry], in order. */
    val recordedUsers: List<String> get() = queueRows.map { it.user }

    override fun insertUserQueueEntry(entry: UserQueueEntry) {
        if (failNextInsert) {
            failNextInsert = false
            throw RuntimeException("simulated userQueue write failure")
        }
        queueRows.add(entry)
    }

    override fun writeCurrentUserPref(user: String) {
        currentUserPref = user
    }

    override fun readCurrentUserPref(unassignedDefault: String): String =
        currentUserPref ?: unassignedDefault

    override fun userQueueDepth(): Int = queueRows.size

    override fun userTimestamps(): NavigableMap<Long, String> =
        queueRows.associateTo(TreeMap<Long, String>()) { it.writeTimestamp to it.user }
}
