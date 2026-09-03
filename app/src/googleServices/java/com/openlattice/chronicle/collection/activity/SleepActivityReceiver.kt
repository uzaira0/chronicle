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
        val pending = goAsync()
        Thread {
            try {
                handle(appContext, intent)
            } catch (e: Exception) {
                Log.w(TAG, "Sleep/activity receive failed", e)
            } finally {
                pending.finish()
            }
        }.start()
    }

    private fun handle(appContext: Context, intent: Intent) {
        val db = ChronicleDb.getInstance(appContext)
        val nowMillis = System.currentTimeMillis()
        val elapsedNowNanos = SystemClock.elapsedRealtimeNanos()

        if (SleepSegmentEvent.hasEvents(intent) || SleepClassifyEvent.hasEvents(intent)) {
            if (CollectionGate.collects(appContext, CollectionModuleId.SLEEP)) {
                persistSleep(appContext, db, intent)
            }
        }
        if (ActivityTransitionResult.hasResult(intent)) {
            if (CollectionGate.collects(appContext, CollectionModuleId.ACTIVITY_RECOGNITION)) {
                persistActivity(appContext, db, intent, nowMillis, elapsedNowNanos)
            }
        }
    }

    private fun persistSleep(appContext: Context, db: ChronicleDb, intent: Intent) {
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
            SleepSampleSink(
                db.sleepSampleDao(),
                persistenceGuard = ResearchPersistenceGate.guard(appContext, CollectionModuleId.SLEEP),
            ).write(rows)
            Log.i(TAG, "Persisted ${rows.size} sleep sample(s)")
        }
    }

    private fun persistActivity(
        appContext: Context,
        db: ChronicleDb,
        intent: Intent,
        nowMillis: Long,
        elapsedNowNanos: Long,
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
            ActivityRecognitionSampleSink(
                db.activityRecognitionSampleDao(),
                persistenceGuard = ResearchPersistenceGate.guard(appContext, CollectionModuleId.ACTIVITY_RECOGNITION),
            ).write(rows)
            Log.i(TAG, "Persisted ${rows.size} activity transition(s)")
        }
    }

    private fun isoUtc(epochMillis: Long): String =
        OffsetDateTime.ofInstant(Instant.ofEpochMilli(epochMillis), ZoneOffset.UTC).toString()

    internal companion object {
        private const val TAG = "SleepActivityReceiver"

        /** Action the capture controller's PendingIntent targets; matched by the manifest receiver. */
        public const val ACTION_SLEEP_ACTIVITY: String = "com.openlattice.chronicle.SLEEP_ACTIVITY_UPDATE"

        // GMS ActivityTransition.ACTIVITY_TRANSITION_ENTER = 0 / _EXIT = 1 (stable API contract).
        private fun transitionTypeFor(gmsTransition: Int): ActivityTransitionType =
            if (gmsTransition == 0) ActivityTransitionType.ENTER else ActivityTransitionType.EXIT
    }
}
