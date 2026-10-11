package com.openlattice.chronicle.services.usage

import android.content.Context
import android.util.Log
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequest
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.google.common.base.Stopwatch
import com.openlattice.chronicle.android.ChronicleData
import com.openlattice.chronicle.android.ChronicleSample
import com.openlattice.chronicle.collection.CollectionModuleId
import com.openlattice.chronicle.collection.state.CollectionGate
import com.openlattice.chronicle.collection.state.ResearchPersistenceGate
import com.openlattice.chronicle.collection.usage.UsageWorkerMigration
import com.openlattice.chronicle.models.ExtractedUsageEvent
import com.openlattice.chronicle.constants.TelemetryEvents
import com.openlattice.chronicle.data.ParticipationStatus
import com.openlattice.chronicle.hasUsageSettingPermission
import com.openlattice.chronicle.sensors.UsageEventsChronicleSensor
import com.openlattice.chronicle.serialization.JsonSerializer
import com.openlattice.chronicle.storage.nextQueueWriteTimestamp
import com.openlattice.chronicle.storage.QueueEntry
import com.openlattice.chronicle.telemetry.LocalTelemetry
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
internal fun collectUsage(context: Context): Boolean {
    // Without Usage Access, queryEvents returns no events. Committing that empty poll moves the
    // cursor past events Android still holds, so they are never read after access returns.
    if (!hasUsageSettingPermission(context)) {
        Log.i(TAG, "Usage Access is off; usage poll skipped, cursor kept")
        return true
    }
    return when (selectedUsageCollectionPath()) {
        UsageCollectionPath.MODULE_GATED -> UsageModuleCollectionDelegate(context).execute()
        UsageCollectionPath.LEGACY_UNGATED -> UsageCollectionDelegate(context).execute()
    }
}

class UsageMonitoringWorker(context: Context, workerParameters: WorkerParameters) :
    com.openlattice.chronicle.security.LeaseBoundWorker(context, workerParameters) {
    override fun runWork(): Result {
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

class UsageCollectionDelegate(context: Context) : UsageCollectionDelegateBase(context) {
    private lateinit var observationSink: com.openlattice.chronicle.collection.sink.UsageEventSink
    private lateinit var observationCheckpoint: com.openlattice.chronicle.collection.usage.DaoUsagePollCheckpointStore
    /**
     * @return true if usage data was collected (or not needed), false if a retryable condition was hit
     */
    override fun monitorUsage(): Boolean {
        val sensors = setOf(UsageEventsChronicleSensor(context))

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
        val scope = ResearchPersistenceGate.withReadLease {
            val currentScope = ResearchPersistenceGate.observationScope(context, CollectionModuleId.USAGE_EVENTS)
                ?: return@withReadLease null
            observationSink = ResearchPersistenceGate.usageSink(context)
            observationCheckpoint = com.openlattice.chronicle.collection.usage.DaoUsagePollCheckpointStore(
                chronicleDb.usagePollCheckpointDao(), currentScope.first, currentScope.second,
                    com.openlattice.chronicle.collection.state.ResearchErasureFence(context).legacyCheckpointOwner())
            currentScope
        } ?: return true
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
            val previousPollTimestamp = observationCheckpoint.readPreviousPollTimestamp()
                ?: maxOf(scope.second, sensor.previousPollTimestamp())
            sensor.poll(previousPollTimestamp, currentPollTimestamp, users) ?: return true
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

        val queueEntries = buildUsageQueueEntries(
            queueEntry,
            chronicleDb.nextQueueWriteTimestamp(),
        ) { rand.nextLong() }
        if (!persistUsageQueueAndCheckpoint(queueEntries, currentPollTimestamp)) {
            users.clear()
            return true
        }

        return finishUsageCollection(queueEntry, w, users, currentPollTimestamp, observationSink)
    }

    private fun persistUsageQueueAndCheckpoint(
        queueEntries: List<QueueEntry>,
        currentPollTimestamp: Long,
    ): Boolean = com.openlattice.chronicle.collection.usage.UsageModulePersistence.persist(
        queueEntries, currentPollTimestamp, observationSink, observationCheckpoint::commitPollTimestamp,
        { block -> chronicleDb.runInTransaction(block) },
    )

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

fun scheduleUsageMonitoringWork(context: Context): androidx.work.Operation {

    val workRequest: PeriodicWorkRequest =
        PeriodicWorkRequestBuilder<UsageMonitoringWorker>(15, TimeUnit.MINUTES)
            .build()

    return WorkManager.getInstance(context).enqueueUniquePeriodicWork(
        USAGE_WORK_NAME,
        ExistingPeriodicWorkPolicy.UPDATE,
        workRequest
    )
}
