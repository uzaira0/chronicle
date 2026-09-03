package com.openlattice.chronicle.services.usage

import android.content.Context
import android.os.Bundle
import android.util.Log
import com.google.common.base.Stopwatch
import com.google.common.collect.ImmutableMap
import com.openlattice.chronicle.api.ChronicleStudyApi
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
import com.openlattice.chronicle.preferences.EnrollmentSettings
import com.openlattice.chronicle.sensors.PROPERTY_TYPE_IDS
import com.openlattice.chronicle.sensors.PROPERTY_TYPES
import com.openlattice.chronicle.sensors.UsageEventsChronicleSensor
import com.openlattice.chronicle.services.upload.UploadWorker
import com.openlattice.chronicle.storage.ChronicleDb
import com.openlattice.chronicle.storage.StorageQueue
import com.openlattice.chronicle.storage.UserStorageQueue
import com.openlattice.chronicle.telemetry.LocalTelemetry
import com.openlattice.chronicle.utils.Utils
import org.apache.olingo.commons.api.edm.FullQualifiedName
import java.security.SecureRandom
import java.util.TreeMap
import java.util.UUID
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
class UsageModuleCollectionDelegate(private val context: Context) {
    private val sw = Stopwatch.createStarted()
    private val rand = SecureRandom()
    private val serviceId = rand.nextLong()

    private lateinit var chronicleApi: ChronicleStudyApi

    private lateinit var propertyTypeIds: Map<FullQualifiedName, UUID>
    private lateinit var chronicleDb: ChronicleDb
    private lateinit var storageQueue: StorageQueue
    private lateinit var userStorageQueue: UserStorageQueue
    private lateinit var settings: EnrollmentSettings
    private lateinit var usageModule: UsageEventsCollectionModule
    private lateinit var usageEventSink: UsageEventSink

    /**
     * @return true if usage data was collected (or not needed), false if a retryable
     *   condition was hit — same contract as [UsageCollectionDelegate.execute].
     */
    fun execute(): Boolean {
        try {
            settings = EnrollmentSettings(context)
            chronicleDb = ChronicleDb.getInstance(context)
            chronicleDb.uploadServerDao().getEnabledServer()
                ?.takeIf { it.studyId == settings.getStudyId().toString() }
                ?.let { primary ->
                    chronicleApi = UploadWorker.getChronicleStudyApi(
                        primary.url,
                        primary.mobileSigningSecretOverride
                    )
                }
            propertyTypeIds = getPropertyTypeIds()
            storageQueue = chronicleDb.queueEntryData()
            userStorageQueue = chronicleDb.userQueueEntryData()
            usageEventSink = UsageEventSink(
                storageQueue,
                persistenceGuard = ResearchPersistenceGate.guard(context, CollectionModuleId.USAGE_EVENTS),
            )
            usageModule = UsageEventsCollectionModule(
                poller = SystemUsageEventPoller(context),
                checkpointStore = DaoUsagePollCheckpointStore(chronicleDb.usagePollCheckpointDao()),
                // Identical fallback ordering to the legacy path:
                // checkpoint row, else the sensor's encrypted-prefs default.
                previousPollTimestampFallback = { UsageEventsChronicleSensor(context).previousPollTimestamp() },
            )
            val collected = monitorUsage()
            if (!collected) {
                return false
            }
        } catch (e: Exception) {
            LocalTelemetry.recordException(e)
            if (::settings.isInitialized) {
                Log.i(TAG, "usage monitoring worker failed for enrolled device", e)
            }
            Log.i(TAG, "usage monitoring worker failed with an exception", e)
            LocalTelemetry.logEvent(TelemetryEvents.USAGE_FAILURE, null)
            throw e
        }
        return true
    }

    private fun monitorUsage(): Boolean {
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
        val currentPollTimestamp = System.currentTimeMillis()
        // Participant labels are an optional field authorized by USER_IDENTIFICATION, not an
        // implicit part of usage_events. Never attach an old queue after that study scope or the
        // participant's local choice has closed.
        val userTimestamps = if (
            settings.isUserIdentificationEnabled() &&
            CollectionGate.collects(context, CollectionModuleId.USER_IDENTIFICATION)
        ) {
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
        )
        // in_app_activity_class field gate: strip the within-app Activity/screen class unless the
        // participant has accepted that (opt-in) module; package-level usage is unaffected.
        val collectActivityClass = CollectionGate.collects(context, CollectionModuleId.IN_APP_ACTIVITY_CLASS)
        val gatedUsageEvents = gateActivityClass(outcome.events, collectActivityClass)
        // Keep the upstream combined UsageStats event sequence intact. Supplemental device-state
        // broadcasts have their own persistence path and are not synthesized into each poll.
        val queueEntry = gatedUsageEvents

        if (queueEntry.isEmpty()) {
            Log.i(TAG, "No sensors reported any data since last poll.")
            persistUsageQueueAndCheckpoint(emptyList(), currentPollTimestamp)
            users.clear() //Release references for GC
            return true
        }

        val queueEntries = buildUsageQueueEntries(queueEntry, System.currentTimeMillis()) { rand.nextLong() }
        persistUsageQueueAndCheckpoint(queueEntries, currentPollTimestamp)

        queueEntry.asSequence().chunked(1000).forEach { chunk ->
            Log.d(
                javaClass.name,
                "Persisting ${chunk.size} usage information elements took ${w.elapsed(TimeUnit.MILLISECONDS)} millis."
            )
            LocalTelemetry.logEvent(TelemetryEvents.USAGE_SUCCESS, Bundle().apply {
                putInt("size", chunk.size)
            })
        }

        // Update "items remaining" only after successfully persisting collected data.
        Utils.updateUploadQueueSize(context, storageQueue.getSize())

        val lowestTimestamp = users.lowerEntry(currentPollTimestamp)?.key
        lowestTimestamp?.let {
            userStorageQueue.deleteEntriesWithLowerTimestamp(currentPollTimestamp)
        }
        users.clear() //Release references for GC

        return true
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
    ) {
        UsageModulePersistence.persist(
            entries = queueEntries,
            currentPollTimestamp = currentPollTimestamp,
            sink = usageEventSink,
            commitCheckpoint = usageModule::commitCheckpoint,
            transaction = { body -> chronicleDb.runInTransaction(body) },
        )
    }

    private fun getPropertyTypeIds(): Map<FullQualifiedName, UUID> {
        var propertyTypeIds = settings.getPropertyTypeIds()
        if (propertyTypeIds.size != PROPERTY_TYPES.size) {
            Log.i(javaClass.name, "Loading BCM property types cache")
            propertyTypeIds = PROPERTY_TYPE_IDS
                .filterKeys { it in PROPERTY_TYPES }
                .ifEmpty {
                    if (::chronicleApi.isInitialized) {
                        chronicleApi.getPropertyTypeIds(PROPERTY_TYPES) ?: ImmutableMap.of()
                    } else {
                        ImmutableMap.of()
                    }
                }
            settings.setPropertyTypeIds(propertyTypeIds)
        }
        return propertyTypeIds
    }
}
