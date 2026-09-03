package com.openlattice.chronicle.services.usage

import android.content.Context
import android.os.Bundle
import android.util.Log
import androidx.work.*
import com.google.common.base.Stopwatch
import com.google.common.collect.ImmutableMap
import com.openlattice.chronicle.android.ChronicleData
import com.openlattice.chronicle.android.ChronicleSample
import com.openlattice.chronicle.api.ChronicleStudyApi
import com.openlattice.chronicle.collection.CollectionModuleId
import com.openlattice.chronicle.collection.state.CollectionGate
import com.openlattice.chronicle.collection.state.ResearchPersistenceGate
import com.openlattice.chronicle.collection.usage.UsageWorkerMigration
import com.openlattice.chronicle.models.ExtractedUsageEvent
import com.openlattice.chronicle.constants.TelemetryEvents
import com.openlattice.chronicle.data.ParticipationStatus
import com.openlattice.chronicle.preferences.EnrollmentSettings
import com.openlattice.chronicle.sensors.ChronicleSensor
import com.openlattice.chronicle.sensors.PROPERTY_TYPE_IDS
import com.openlattice.chronicle.sensors.PROPERTY_TYPES
import com.openlattice.chronicle.sensors.UsageEventsChronicleSensor
import com.openlattice.chronicle.sensors.USAGE_EVENTS_SENSOR_CHECKPOINT
import com.openlattice.chronicle.serialization.JsonSerializer
import com.openlattice.chronicle.services.upload.UploadWorker
import com.openlattice.chronicle.storage.ChronicleDb
import com.openlattice.chronicle.storage.QueueEntry
import com.openlattice.chronicle.storage.StorageQueue
import com.openlattice.chronicle.storage.UsagePollCheckpointEntity
import com.openlattice.chronicle.storage.UserStorageQueue
import com.openlattice.chronicle.telemetry.LocalTelemetry
import com.openlattice.chronicle.utils.Utils
import org.apache.olingo.commons.api.edm.FullQualifiedName
import java.security.SecureRandom
import java.util.*
import java.util.concurrent.TimeUnit

val TAG = UsageMonitoringWorker::class.java.simpleName
const val USAGE_WORK_NAME = "usage"

/**
 * Which usage-collection delegate runs, per the Phase 4B migration switch
 * [UsageWorkerMigration.USE_MODULE_MANAGER_USAGE_PATH].
 *
 * [MODULE_GATED] is the module-manager path ([UsageModuleCollectionDelegate]) — it enforces the
 * collection-loop acknowledgment gate (`CollectionGate.collects(USAGE_EVENTS)`) before persisting.
 * [LEGACY_UNGATED] is the legacy path ([UsageCollectionDelegate]), which has **no** gate.
 */
internal enum class UsageCollectionPath { MODULE_GATED, LEGACY_UNGATED }

/** The single delegate-selection decision, shared by every usage-collection caller. */
internal fun selectedUsageCollectionPath(): UsageCollectionPath =
    if (UsageWorkerMigration.USE_MODULE_MANAGER_USAGE_PATH) {
        UsageCollectionPath.MODULE_GATED
    } else {
        UsageCollectionPath.LEGACY_UNGATED
    }

/**
 * Runs usage collection through the migration-selected delegate. This is the **single** place a
 * usage delegate is constructed, so the acknowledgment gate is enforced identically on every sync
 * strategy. Both the split-periodic [UsageMonitoringWorker] and the coordinated
 * [com.openlattice.chronicle.services.sync.ChronicleSyncWorker] route through here.
 *
 * Before this seam existed, `ChronicleSyncWorker` hardcoded the legacy ungated delegate, so the
 * coordinated strategy (the device's production strategy) collected + uploaded usage_events
 * before the participant acknowledged the module — bypassing the gate the periodic path enforced.
 *
 * @return true if usage data was collected (or not needed), false on a retryable condition.
 */
internal fun collectUsage(context: Context): Boolean =
    when (selectedUsageCollectionPath()) {
        UsageCollectionPath.MODULE_GATED -> UsageModuleCollectionDelegate(context).execute()
        UsageCollectionPath.LEGACY_UNGATED -> UsageCollectionDelegate(context).execute()
    }

class UsageMonitoringWorker(context: Context, workerParameters: WorkerParameters) :
    Worker(context, workerParameters) {
    override fun doWork(): Result {
        return try {
            // Exactly one collection path runs per execution, selected by the Phase 4B migration
            // switch (UsageWorkerMigration.USE_MODULE_MANAGER_USAGE_PATH) — see collectUsage.
            val collected = collectUsage(applicationContext)
            if (collected) Result.success() else Result.retry()
        } catch (e: Exception) {
            Log.i(TAG, "usage monitoring worker failed with an exception", e)
            Result.failure()
        }
    }
}

class UsageCollectionDelegate(private val context: Context) {
    private val sw = Stopwatch.createStarted()
    private val rand = SecureRandom()
    private val serviceId = rand.nextLong()

    private lateinit var chronicleApi: ChronicleStudyApi

    private lateinit var propertyTypeIds: Map<FullQualifiedName, UUID>
    private lateinit var chronicleDb: ChronicleDb
    private lateinit var storageQueue: StorageQueue
    private lateinit var userStorageQueue: UserStorageQueue
    private lateinit var sensors: Set<ChronicleSensor>
    private lateinit var settings: EnrollmentSettings

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
            sensors = mutableSetOf(
                UsageEventsChronicleSensor(context)
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

    /**
     * @return true if usage data was collected (or not needed), false if a retryable condition was hit
     */
    private fun monitorUsage(): Boolean {

        Log.i(TAG, "usage monitoring worker initialized")
        LocalTelemetry.logEvent(TelemetryEvents.USAGE_START, null)

        // only stop monitoring if data collection has been explicitly turned off
        val participationStatus = settings.getParticipationStatus()
        if (participationStatus != ParticipationStatus.ENROLLED) {
            Log.i(TAG, "Participant is not enrolled in active data collection (status = $participationStatus)")
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
        // Legacy independently schedulable collection must obey the same optional-field scope as
        // the module delegate. A stale local identify_user preference is never study authority.
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
        val usageEvents = sensors.flatMap { sensor ->
            when (sensor) {
                is UsageEventsChronicleSensor -> {
                    val previousPollTimestamp = chronicleDb.usagePollCheckpointDao()
                        .getLastPollTimestamp(USAGE_EVENTS_SENSOR_CHECKPOINT)
                        ?: sensor.previousPollTimestamp()
                    sensor.poll(previousPollTimestamp, currentPollTimestamp, users)
                }
                else -> sensor.poll(currentPollTimestamp, users)
            }
        }
        // in_app_activity_class field gate: strip the within-app Activity/screen class unless the
        // participant has accepted that (opt-in) module; package-level usage is unaffected.
        val collectActivityClass = CollectionGate.collects(context, CollectionModuleId.IN_APP_ACTIVITY_CLASS)
        val gatedUsageEvents = gateActivityClass(usageEvents, collectActivityClass)
        // Preserve the original researcher-facing stream: UsageStats owns its screen, keyguard,
        // startup, and shutdown rows. Supplemental device-state broadcasts are persisted by their
        // own collector and must not be injected into every usage poll.
        val queueEntry = gatedUsageEvents

        if (queueEntry.isEmpty()) {
            Log.i(TAG, "No sensors reported any data since last poll.")
            persistUsageQueueAndCheckpoint(emptyList(), currentPollTimestamp)
            users.clear() //Release references for GC
            return true
        }

        val queueEntries = buildUsageQueueEntries(queueEntry, System.currentTimeMillis()) { rand.nextLong() }
        if (!persistUsageQueueAndCheckpoint(queueEntries, currentPollTimestamp)) {
            users.clear()
            return true
        }

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

        // currentPollTimestamp will be the begjnTime of UsageStatsManager.queryEvents() call in the next sensor poll
        // We can therefore delete entries whose timestamp is less than the largest timestamp greater than currentPollTimestamp
        // Therefore we can clear out user entries that have a lower timestamp
        val lowestTimestamp = users.lowerEntry(currentPollTimestamp)?.key
        lowestTimestamp?.let {
            userStorageQueue.deleteEntriesWithLowerTimestamp(currentPollTimestamp)
        }
        users.clear() //Release references for GC

        return true
    }

    private fun persistUsageQueueAndCheckpoint(
        queueEntries: List<QueueEntry>,
        currentPollTimestamp: Long,
    ): Boolean = ResearchPersistenceGate.persistIfCollecting(
        context,
        CollectionModuleId.USAGE_EVENTS,
    ) {
        chronicleDb.runInTransaction {
            if (queueEntries.isNotEmpty()) {
                storageQueue.insertEntries(queueEntries)
            }
            chronicleDb.usagePollCheckpointDao().upsert(
                UsagePollCheckpointEntity(USAGE_EVENTS_SENSOR_CHECKPOINT, currentPollTimestamp)
            )
        }
    }

    private fun getPropertyTypeIds(): Map<FullQualifiedName, UUID> {
        // try retrieving cached values first
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

/**
 * Applies the `in_app_activity_class` field gate to a freshly-polled batch of usage samples.
 *
 * The within-app Activity/screen class (`ExtractedUsageEvent.activityClass`, the
 * `UsageStatsManager` `className`) rides every usage event at the same per-transition resolution
 * as the app-usage log. It is split out into its own opt-in module: when
 * [CollectionModuleId.IN_APP_ACTIVITY_CLASS] is not collected, the class is stripped **on-device**
 * (the finer signal never leaves the phone) while package-level usage is preserved. Non-usage
 * samples (e.g. device-state rows) pass through untouched.
 *
 * Pure + side-effect-free so it is JVM-unit-testable; the gate decision is computed by the caller
 * (which holds the [android.content.Context]) and passed in as [collectActivityClass].
 */
internal fun gateActivityClass(
    events: List<ChronicleSample>,
    collectActivityClass: Boolean,
): List<ChronicleSample> =
    if (collectActivityClass) {
        events
    } else {
        events.map { if (it is ExtractedUsageEvent) it.copy(activityClass = null) else it }
    }

internal fun buildUsageQueueEntries(
    queueEntry: List<com.openlattice.chronicle.android.ChronicleSample>,
    firstWriteTimestamp: Long,
    nextId: () -> Long
): List<QueueEntry> {
    var writeTimestamp = firstWriteTimestamp
    return queueEntry.chunked(1000).map { chunk ->
        QueueEntry(
            writeTimestamp++,
            nextId(),
            JsonSerializer.serializeQueueEntry(ChronicleData(chunk))
        )
    }
}

fun scheduleUsageMonitoringWork(context: Context) {

    val workRequest: PeriodicWorkRequest =
        PeriodicWorkRequestBuilder<UsageMonitoringWorker>(15, TimeUnit.MINUTES)
            .build()

    WorkManager.getInstance(context).enqueueUniquePeriodicWork(
        USAGE_WORK_NAME,
        ExistingPeriodicWorkPolicy.UPDATE,
        workRequest
    )
}
