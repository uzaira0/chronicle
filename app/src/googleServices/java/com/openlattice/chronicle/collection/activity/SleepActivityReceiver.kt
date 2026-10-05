package com.openlattice.chronicle.collection.activity

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.SystemClock
import android.util.Log
import com.google.android.gms.location.ActivityTransitionResult
import com.google.android.gms.location.SleepClassifyEvent
import com.google.android.gms.location.SleepSegmentEvent
import com.openlattice.chronicle.collection.ActivityTransitionType
import com.openlattice.chronicle.collection.CollectionModuleId
import com.openlattice.chronicle.collection.core.ModuleResult
import com.openlattice.chronicle.collection.SleepEventType
import com.openlattice.chronicle.collection.sink.ActivityRecognitionSampleSink
import com.openlattice.chronicle.collection.sink.SleepSampleSink
import com.openlattice.chronicle.collection.state.ResearchPersistenceGate
import com.openlattice.chronicle.collection.state.CollectionGate
import com.openlattice.chronicle.storage.ActivityRecognitionSampleEntry
import com.openlattice.chronicle.storage.ChronicleDb
import com.openlattice.chronicle.storage.SleepSampleEntry
import com.openlattice.chronicle.storage.activityRecognitionSampleDao
import com.openlattice.chronicle.storage.sleepSampleDao
import com.openlattice.chronicle.services.upload.LocalUploadModuleFamily
import com.openlattice.chronicle.services.upload.recordAbandonedGateBatch
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.util.TimeZone
import java.util.UUID

/**
 * Receives Play Services Sleep API and Activity Transition deliveries and persists them into the
 * `sleep_samples` / `activity_recognition_samples` buffers via the sanctioned sinks. Registered by
 * [SleepActivityCaptureController]; one shared PendingIntent feeds this receiver, which inspects
 * the intent for each event kind.
 *
 * Content-free: an activity label/transition (confidence is the API's high-confidence transition,
 * recorded as 100) and a sleep label/segment + coarse light/motion. Writes are gated again on
 * per-module consent so a stale registration can never persist data the participant declined.
 */
public class SleepActivityReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val appContext = context.applicationContext
        val module = CollectionModuleId.fromIdOrNull(intent.getStringExtra("registration_module") ?: "") ?: return
        if (module !in setOf(CollectionModuleId.SLEEP, CollectionModuleId.ACTIVITY_RECOGNITION)) return
        val observedAt = System.currentTimeMillis()
        val observedElapsed = SystemClock.elapsedRealtimeNanos()
        val pending = goAsync()
        Thread {
            var owner: com.openlattice.chronicle.storage.UploadServerEntity? = null
            var observedCount = 0
            try {
                ResearchPersistenceGate.initialize(appContext)
                val origin = ResearchPersistenceGate.guardForRegistration(appContext, module, intent.getStringExtra("registration_scope"))
                if (!origin.isCurrent()) return@Thread
                owner = ResearchPersistenceGate.captureOwner(appContext)
                val floor = ResearchPersistenceGate.observationScope(appContext, module)?.second ?: Long.MAX_VALUE
                observedCount = if (module == CollectionModuleId.SLEEP) {
                    SleepSegmentEvent.extractEvents(intent).count { it.startTimeMillis >= floor } +
                        SleepClassifyEvent.extractEvents(intent).count { it.timestampMillis >= floor }
                } else {
                    ActivityTransitionResult.extractResult(intent)?.transitionEvents?.count {
                        observedAt - (observedElapsed - it.elapsedRealTimeNanos) / 1_000_000L >= floor
                    } ?: 0
                }
                handle(appContext, intent, origin, owner, floor, observedAt, observedElapsed)
            } catch (e: Exception) {
                // The broadcast is not redelivered: whatever it carried is lost.
                Log.e(TAG, "Sleep/activity receive failed", e)
                com.openlattice.chronicle.services.upload.recordForExpectedOwner(
                    appContext, owner,
                    if (module == CollectionModuleId.SLEEP) LocalUploadModuleFamily.SLEEP else LocalUploadModuleFamily.ACTIVITY_RECOGNITION,
                    com.openlattice.chronicle.services.upload.LocalOperationalIssue.LOCAL_WRITE_FAILED, observedCount,
                )
            } finally {
                pending.finish()
            }
        }.start()
    }

    private fun handle(appContext: Context, intent: Intent,
                       origin: com.openlattice.chronicle.collection.state.CollectionPersistenceGuard,
                       owner: com.openlattice.chronicle.storage.UploadServerEntity?, floor: Long,
                       nowMillis: Long, elapsedNowNanos: Long) {
        val db = ChronicleDb.getInstance(appContext)

        if (SleepSegmentEvent.hasEvents(intent) || SleepClassifyEvent.hasEvents(intent)) {
            if (CollectionGate.collects(appContext, CollectionModuleId.SLEEP)) {
                persistSleep(appContext, db, intent, owner, origin, floor)
            }
        }
        if (ActivityTransitionResult.hasResult(intent)) {
            if (CollectionGate.collects(appContext, CollectionModuleId.ACTIVITY_RECOGNITION)) {
                persistActivity(appContext, db, intent, nowMillis, elapsedNowNanos, owner, origin, floor)
            }
        }
    }

    private fun persistSleep(appContext: Context, db: ChronicleDb, intent: Intent,
                             owner: com.openlattice.chronicle.storage.UploadServerEntity?,
                             origin: com.openlattice.chronicle.collection.state.CollectionPersistenceGuard, floor: Long) {
        val rows = mutableListOf<SleepSampleEntry>()
        if (SleepSegmentEvent.hasEvents(intent)) {
            for (e in SleepSegmentEvent.extractEvents(intent)) {
                rows += SleepSampleEntry(
                    id = UUID.randomUUID().toString(),
                    timestamp = isoUtc(e.startTimeMillis),
                    timezone = TimeZone.getDefault().id,
                    eventType = SleepEventType.SEGMENT.name,
                    segmentStartMillis = e.startTimeMillis,
                    segmentEndMillis = e.endTimeMillis,
                    segmentStatus = sleepSegmentStatusFor(e.status).name,
                    confidence = null,
                    light = null,
                    motion = null,
                )
            }
        }
        if (SleepClassifyEvent.hasEvents(intent)) {
            for (e in SleepClassifyEvent.extractEvents(intent)) {
                rows += SleepSampleEntry(
                    id = UUID.randomUUID().toString(),
                    timestamp = isoUtc(e.timestampMillis),
                    timezone = TimeZone.getDefault().id,
                    eventType = SleepEventType.CLASSIFY.name,
                    segmentStartMillis = null,
                    segmentEndMillis = null,
                    segmentStatus = null,
                    confidence = e.confidence,
                    light = e.light,
                    motion = e.motion,
                )
            }
        }
        if (rows.isNotEmpty()) {
            val result = SleepSampleSink(
                db.sleepSampleDao(),
                persistenceGuard = origin,
            ).write(rows.filter { Instant.parse(it.timestamp).toEpochMilli() >= floor })
            recordAbandonedGateBatch(appContext, owner, LocalUploadModuleFamily.SLEEP, result, rows.size)
            logWrite(writeOutcome("sleep sample(s)", rows.size, result))
        }
    }

    private fun persistActivity(
        appContext: Context,
        db: ChronicleDb,
        intent: Intent,
        nowMillis: Long,
        elapsedNowNanos: Long,
        owner: com.openlattice.chronicle.storage.UploadServerEntity?,
        origin: com.openlattice.chronicle.collection.state.CollectionPersistenceGuard, floor: Long,
    ) {
        val result = ActivityTransitionResult.extractResult(intent) ?: return
        val rows = result.transitionEvents.map { ev ->
            val wallMillis = nowMillis - (elapsedNowNanos - ev.elapsedRealTimeNanos) / 1_000_000L
            ActivityRecognitionSampleEntry(
                id = UUID.randomUUID().toString(),
                timestamp = isoUtc(wallMillis),
                timezone = TimeZone.getDefault().id,
                activityType = detectedActivityTypeFor(ev.activityType).name,
                // The Activity Transition API only delivers high-confidence transitions; it carries
                // no per-event confidence, so a confirmed transition is recorded at 100.
                confidence = 100,
                transitionType = transitionTypeFor(ev.transitionType).name,
            )
        }
        if (rows.isNotEmpty()) {
            val result = ActivityRecognitionSampleSink(
                db.activityRecognitionSampleDao(),
                persistenceGuard = origin,
            ).write(rows.filter { Instant.parse(it.timestamp).toEpochMilli() >= floor })
            recordAbandonedGateBatch(appContext, owner, LocalUploadModuleFamily.ACTIVITY_RECOGNITION, result, rows.size)
            logWrite(writeOutcome("activity transition(s)", rows.size, result))
        }
    }

    private fun logWrite(outcome: Pair<Int, String>) {
        Log.println(outcome.first, TAG, outcome.second)
    }

    private fun isoUtc(epochMillis: Long): String =
        OffsetDateTime.ofInstant(Instant.ofEpochMilli(epochMillis), ZoneOffset.UTC).toString()

    internal companion object {
        private const val TAG = "SleepActivityReceiver"

        /** Action the capture controller's PendingIntent targets; matched by the manifest receiver. */
        public const val ACTION_SLEEP_ACTIVITY: String = "com.openlattice.chronicle.SLEEP_ACTIVITY_UPDATE"

        /**
         * Log priority and text for one sink write. Only [ModuleResult.Ok] reports rows as persisted;
         * a failed or retry result is an error because a broadcast is never redelivered.
         */
        internal fun writeOutcome(kind: String, attempted: Int, result: ModuleResult): Pair<Int, String> =
            when (result) {
                is ModuleResult.Ok -> Log.INFO to "Persisted $attempted $kind"
                is ModuleResult.Skipped -> Log.INFO to "Skipped $attempted $kind: ${result.reason}"
                is ModuleResult.Retry -> Log.ERROR to "Dropped $attempted $kind: ${result.reason}"
                is ModuleResult.Failed -> Log.ERROR to "Failed to persist $attempted $kind: ${result.redactedMessage}"
            }

        // GMS ActivityTransition.ACTIVITY_TRANSITION_ENTER = 0 / _EXIT = 1 (stable API contract).
        private fun transitionTypeFor(gmsTransition: Int): ActivityTransitionType =
            if (gmsTransition == 0) ActivityTransitionType.ENTER else ActivityTransitionType.EXIT
    }
}
