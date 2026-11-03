package com.openlattice.chronicle.collection.state

import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import com.openlattice.chronicle.EnrollmentTestStores
import com.openlattice.chronicle.android.AndroidSensorType
import com.openlattice.chronicle.android.ChronicleData
import com.openlattice.chronicle.android.ChronicleUsageEvent
import com.openlattice.chronicle.android.UploadReceipt
import com.openlattice.chronicle.audit.auditWorkerParameters
import com.openlattice.chronicle.audit.studyApi
import com.openlattice.chronicle.services.sync.ChronicleSyncWorker
import com.openlattice.chronicle.services.upload.decodedUploadBody
import com.openlattice.chronicle.collection.CollectionModuleId
import com.openlattice.chronicle.collection.usage.DaoUsagePollCheckpointStore
import com.openlattice.chronicle.data.CaptureWindow
import com.openlattice.chronicle.data.ParticipationStatus
import com.openlattice.chronicle.models.ExtractedUsageEvent
import com.openlattice.chronicle.serialization.JsonSerializer
import com.openlattice.chronicle.services.enrollment.EnrollmentMonitoringWorker
import com.openlattice.chronicle.storage.QueueEntry
import com.openlattice.chronicle.storage.SensorSampleEntry
import com.openlattice.chronicle.time.ServerClock
import android.os.SystemClock
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneId
import java.util.Random
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertEquals
import org.junit.Test
import org.robolectric.Shadows.shadowOf
import org.robolectric.shadows.ShadowUsageStatsManager

/**
 * Two clocks: the server publishes its capture windows on its clock, the phone stamps rows on its own. A queued row
 * is dropped exactly when the moment it was captured lies inside a window.
 */
class CaptureWindowQueueTest : ResearchErasureTestFixture() {
    private val minute = TimeUnit.MINUTES.toMillis(1)
    private val santiago = ZoneId.of("America/Santiago")

    /** The phone's wall clock is the server's plus [skew]; the measurement poll is instantaneous. */
    private fun measure(skew: Long) {
        val elapsed = SystemClock.elapsedRealtime()
        ServerClock.of(context).measure(Instant.ofEpochMilli(System.currentTimeMillis() - skew), elapsed, elapsed)
    }

    /** A phone stamp written in Santiago local time, as the collectors write it. */
    private fun stamp(phoneMillis: Long) = OffsetDateTime.ofInstant(Instant.ofEpochMilli(phoneMillis), santiago)

    private fun sensor(id: String, phoneMillis: Long) = SensorSampleEntry(id, AndroidSensorType.accelerometer.name,
        stamp(phoneMillis).toString(), santiago.id, 1f, 2f, 3f, null, 0)

    private fun usage(id: String, phoneMillis: Long) = event(phoneMillis).copy(activityClass = id, timestamp = stamp(phoneMillis))

    private fun queuedSensors() = db.sensorSampleDao().getOldest(1_000).map { it.id }.toSet()

    private fun queuedUsage() = db.queueEntryData().getEntriesAfter(Long.MIN_VALUE, Long.MIN_VALUE, 1_000)
        .flatMap { JsonSerializer.deserializeQueueEntry(it.data) }.filterIsInstance<ExtractedUsageEvent>()
        .mapNotNull { it.activityClass }.toSet()

    private fun apply(windows: List<CaptureWindow>) = onPersistenceWorker {
        ResearchPersistenceGate.applyCaptureWindows(context, owner, fence.settingsGeneration(), windows, null)
    }

    /** The phone runs 20 s ahead; a row captured 10 s before the pause uploads, one 10 s after it does not. */
    @Test fun aRowTenSecondsBeforeThePauseUploadsUnderTwentySecondsOfSkew() {
        val skew = 20_000L
        measure(skew)
        val serverPause = System.currentTimeMillis() - skew - 5 * minute
        db.sensorSampleDao().insertAll(listOf(sensor("before", serverPause - 10_000 + skew), sensor("after", serverPause + 10_000 + skew)))
        db.queueEntryData().insertEntry(QueueEntry(1, 1, JsonSerializer.serializeQueueEntry(listOf(
            usage("before", serverPause - 10_000 + skew), usage("after", serverPause + 10_000 + skew)))))
        apply(listOf(CaptureWindow(utc(serverPause))))
        assertEquals(setOf("before"), queuedSensors())
        assertEquals(setOf("before"), queuedUsage())
    }

    /** Generated timelines: skews of seconds, minutes and hours either way, closed and open windows, rows anywhere. */
    @Test fun generatedTimelinesDropExactlyTheRowsCapturedInsideAWindow() {
        val random = Random(20261028)
        repeat(40) { run ->
            CollectionHardStop.clearQueues(db)
            val skew = listOf(1_000L, 60_000L, 3_600_000L)[random.nextInt(3)] * (random.nextInt(5) + 1) *
                (if (random.nextBoolean()) 1 else -1)
            measure(skew)
            val serverNow = System.currentTimeMillis() - skew
            val windows = (0 until random.nextInt(3) + 1).map {
                val start = serverNow - random.nextInt(6 * 60) * minute
                val end = start + (random.nextInt(90) + 1) * minute
                CaptureWindow(utc(start), utc(end).takeIf { end < serverNow && random.nextInt(4) > 0 })
            }
            fun inside(serverMillis: Long) = windows.any { window ->
                serverMillis >= window.start.toInstant().toEpochMilli() &&
                    (window.end == null || serverMillis < window.end!!.toInstant().toEpochMilli())
            }
            // Rows at least a second from every boundary: the measurement itself is good to a few milliseconds.
            val boundaries = windows.flatMap { listOfNotNull(it.start, it.end) }.map { it.toInstant().toEpochMilli() }
            val captured = generateSequence { serverNow - random.nextInt(7 * 60 * 60) * 1_000L }
                .filter { at -> boundaries.none { Math.abs(it - at) < 1_000 } }.take(30).toList()
            val ids = captured.indices.map { "row-$it" }
            db.sensorSampleDao().insertAll(captured.mapIndexed { i, at -> sensor(ids[i], at + skew) })
            captured.mapIndexed { i, at -> usage(ids[i], at + skew) }.chunked(4).forEach {
                db.queueEntryData().insertEntry(QueueEntry(1, 1, JsonSerializer.serializeQueueEntry(it)))
            }
            apply(windows)
            val expected = ids.filterIndexed { i, _ -> !inside(captured[i]) }.toSet()
            assertEquals("run $run skew $skew", expected, queuedSensors())
            assertEquals("run $run skew $skew", expected, queuedUsage())
        }
    }

    /**
     * The monitor learns of a completion it missed. The last usage poll reads up to the window start and keeps
     * the 10-second-before row (clock skew); what was captured inside the window, queued or not, never uploads.
     */
    @Test fun theLastUsagePollBeforeACompletionKeepsWhatCameBeforeIt() {
        val skew = 20_000L
        val phoneNow = System.currentTimeMillis()
        val scope = ResearchPersistenceGate.observationScope(context, CollectionModuleId.USAGE_EVENTS)!!
        DaoUsagePollCheckpointStore(db.usagePollCheckpointDao(), scope.key, scope.floorMillis)
            .commitPollTimestamp(phoneNow - 10 * minute)
        val phoneStop = phoneNow - 5 * minute
        val manager = shadowOf(context.getSystemService(UsageStatsManager::class.java))
        for ((activity, at) in listOf("unread-before" to phoneStop - 2 * minute, "unread-just-before" to phoneStop - 10_000,
            "unread-inside" to phoneStop + minute)) {
            manager.addEvent(ShadowUsageStatsManager.EventBuilder.buildEvent().setPackage("example.app").setClass(activity)
                .setTimeStamp(at).setEventType(UsageEvents.Event.ACTIVITY_RESUMED).build())
        }
        db.queueEntryData().insertEntry(QueueEntry(1, 1, JsonSerializer.serializeQueueEntry(listOf(
            usage("queued-before", phoneStop - 3 * minute), usage("queued-inside", phoneStop + 2 * minute)))))
        withStudyApi { method ->
            check(method == "getDeviceParticipationStatus") { method }
            polled(ParticipationStatus.COLLECTION_COMPLETED, listOf(CaptureWindow(utc(phoneStop - skew))),
                serverTime = System.currentTimeMillis() - skew)
        }.use { onPersistenceWorker { EnrollmentMonitoringWorker(context, auditWorkerParameters()).doWork() } }
        assertEquals(setOf("queued-before", "unread-before", "unread-just-before"), queuedUsage())
    }

    /**
     * The dashboard paused at server T and the phone never heard (its clock runs 3 min ahead). The sync worker fires
     * at T+7 min: the row captured after T never uploads; the row captured before it uploads or stays queued.
     */
    @Test fun theSyncWorkerAppliesAMissedPauseBeforeItUploads() {
        val skew = 3 * minute
        measure(skew)
        val serverPause = System.currentTimeMillis() - skew - 7 * minute
        db.queueEntryData().insertEntry(QueueEntry(1, 1, JsonSerializer.serializeQueueEntry(listOf(
            usage("before", serverPause - minute + skew), usage("after", serverPause + 5 * minute + skew)))))
        val sent = mutableSetOf<String>()
        EnrollmentTestStores.installApi(studyApi { method, args ->
            when (method) {
                "getDeviceParticipationStatus" -> polled(ParticipationStatus.PAUSED, listOf(CaptureWindow(utc(serverPause))),
                    serverTime = System.currentTimeMillis() - skew)
                "uploadAndroidUsageEventData" -> decodedUploadBody<ChronicleData>(args!![3]).map { it as ChronicleUsageEvent }
                    .also { events -> events.forEach { sent += it.activityClass!! } }
                    .let { events -> UploadReceipt(events.mapTo(linkedSetOf()) { it.clientEventId!! }, emptySet(), events.size) }
                else -> error(method)
            }
        }).use { onPersistenceWorker { ChronicleSyncWorker(context, auditWorkerParameters()).doWork() } }
        assertEquals(setOf("before"), sent + queuedUsage())
    }
}
