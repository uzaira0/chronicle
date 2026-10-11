package com.openlattice.chronicle.services.usage

import android.content.Context
import android.util.Log
import com.google.common.base.Stopwatch
import com.openlattice.chronicle.collection.CollectionModuleId
import com.openlattice.chronicle.collection.core.CollectionWindow
import com.openlattice.chronicle.collection.state.CollectionGate
import com.openlattice.chronicle.collection.state.ResearchPersistenceGate
import com.openlattice.chronicle.collection.sink.UsageEventSink
import com.openlattice.chronicle.collection.usage.DaoUsagePollCheckpointStore
import com.openlattice.chronicle.collection.usage.SystemUsageEventPoller
import com.openlattice.chronicle.collection.usage.UsageEventsCollectionModule
import com.openlattice.chronicle.collection.usage.UsageModulePersistence
import com.openlattice.chronicle.constants.TelemetryEvents
import com.openlattice.chronicle.data.ParticipationStatus
import com.openlattice.chronicle.sensors.UsageEventsChronicleSensor
import com.openlattice.chronicle.storage.nextQueueWriteTimestamp
import com.openlattice.chronicle.telemetry.LocalTelemetry
import java.util.TreeMap
import java.util.concurrent.TimeUnit

/**
 * The Phase 4B module-manager path for usage polling.
 *
 * This is the parity counterpart of [UsageCollectionDelegate]: `UsageMonitoringWorker`
 * routes here instead of to the legacy delegate when
 * [com.openlattice.chronicle.collection.usage.UsageWorkerMigration.USE_MODULE_MANAGER_USAGE_PATH]
 * is `true`. The switch defaults to `false`, so in production this class is dormant
 * until its parity tests pass — only one of the two paths runs per worker execution,
 * never both (no double-enqueue / double-write).
 *
 * What changes vs. [UsageCollectionDelegate]: the usage *poll step* runs through
 * [UsageEventsCollectionModule] (over the [com.openlattice.chronicle.collection.usage.UsageEventPoller]
 * seam) and the usage rows are persisted through the sanctioned [UsageEventSink].
 *
 * What is preserved **exactly**:
 *  - the WorkManager unique work name `"usage"` and 15-minute period (owned by
 *    [scheduleUsageMonitoringWork], untouched);
 *  - the two-timestamp poll window and the `usage_poll_checkpoints` cursor;
 *  - the original UsageStats event sequence and labels; supplemental device-state broadcasts are
 *    not synthesized into the poll result;
 *  - the single Room transaction wrapping the queue write **and** the checkpoint commit
 *    (a crash between them cannot advance the cursor past un-persisted rows);
 *  - the upload-queue-size update, the `users` lookup and lower-timestamp cleanup;
 *  - the `USAGE_START` / `USAGE_SUCCESS` / `USAGE_FAILURE` local events and the
 *    `UsageMonitoringWorker` TAG log lines (operational telemetry parity).
 *
 */
class UsageModuleCollectionDelegate(context: Context) : UsageCollectionDelegateBase(context) {
    private lateinit var usageModule: UsageEventsCollectionModule
    private lateinit var usageEventSink: UsageEventSink

    override fun monitorUsage(): Boolean {
        Log.i(TAG, "usage monitoring worker initialized")
        LocalTelemetry.logEvent(TelemetryEvents.USAGE_START, null)

        // only stop monitoring if data collection has been explicitly turned off
        val participationStatus = settings.getParticipationStatus()
        if (participationStatus != ParticipationStatus.ENROLLED) {
            Log.i(TAG, "Participant is not enrolled in active data collection (status = $participationStatus)")
            return true
        }

        // Collection loop closure gate: usage_events collects only when the server has
        // enabled it AND the participant has acknowledged it on-device.
        if (!CollectionGate.collects(context, CollectionModuleId.USAGE_EVENTS)) {
            Log.i(TAG, "usage_events not server-enabled/acknowledged yet; skipping collection")
            return true
        }

        if (propertyTypeIds.isEmpty()) {
            Log.w(TAG, "propertyTypeIds is empty — cannot collect usage data. Will retry.")
            return false
        }

        Log.d(
            javaClass.name,
            "Collecting Usage Information. Service $serviceId has been running for ${
                sw.elapsed(TimeUnit.SECONDS)
            } seconds."
        )

        val w = Stopwatch.createStarted()
        var collectActivityClass = false
        var collectUser = false
        val captured = ResearchPersistenceGate.withReadLease {
            val scope = ResearchPersistenceGate.observationScope(context, CollectionModuleId.USAGE_EVENTS)
                ?: return@withReadLease false
            usageEventSink = ResearchPersistenceGate.usageSink(context)
            collectActivityClass = ResearchPersistenceGate.collectsNow(context, CollectionModuleId.IN_APP_ACTIVITY_CLASS)
            collectUser = settings.isUserIdentificationEnabled() &&
                ResearchPersistenceGate.collectsNow(context, CollectionModuleId.USER_IDENTIFICATION)
            val checkpoints = DaoUsagePollCheckpointStore(chronicleDb.usagePollCheckpointDao(), scope.first, scope.second,
                com.openlattice.chronicle.collection.state.ResearchErasureFence(context).legacyCheckpointOwner())
            val initialStart = checkpoints.readOrRememberStart {
                maxOf(scope.second, UsageEventsChronicleSensor(context).previousPollTimestamp())
            }
            usageModule = UsageEventsCollectionModule(
                poller = SystemUsageEventPoller(context),
                checkpointStore = checkpoints,
                previousPollTimestampFallback = { initialStart },
            )
            true
        }
        if (!captured) return true
        val currentPollTimestamp = System.currentTimeMillis()
        // Participant labels are an optional field authorized by USER_IDENTIFICATION, not an
        // implicit part of usage_events. Never attach an old queue after that study scope or the
        // participant's local choice has closed.
        val userTimestamps = if (collectUser) {
            userStorageQueue.getUserTimestamps()
        } else {
            emptyList()
        }
        val users = userTimestamps.associateTo(TreeMap<Long, String>()) {
            it.writeTimestamp to it.user
        }

        // Usage poll runs through the collection module over the UsageEventPoller seam.
        // The module resolves the previous poll timestamp from the checkpoint cursor.
        val outcome = usageModule.pollWindow(
            users,
            CollectionWindow(startEpochMs = 0L, endEpochMs = currentPollTimestamp),
        ) ?: return true
        // Project using capture-time admission. The sink revalidates the optional fields'
        // owner, erasure epochs and observation floors during insertion.
        val gatedUsageEvents = gateActivityClass(outcome.events, collectActivityClass)
        // Keep the upstream combined UsageStats event sequence intact. Supplemental device-state
        // broadcasts have their own persistence path and are not synthesized into each poll.
        val queueEntry = gatedUsageEvents

        if (queueEntry.isEmpty()) {
            Log.i(TAG, "No sensors reported any data since last poll.")
            if (!persistUsageQueueAndCheckpoint(emptyList(), currentPollTimestamp)) return true
            users.clear() //Release references for GC
            return true
        }

        val queueEntries = buildUsageQueueEntries(
            queueEntry,
            chronicleDb.nextQueueWriteTimestamp(),
        ) { rand.nextLong() }
        if (!persistUsageQueueAndCheckpoint(queueEntries, currentPollTimestamp)) return true

        return finishUsageCollection(queueEntry, w, users, currentPollTimestamp, usageEventSink)
    }

    /**
     * Writes the queue entries through [UsageEventSink] and commits the usage-events
     * checkpoint — both inside one Room transaction, identical atomicity to the legacy
     * [UsageCollectionDelegate.persistUsageQueueAndCheckpoint]. The transactional
     * write+checkpoint logic is extracted into [UsageModulePersistence] so it is JVM-unit
     * testable; here it is wired to the real `ChronicleDb` transaction runner.
     */
    private fun persistUsageQueueAndCheckpoint(
        queueEntries: List<com.openlattice.chronicle.storage.QueueEntry>,
        currentPollTimestamp: Long,
    ): Boolean = UsageModulePersistence.persist(
            entries = queueEntries,
            currentPollTimestamp = currentPollTimestamp,
            sink = usageEventSink,
            commitCheckpoint = usageModule::commitCheckpoint,
            transaction = { body -> chronicleDb.runInTransaction(body) },
        )

}
