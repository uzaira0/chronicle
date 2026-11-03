package com.openlattice.chronicle.services.upload

import android.os.SystemClock
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.WorkQuery
import androidx.work.Worker
import androidx.work.WorkerParameters
import com.openlattice.chronicle.EnrolledWork
import com.openlattice.chronicle.EnrollmentTestStores
import com.openlattice.chronicle.api.ChronicleStudyApi
import com.openlattice.chronicle.api.RestrictedChronicleStudyApi
import com.openlattice.chronicle.audit.auditWorkerParameters
import com.openlattice.chronicle.audit.studyApi
import com.openlattice.chronicle.collection.BatteryChargingState
import com.openlattice.chronicle.collection.BatteryHealth
import com.openlattice.chronicle.collection.BatteryPlugType
import com.openlattice.chronicle.collection.ConnectivityEventType
import com.openlattice.chronicle.collection.InteractionEventType
import com.openlattice.chronicle.collection.NetworkTransport
import com.openlattice.chronicle.collection.audio.AudioUploadWorker
import com.openlattice.chronicle.collection.battery.BatteryCollectionWorker
import com.openlattice.chronicle.collection.battery.BatteryUploadWorker
import com.openlattice.chronicle.collection.device.ExpansionCollectionWorker
import com.openlattice.chronicle.collection.device.ExpansionUploadWorker
import com.openlattice.chronicle.collection.interaction.InteractionUploadWorker
import com.openlattice.chronicle.collection.state.CollectionSettingsSyncWorker
import com.openlattice.chronicle.collection.state.ResearchErasureTestFixture
import com.openlattice.chronicle.collection.state.onPersistenceWorker
import com.openlattice.chronicle.data.CaptureWindow
import com.openlattice.chronicle.data.ParticipationStatus
import com.openlattice.chronicle.models.ExtractedUsageEvent
import com.openlattice.chronicle.participantaccess.MobileReminderConfiguration
import com.openlattice.chronicle.preferences.EnrollmentSettings
import com.openlattice.chronicle.serialization.ChronicleCallException
import com.openlattice.chronicle.serialization.JsonSerializer
import com.openlattice.chronicle.services.enrollment.EnrollmentMonitoringWorker
import com.openlattice.chronicle.services.notifications.NotificationsWorker
import com.openlattice.chronicle.services.sensors.SensorSettingsRefreshWorker
import com.openlattice.chronicle.services.sync.ChronicleSyncWorker
import com.openlattice.chronicle.services.sync.triggerImmediateChronicleSync
import com.openlattice.chronicle.storage.AudioContentSampleEntry
import com.openlattice.chronicle.storage.BatterySampleEntry
import com.openlattice.chronicle.storage.ConnectivityStateSampleEntry
import com.openlattice.chronicle.storage.InteractionSampleEntry
import com.openlattice.chronicle.storage.QueueEntry
import com.openlattice.chronicle.storage.UploadQueueTable
import com.openlattice.chronicle.storage.audioContentSampleDao
import com.openlattice.chronicle.storage.interactionSampleDao
import com.openlattice.chronicle.storage.storedInstant
import com.openlattice.chronicle.time.ServerClock
import java.lang.reflect.Proxy
import java.time.Instant
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit
import okhttp3.RequestBody
import androidx.work.ListenableWorker
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.robolectric.annotation.Config

/**
 * Every upload worker the app schedules runs the status step before its first upload call. The workers are
 * enumerated from what the app's own schedulers enqueue; the upload calls are every `upload*` method of the two study
 * APIs. Two clocks: the server paused at its T, the phone (3 min ahead) never heard, the worker fires at T+7 min.
 */
@Config(shadows = [RestrictedApiShadow::class])
class UploadStatusStepTest : ResearchErasureTestFixture() {
    private val minute = TimeUnit.MINUTES.toMillis(1)
    private val skew = 3 * minute

    /** Each upload worker and the queue it drains; every other scheduled worker must not upload at all. */
    private val uploaders: Map<Class<out Worker>, UploadQueueTable> = mapOf(
        ChronicleSyncWorker::class.java to UploadQueueTable.USAGE,
        CombinedUploadWorker::class.java to UploadQueueTable.USAGE,
        BatteryUploadWorker::class.java to UploadQueueTable.BATTERY,
        ExpansionUploadWorker::class.java to UploadQueueTable.CONNECTIVITY_STATE,
        AudioUploadWorker::class.java to UploadQueueTable.AUDIO_CONTENT,
        InteractionUploadWorker::class.java to UploadQueueTable.INTERACTION,
    )
    private val nonUploaders: Set<Class<out Worker>> = setOf(
        EnrollmentMonitoringWorker::class.java, CollectionSettingsSyncWorker::class.java,
        BatteryCollectionWorker::class.java, ExpansionCollectionWorker::class.java,
        NotificationsWorker::class.java, SensorSettingsRefreshWorker::class.java,
    )

    @Before fun routeRestrictedUploads() {
        reminders = null
        RestrictedApiShadow.transport = { url ->
            Proxy.newProxyInstance(RestrictedChronicleStudyApi::class.java.classLoader,
                arrayOf(RestrictedChronicleStudyApi::class.java)) { _, method, args -> server(method.name, args, url) }
                as RestrictedChronicleStudyApi
        }
    }

    private companion object {
        val calls = CopyOnWriteArrayList<Pair<String, String>>()
        @Volatile var status: () -> Any = { error("no status") }
        @Volatile var reminders: MobileReminderConfiguration? = null
        val uploadMethods: Set<String> = (ChronicleStudyApi::class.java.methods + RestrictedChronicleStudyApi::class.java.methods)
            .map { it.name }.filter { it.startsWith("upload") }.toSet()

        /** The study server: answers the status poll, records each upload's body and refuses it (503), refuses the rest. */
        fun server(method: String, args: Array<out Any?>?, url: String = EnrollmentTestStores.TEST_SERVER_URL): Any {
            check(url == EnrollmentTestStores.TEST_SERVER_URL) { "Unexpected study server" }
            if (method == "getDeviceParticipationStatus") return status()
            if (method == "getMobileReminderSchedule") reminders?.let { return it }
            val body = args.orEmpty().firstOrNull { it is RequestBody }
            calls += method to body?.let { uploadedBodyBytes(it).toString(Charsets.UTF_8) }.orEmpty()
            throw ChronicleCallException(method, "request", "", 503)
        }
    }

    /** What the app's own schedulers enqueue, by the worker class name WorkManager tags every request with. */
    private fun registeredWorkers(): Set<Class<out Worker>> {
        onPersistenceWorker { EnrolledWork.reconcile(context) }
        triggerImmediateChronicleSync(context).result.get(10, TimeUnit.SECONDS)
        triggerImmediateUpload(context)
        val infos = WorkManager.getInstance(context)
            .getWorkInfos(WorkQuery.fromStates(WorkInfo.State.values().toList())).get(10, TimeUnit.SECONDS)
        return infos.flatMap { it.tags }.mapNotNull { runCatching { Class.forName(it) }.getOrNull() }
            .filter { Worker::class.java.isAssignableFrom(it) }.map { it.asSubclass(Worker::class.java) }.toSet()
    }

    private fun run(worker: Class<out Worker>) {
        calls.clear()
        EnrollmentTestStores.installApi(studyApi { method, args -> server(method, args) }).use {
            onPersistenceWorker {
                runCatching {
                    worker.getConstructor(android.content.Context::class.java, WorkerParameters::class.java)
                        .newInstance(context, auditWorkerParameters()).doWork()
                }
            }
        }
    }

    /** One row captured before the server's pause and one after it, in each upload worker's queue, on phone stamps. */
    private fun seed(serverPause: Long): Map<UploadQueueTable, Pair<String, String>> {
        val ids = uploaders.values.toSet().associateWith { UUID.randomUUID().toString() to UUID.randomUUID().toString() }
        fun phone(serverMillis: Long) = storedInstant(Instant.ofEpochMilli(serverMillis + skew))
        val before = serverPause - minute
        val after = serverPause + 5 * minute
        for ((table, pair) in ids) for ((id, at) in listOf(pair.first to before, pair.second to after)) when (table) {
            UploadQueueTable.USAGE -> db.queueEntryData().insertEntry(QueueEntry(at, 1,
                JsonSerializer.serializeQueueEntry(listOf(event(at + skew).copy(activityClass = id)))))
            UploadQueueTable.BATTERY -> db.batterySampleDao().insertAll(listOf(BatterySampleEntry(id, phone(at), "UTC", 50,
                BatteryChargingState.CHARGING.name, BatteryPlugType.AC.name, 250, 4000, BatteryHealth.GOOD.name)))
            UploadQueueTable.CONNECTIVITY_STATE -> db.connectivityStateSampleDao().insertAll(listOf(ConnectivityStateSampleEntry(
                id, phone(at), "UTC", ConnectivityEventType.SNAPSHOT.name, NetworkTransport.WIFI.name, true, false, true)))
            UploadQueueTable.AUDIO_CONTENT -> db.audioContentSampleDao().insertAll(listOf(AudioContentSampleEntry(
                id, phone(at), "UTC", "example.player", "title", "artist", "album", null, null)))
            UploadQueueTable.INTERACTION -> db.interactionSampleDao().insertAll(listOf(InteractionSampleEntry(
                id, phone(at), "UTC", InteractionEventType.SCROLL.name, 4, 4, 1, 1, "list", "example.app",
                rawX = null, rawY = null, screenWidth = null, screenHeight = null, normalizedX = null, normalizedY = null,
                scrollDeltaX = 0, scrollDeltaY = 10, eventTimeMillis = null, episodeId = null, dwellMillisSincePrev = null,
                orientation = null, screenDensityDpi = null, scrollVelocityX = null, scrollVelocityY = null,
                scrollReversed = null)))
            else -> error(table)
        }
        return ids
    }

    private fun queued(table: UploadQueueTable): Set<String> = if (table == UploadQueueTable.USAGE) {
        db.queueEntryData().getEntriesAfter(Long.MIN_VALUE, Long.MIN_VALUE, 1_000)
            .flatMap { JsonSerializer.deserializeQueueEntry(it.data) }.filterIsInstance<ExtractedUsageEvent>()
            .mapNotNull { it.activityClass }.toSet()
    } else db.openHelper.readableDatabase.query("SELECT id FROM `${table.tableName}`").use { rows ->
        buildSet { while (rows.moveToNext()) add(rows.getString(0)) }
    }

    private fun sent(id: String) = calls.any { (method, body) -> method in uploadMethods && body.contains(id) }

    @Test fun everyScheduledWorkerIsClassifiedAndTheUploadersCoverEveryFamily() {
        val registered = registeredWorkers()
        assertEquals("classify each new worker as an uploader (with its queue) or not", uploaders.keys, registered - nonUploaders)
    }

    /** A failed status poll uploads nothing, from any scheduled worker: the phone cannot know the server did not stop it. */
    @Test fun aFailedStatusPollUploadsNothing() {
        for (worker in registeredWorkers()) {
            tearDown(); setUp()
            seed(System.currentTimeMillis() - skew - 7 * minute)
            status = { throw ChronicleCallException("GET", "status", "", 503) }
            run(worker)
            assertEquals("${worker.simpleName} uploaded before a status poll succeeded", emptyList<String>(),
                calls.map { it.first }.filter { it in uploadMethods })
        }
    }

    @Test fun eachUploaderAppliesAPauseItMissedBeforeItUploads() {
        for ((worker, table) in uploaders) {
            tearDown(); setUp()
            val elapsed = SystemClock.elapsedRealtime()
            ServerClock.of(context).measure(Instant.ofEpochMilli(System.currentTimeMillis() - skew), elapsed, elapsed)
            val serverPause = System.currentTimeMillis() - skew - 7 * minute
            val (before, after) = seed(serverPause).getValue(table)
            status = { polled(ParticipationStatus.PAUSED, listOf(CaptureWindow(utc(serverPause))),
                serverTime = System.currentTimeMillis() - skew) }
            run(worker)
            assertTrue("${worker.simpleName} never tried to upload ${table.name}", sent(before))
            assertTrue("${worker.simpleName} sent a ${table.name} row captured inside the pause", !sent(after))
            assertEquals("${worker.simpleName} ${table.name} queue", setOf(before), queued(table))
        }
    }

    /** The server paused this phone [ago] before now on its clock; the phone runs [skew] ahead. */
    private fun pausedBefore(ago: Long): Long {
        val elapsed = SystemClock.elapsedRealtime()
        ServerClock.of(context).measure(Instant.ofEpochMilli(System.currentTimeMillis() - skew), elapsed, elapsed)
        return System.currentTimeMillis() - skew - ago
    }

    private fun pausedAt(serverPause: Long) = polled(ParticipationStatus.PAUSED, listOf(CaptureWindow(utc(serverPause))),
        serverTime = System.currentTimeMillis() - skew)

    /**
     * The reminder worker reads PAUSED from its manifest while an upload's status poll is in flight,
     * after the poll read the settings generation and before it applies. Only the poll applies a status, with its
     * capture windows: the upload sends the row captured before the pause and never the one inside it.
     */
    @Test fun aReminderReadDuringTheUploadsStatusPollUploadsNoRowInsideThePause() {
        val serverPause = pausedBefore(7 * minute)
        val (before, after) = seed(serverPause).getValue(UploadQueueTable.USAGE)
        reminders = MobileReminderConfiguration(ParticipationStatus.PAUSED, emptyList())
        var interleaved = false
        status = {
            if (!interleaved) {
                interleaved = true
                NotificationsWorker(context, auditWorkerParameters()).doWork()
            }
            pausedAt(serverPause)
        }
        run(CombinedUploadWorker::class.java)
        assertTrue("the reminder worker never ran inside the poll", interleaved)
        assertTrue("the upload never tried to send the row captured before the pause", sent(before))
        assertTrue("a row captured inside the pause was uploaded", !sent(after))
        assertEquals(setOf(before), queued(UploadQueueTable.USAGE))
    }

    /**
     * A status apply skipped because the settings changed while the poll was in flight is not a success: the monitor
     * retries, the upload sends nothing and keeps the queue whole, and the next poll applies the windows before it drains.
     */
    @Test fun aSkippedStatusApplyIsRetriedAndNothingUploadsUntilTheWindowsApply() {
        val serverPause = pausedBefore(7 * minute)
        val (before, after) = seed(serverPause).getValue(UploadQueueTable.USAGE)
        var skip = true
        status = {
            if (skip) fence.settingsChanged()
            pausedAt(serverPause)
        }
        EnrollmentTestStores.installApi(studyApi { method, args -> server(method, args) }).use {
            assertEquals(ListenableWorker.Result.retry(), onPersistenceWorker {
                EnrollmentMonitoringWorker(context, auditWorkerParameters()).doWork()
            })
        }
        assertEquals(ParticipationStatus.ENROLLED, EnrollmentSettings(context).getParticipationStatus())
        run(CombinedUploadWorker::class.java)
        assertEquals("an upload ran on a skipped status apply", emptyList<String>(),
            calls.map { it.first }.filter { it in uploadMethods })
        assertEquals(setOf(before, after), queued(UploadQueueTable.USAGE))
        skip = false
        run(CombinedUploadWorker::class.java)
        assertTrue("the retried upload never sent the row captured before the pause", sent(before))
        assertTrue("a row captured inside the pause was uploaded", !sent(after))
    }
}
