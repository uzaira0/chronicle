package com.openlattice.chronicle.collection.usage

import com.openlattice.chronicle.android.ChronicleData
import com.openlattice.chronicle.models.ExtractedUsageEvent
import java.util.NavigableMap

/**
 * In-memory fakes of the Phase 4 usage-module seams for JVM unit tests.
 *
 * Both seams are interfaces, so these fakes implement them directly — no mocking
 * framework is needed (and none is on the test classpath), matching the Phase 3
 * `FakeDaos` approach.
 */

/**
 * Fake [UsageEventPoller]. Records the `[previous, current)` window and `users` it was
 * called with, and returns a configurable [ChronicleData]. [failWith] forces the next
 * poll to throw, to exercise the `UsageStatsManager`-failure path.
 */
class FakeUsageEventPoller(
    var nextResult: ChronicleData = ChronicleData(emptyList()),
    var failWith: Exception? = null,
) : UsageEventPoller {

    var pollCount: Int = 0
        private set
    var lastPreviousPollTimestamp: Long? = null
        private set
    var lastCurrentPollTimestamp: Long? = null
        private set
    var lastUsers: NavigableMap<Long, String>? = null
        private set

    override fun poll(
        previousPollTimestamp: Long,
        currentPollTimestamp: Long,
        users: NavigableMap<Long, String>,
    ): ChronicleData {
        pollCount++
        lastPreviousPollTimestamp = previousPollTimestamp
        lastCurrentPollTimestamp = currentPollTimestamp
        lastUsers = users
        failWith?.let { throw it }
        return nextResult
    }

    companion object {
        /** A single-event [ChronicleData] with the given activity class, for parity tests. */
        fun oneEvent(activityClass: String?): ChronicleData = ChronicleData(
            listOf(
                ExtractedUsageEvent(
                    appPackageName = "com.example.app",
                    interactionType = "Activity Resumed",
                    timestamp = java.time.OffsetDateTime.parse("2026-05-20T00:00:00Z"),
                    timezone = "UTC",
                    user = "",
                    applicationLabel = "Example",
                    activityClass = activityClass,
                ),
            ),
        )
    }
}

/**
 * Fake [UsagePollCheckpointStore]. Holds the cursor in memory. [storedTimestamp] starts
 * `null` (no checkpoint row yet — the module then uses its fallback); [commitPollTimestamp]
 * updates it. [commitCount] lets tests assert the checkpoint did / did not advance.
 */
class FakeUsagePollCheckpointStore(
    var storedTimestamp: Long? = null,
) : UsagePollCheckpointStore {

    var commitCount: Int = 0
        private set

    override fun readPreviousPollTimestamp(): Long? = storedTimestamp

    override fun commitPollTimestamp(currentPollTimestamp: Long) {
        storedTimestamp = currentPollTimestamp
        commitCount++
    }
}
