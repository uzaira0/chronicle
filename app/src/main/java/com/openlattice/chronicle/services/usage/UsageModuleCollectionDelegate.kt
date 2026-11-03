package com.openlattice.chronicle.services.usage

import com.openlattice.chronicle.storage.QueueEntry

import android.content.Context
import android.util.Log
import com.google.common.base.Stopwatch
import com.openlattice.chronicle.collection.CollectionModuleId
import com.openlattice.chronicle.collection.CollectionUploadEvidence
import com.openlattice.chronicle.collection.core.CollectionWindow
import com.openlattice.chronicle.collection.core.ModuleResult
import com.openlattice.chronicle.collection.state.CollectionGate
import com.openlattice.chronicle.collection.state.ResearchPersistenceGate
import com.openlattice.chronicle.collection.sink.UsageEventSink
import com.openlattice.chronicle.collection.usage.DaoUsagePollCheckpointStore
import com.openlattice.chronicle.collection.usage.SystemUsageEventPoller
import com.openlattice.chronicle.collection.usage.UsageCursorMovedException
import com.openlattice.chronicle.collection.usage.UsageEventsCollectionModule
import com.openlattice.chronicle.collection.usage.UsageModulePersistence
import com.openlattice.chronicle.collection.usage.UsagePollOutcome
import com.openlattice.chronicle.constants.TelemetryEvents
import com.openlattice.chronicle.data.ParticipationStatus
import com.openlattice.chronicle.models.ExtractedUsageEvent
import com.openlattice.chronicle.serialization.JsonSerializer
import com.openlattice.chronicle.sensors.UsageEventsChronicleSensor
import com.openlattice.chronicle.storage.ChronicleDb
import com.openlattice.chronicle.storage.DECISION_SEQ_KEY
import com.openlattice.chronicle.storage.LifecycleStateEntity
import com.openlattice.chronicle.storage.UploadQueueTable
import com.openlattice.chronicle.storage.nextQueueWriteTimestamp
import com.openlattice.chronicle.telemetry.LocalTelemetry
import com.openlattice.chronicle.storage.labelTimeline
import java.util.TreeMap
import java.util.concurrent.TimeUnit

/** Polls and persists usage through the consent-gated collection module. */
class UsageModuleCollectionDelegate(context: Context) : UsageCollectionDelegateBase(context) {
    private lateinit var usageModule: UsageEventsCollectionModule
    private lateinit var usageEventSink: UsageEventSink

    override fun monitorUsage(): Boolean {
        while (true) {
            try { return pollUsage() }
            catch (_: UsageCursorMovedException) {
                Log.i(TAG, "Usage cursor changed; reading the remaining window")
            }
        }
    }

    private fun pollUsage(): Boolean {
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

        Log.d(
            javaClass.name,
            "Collecting Usage Information. Service $serviceId has been running for ${
                sw.elapsed(TimeUnit.SECONDS)
            } seconds."
        )
        return collectWindow()
    }

    /** Opens the usage module inside admission's read lease; false when the participant has no observation scope. */
    private fun openUsageModule(): Boolean = ResearchPersistenceGate.withReadLease {
        val scope = ResearchPersistenceGate.observationScope(context, CollectionModuleId.USAGE_EVENTS)
            ?: return@withReadLease false
        usageEventSink = revisionedUsageSink()
        val checkpoints = DaoUsagePollCheckpointStore(chronicleDb.usagePollCheckpointDao(), scope.key, scope.floorMillis)
        val initialStart = checkpoints.readOrRememberStart {
            maxOf(scope.floorMillis, UsageEventsChronicleSensor(context).previousPollTimestamp())
        }
        usageModule = UsageEventsCollectionModule(
            poller = SystemUsageEventPoller(context),
            checkpointStore = checkpoints,
            previousPollTimestampFallback = { initialStart },
        )
        true
    }

    private fun collectWindow(): Boolean {
        val w = Stopwatch.createStarted()
        if (!openUsageModule()) return true
        val currentPollTimestamp = System.currentTimeMillis()
        // Participant labels are an optional field authorized by USER_IDENTIFICATION, not an
        // implicit part of usage_events: the sink keeps a label only on events captured while that
        // module collected. The participant's local switch still withholds every label.
        val users = if (settings.isUserIdentificationSwitchOn()) userStorageQueue.labelTimeline() else TreeMap()

        // Usage poll runs through the collection module over the UsageEventPoller seam.
        // The module resolves the previous poll timestamp from the checkpoint cursor.
        val outcome = usageModule.pollWindow(
            users,
            CollectionWindow(startEpochMs = 0L, endEpochMs = currentPollTimestamp),
        ) ?: return true
        // The sink projects the optional fields by the decision that held at each event's capture
        // time. Keep the upstream combined UsageStats event sequence intact. Supplemental device-state
        // broadcasts have their own persistence path and are not synthesized into each poll.
        val events = outcome.events
        if (events.isEmpty()) Log.i(TAG, "No sensors reported any data since last poll.")
        // An empty poll still commits its checkpoint, so the next poll starts at this one's end.
        val queueEntries = if (events.isEmpty()) emptyList()
        else buildUsageQueueEntries(events, chronicleDb.nextQueueWriteTimestamp()) { rand.nextLong() }
        if (!persistUsageQueueAndCheckpoint(queueEntries, outcome) || events.isEmpty()) return true

        return finishUsageCollection(events, w, users, currentPollTimestamp, usageEventSink)
    }

    /**
     * Writes the queue entries through [UsageEventSink] and commits the usage-events
     * checkpoint inside one Room transaction, from the cursor this poll read. The transactional
     * write+checkpoint logic is extracted into [UsageModulePersistence] so it is JVM-unit
     * testable; here it is wired to the real `ChronicleDb` transaction runner.
     */
    private fun persistUsageQueueAndCheckpoint(
        queueEntries: List<QueueEntry>,
        outcome: UsagePollOutcome,
    ): Boolean = UsageModulePersistence.persist(
            entries = queueEntries,
            currentPollTimestamp = outcome.currentPollTimestamp,
            sink = usageEventSink,
            commitCheckpoint = { usageModule.commitCheckpoint(outcome) },
            transaction = { body -> chronicleDb.runInTransaction(body) },
        )

    /** Split inside admission's lease: a settings apply may have finished while the provider was reading. */
    private fun revisionedUsageSink(): UsageEventSink {
        val sink = ResearchPersistenceGate.usageSink(context)
        return UsageEventSink(storageQueue, persistenceGuard = sink.captureAdmission(), writeEntries = { entries ->
            val revisions = revisionTimeline(chronicleDb, checkNotNull(ResearchPersistenceGate.currentOwnerKey()))
            val latest = revisions.lastEntry()?.key
            val decisionSeq = currentDecisionSeq(chronicleDb)
            entries.forEach { entry ->
                val groups = JsonSerializer.deserializeQueueEntry(entry.data).groupBy { sample ->
                    val capturedAt = (sample as ExtractedUsageEvent).timestamp.toInstant().toEpochMilli()
                    val revision = revisions.floorEntry(capturedAt)
                    if (revision == null) Revision(ResearchPersistenceGate.currentSettingsVersion(), decisionSeq)
                    else if (revision.key == latest) revision.value.copy(decisionSeq = decisionSeq)
                    else revision.value
                }
                groups.forEach { (capture, samples) ->
                    // Allocated above every queued row, after the preceding group: each row at or past it is this group's,
                    // including rows a bounded insert splits off an oversized payload.
                    val from = chronicleDb.nextQueueWriteTimestamp(entry.writeTimestamp)
                    val result = sink.writeAdmitted(buildUsageQueueEntries(samples, from) { rand.nextLong() })
                    if (result is ModuleResult.Failed) throw result.error
                    check(result.isSuccess) { "usage revision write did not persist" }
                    // The sink may have removed every sample in a suspended window. Stamp only inserted rows,
                    // in the same transaction as their payload and checkpoint; the admission and field gates stay shared.
                    val evidence = chronicleDb.uploadQueueEvidenceDao()
                    evidence.put(evidence.get(UploadQueueTable.USAGE.name, storageQueue.keysFrom(from)).map {
                        it.copy(settingsVersion = capture.settingsVersion, decisionSeq = capture.decisionSeq)
                    })
                }
            }
        })
    }

    private data class Revision(val settingsVersion: Int?, val decisionSeq: Long?)

    companion object {
        private const val REVISION_VERSION = "settingsVersion"
        private const val REVISION_DECISION = "decisionSeq"
        private fun revisionKey(ownerKey: String) = "usage_revisions:$ownerKey"

        private fun revisionTimeline(db: ChronicleDb, ownerKey: String): TreeMap<Long, Revision> =
            TreeMap<Long, Revision>().apply {
                // Persist native maps, so release shrinking cannot rename a reflected model's fields.
                JsonSerializer.fromJson<Map<Long, Map<String, Long?>>>(
                    db.lifecycleStateDao().get(revisionKey(ownerKey))?.value ?: "{}"
                ).orEmpty().forEach { (at, capture) ->
                    put(at, Revision(capture[REVISION_VERSION]?.let(Math::toIntExact), capture[REVISION_DECISION]))
                }
            }

        private fun currentDecisionSeq(db: ChronicleDb): Long? =
            db.lifecycleStateDao().get(DECISION_SEQ_KEY)?.value?.toLong()
                ?.takeIf { it != CollectionUploadEvidence.UNKNOWN_DECISION_SEQ }

        /** Caller holds the settings stop and commits this timeline with the reconciled module states. */
        internal fun recordSettingsRevision(context: Context, ownerKey: String, previousVersion: Int?,
                                            version: Int, at: Long) {
            val db = ChronicleDb.getInstance(context)
            val revisions = revisionTimeline(db, ownerKey)
            val appliedVersion = version.takeIf { it != CollectionUploadEvidence.UNKNOWN_SETTINGS_VERSION }
            if (revisions.isEmpty()) revisions[Long.MIN_VALUE] = Revision(previousVersion ?: appliedVersion, null)
            if (revisions.lastEntry()?.value?.settingsVersion == appliedVersion) return
            // Freeze the closing revision's decisions before a later re-consent can replace its acceptance.
            val previous = checkNotNull(revisions.lastEntry())
            revisions[previous.key] = previous.value.copy(decisionSeq = currentDecisionSeq(db))
            // As with labelTimeline, a later revision in an earlier clock frame supersedes that frame's future.
            revisions.tailMap(at, true).clear()
            revisions[at] = Revision(appliedVersion, null)
            db.lifecycleStateDao().upsert(LifecycleStateEntity(revisionKey(ownerKey),
                JsonSerializer.toJson(revisions.mapValues { (_, capture) -> mapOf(
                    REVISION_VERSION to capture.settingsVersion?.toLong(), REVISION_DECISION to capture.decisionSeq)
                }), at))
        }
    }
}
