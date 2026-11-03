package com.openlattice.chronicle.services.upload

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.openlattice.chronicle.EnrollmentTestStores
import com.openlattice.chronicle.android.ChronicleData
import com.openlattice.chronicle.android.ChronicleUsageEvent
import com.openlattice.chronicle.android.UploadReceipt
import com.openlattice.chronicle.android.fromInteractionType
import com.openlattice.chronicle.api.ChronicleStudyApi
import com.openlattice.chronicle.collection.AndroidConnectivityStateEvent
import com.openlattice.chronicle.collection.CollectionModuleId
import com.openlattice.chronicle.collection.CollectionUploadEvidence
import com.openlattice.chronicle.collection.device.ExpansionUploadWorkerDelegate
import com.openlattice.chronicle.collection.device.toAndroidConnectivityStateEvent
import com.openlattice.chronicle.collection.state.ParticipantDecision
import com.openlattice.chronicle.collection.state.eraseSharedQueueModule
import com.openlattice.chronicle.constants.ANDROID_SYSTEM_PACKAGE
import com.openlattice.chronicle.constants.INTERACTION_BATTERY_CHARGING
import com.openlattice.chronicle.generated.UploadLimits
import com.openlattice.chronicle.models.ExtractedUsageEvent
import com.openlattice.chronicle.preferences.EncryptedPrefsHelper
import com.openlattice.chronicle.serialization.ChronicleCallException
import com.openlattice.chronicle.serialization.JsonSerializer
import com.openlattice.chronicle.services.usage.buildUsageQueueEntries
import com.openlattice.chronicle.storage.ChronicleDb
import com.openlattice.chronicle.storage.CollectionModuleStateEntity
import com.openlattice.chronicle.storage.ConnectivityStateSampleEntry
import com.openlattice.chronicle.storage.LifecycleStateEntity
import com.openlattice.chronicle.storage.QUEUE_CAPTURE_VERSION_KEY
import com.openlattice.chronicle.storage.QueueEntry
import com.openlattice.chronicle.storage.UPLOAD_EVIDENCE_CALLBACK
import com.openlattice.chronicle.storage.UploadQueueTable
import com.openlattice.chronicle.storage.UploadServerEntity
import java.io.IOException
import java.lang.reflect.Proxy
import java.net.HttpURLConnection
import java.time.OffsetDateTime
import java.util.UUID
import okhttp3.RequestBody
import okhttp3.ResponseBody.Companion.toResponseBody
import org.json.JSONObject
import org.junit.After
import org.junit.Assert
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
abstract class UsageUploadFixture {

    protected val context: Context = ApplicationProvider.getApplicationContext()
    protected lateinit var db: ChronicleDb
    protected lateinit var server: UploadServerEntity
    protected val posts = mutableListOf<List<ChronicleUsageEvent>>()
    protected val usagePosts get() = posts.map { it.size }
    protected val bodyBytes = mutableListOf<Long>()
    protected val usageVersions = mutableListOf<Int?>()
    protected val failures = mutableListOf<Exception>()

    @Before fun setUp() {
        EnrollmentTestStores.prefs = context.getSharedPreferences("executor-test", Context.MODE_PRIVATE)
        EncryptedPrefsHelper.getEncryptedPrefs(context).edit().clear().commit()
        db = Room.inMemoryDatabaseBuilder(context, ChronicleDb::class.java).allowMainThreadQueries()
            .addCallback(UPLOAD_EVIDENCE_CALLBACK).build()
        val id = db.uploadServerDao().insert(UploadServerEntity(
            name = "test", url = "https://localhost", studyId = "11111111-1111-1111-1111-111111111111",
            participantId = "p1", sourceRandomDeviceId = "d1", createdAt = "2026-09-01T00:00:00Z",
        ))
        server = db.uploadServerDao().getById(id)!!
    }

    @After fun tearDown() {
        db.close()
        EnrollmentTestStores.prefs = null
    }

    protected fun ids(rows: List<ChronicleUsageEvent>) = rows.mapTo(linkedSetOf()) { checkNotNull(it.clientEventId) }

    protected fun accepted(rows: List<ChronicleUsageEvent>) = UploadReceipt(ids(rows), emptySet(), 0)

    /** The server's answer to each POST; it may throw to model a transport or HTTP failure. */
    protected fun executor(respond: (List<ChronicleUsageEvent>) -> UploadReceipt = ::accepted) = UploadExecutor(context, db, studyApiFor = {
        Proxy.newProxyInstance(ChronicleStudyApi::class.java.classLoader, arrayOf(ChronicleStudyApi::class.java)) { _, method, args ->
            check(method.name == "uploadAndroidUsageEventData") { "unexpected call ${method.name}" }
            val rows = decodedUploadBody<ChronicleData>(args[3]).filterIsInstance<ChronicleUsageEvent>()
            posts += rows
            bodyBytes += (args[3] as RequestBody).contentLength()
            usageVersions += (args[2] as Map<*, *>)[CollectionUploadEvidence.SETTINGS_VERSION_HEADER]?.toString()?.toInt()
            respond(rows)
        } as ChronicleStudyApi
    })

    /** One upload run against the current enrollment row; 1 means the run failed and kept its rows. */
    protected fun UploadExecutor.run(stopped: () -> Boolean = { false }) =
        uploadForServer(db.uploadServerDao().getById(server.id)!!, stopped) { failures += it }

    protected fun queued() = db.queueEntryData().getNextEntries(Int.MAX_VALUE)
        .flatMap { JsonSerializer.deserializeQueueEntry(it.data) }.filterIsInstance<ExtractedUsageEvent>()

    protected fun count(sql: String) = db.openHelper.readableDatabase.query(sql).use { it.moveToFirst(); it.getInt(0) }

    protected fun httpError(status: Int, body: String = "refused"): Nothing =
        throw retrofit2.HttpException(retrofit2.Response.error<Any>(status, body.toResponseBody()))

    /** The server's answer to a defect in a row; any other 400 is about the request. */
    protected val rowRejectedBody = """{"code":"${UploadLimits.UPLOAD_ROW_REJECTED_CODE}","message":"refused"}"""

    protected fun validEntry(id: Long, activityClass: String? = null) = QueueEntry(1_000L + id, id, JsonSerializer.serializeQueueEntry(listOf(
        ExtractedUsageEvent(eventType = fromInteractionType("Activity Resumed"),
            appPackageName = "a.b.c", interactionType = "Activity Resumed",
            timestamp = OffsetDateTime.parse("2026-09-24T10:00:00Z"), timezone = "UTC", user = "u", applicationLabel = "abc",
            activityClass = activityClass,
        ),
    )))

    protected fun applyUsagePolicy(version: Int, enabled: Boolean = true) {
        db.collectionModuleStateDao().upsert(CollectionModuleStateEntity(CollectionModuleId.USAGE_EVENTS.id,
            enabled, ParticipantDecision.ACCEPTED.name, 0, false, version))
    }

    protected fun overrideCaptureVersion(version: Int) {
        db.lifecycleStateDao().upsert(LifecycleStateEntity(QUEUE_CAPTURE_VERSION_KEY, version.toString(), 0))
    }

    protected fun disableUsageEvents(): ExtractedUsageEvent {
        val usage = JsonSerializer.deserializeQueueEntry(validEntry(1).data).single() as ExtractedUsageEvent
        db.collectionModuleStateDao().upsertAll(listOf(
            CollectionModuleStateEntity(CollectionModuleId.USAGE_EVENTS.id, false, ParticipantDecision.ACCEPTED.name, 0,
                false, 2),
            CollectionModuleStateEntity(CollectionModuleId.DEVICE_LIFECYCLE.id, true, ParticipantDecision.ACCEPTED.name, 0, false, 2)))
        return usage
    }

    protected fun lifecycleEvent(usage: ExtractedUsageEvent) = usage.copy(
        appPackageName = ANDROID_SYSTEM_PACKAGE,
        interactionType = INTERACTION_BATTERY_CHARGING, user = "")

    protected fun assertRejectedRowIsIsolated(status: Int, refuse: (Int) -> Nothing = { httpError(it, rowRejectedBody) }) {
        db.queueEntryData().insertEntries(listOf(validEntry(1), validEntry(2)))
        var rejectedSingleton = false
        executor { rows ->
            if (rows.size > 1 || !rejectedSingleton) {
                if (rows.size == 1) rejectedSingleton = true
                refuse(status)
            }
            accepted(rows)
        }.run()
        assertEquals(listOf(2, 1, 1), usagePosts)
        val quarantined = JsonSerializer.deserializeQueueEntry(db.localDataQuarantineDao().forSource("dataQueue").single().rawData)
        assertEquals(ids(posts[1]), quarantined.mapTo(linkedSetOf()) { (it as ExtractedUsageEvent).clientEventId })
        assertEquals(status, db.uploadDiagnosticDao().forEnrollment(server.studyId, server.participantId,
            server.sourceRandomDeviceId, "${server.id}:${server.createdAt}").single().httpStatus)
        assertEquals(0, db.queueEntryData().getSize())
    }

    protected fun quarantineRejected(table: UploadQueueTable, id: String, raw: ByteArray, family: LocalUploadModuleFamily) =
        quarantineMalformedSample(db, server, table.tableName, id, raw, family,
            reason = UPLOAD_REJECTED_REASON, httpStatus = UNPROCESSABLE_PAYLOAD_STATUS, settingsVersion = 1)
}

class UploadExecutorRegressionTest : UsageUploadFixture() {
    @Test fun interleavedCaptureVersionsUploadEveryRowUnderItsOwnVersion() {
        val queue = db.queueEntryData()
        applyUsagePolicy(1)
        queue.insertEntry(validEntry(1))
        applyUsagePolicy(2)
        queue.insertEntry(validEntry(2))
        overrideCaptureVersion(1)
        queue.insertEntry(validEntry(3))

        assertEquals(0, executor().run())

        assertEquals(listOf(1, 1, 1), usagePosts)
        assertEquals(listOf(1, 2, 1), usageVersions)
        assertEquals(0, queue.getSize())
    }

    @Test fun acceptedAndDiscardedEventsLeaveTheQueueInOneRun() {
        applyUsagePolicy(1)
        val cutoff = OffsetDateTime.parse("2026-09-24T10:01:00Z")
        val before = JsonSerializer.deserializeQueueEntry(validEntry(1).data).filterIsInstance<ExtractedUsageEvent>().single()
        val queue = db.queueEntryData()
        queue.insertEntry(QueueEntry(1_001L, 1L, JsonSerializer.serializeQueueEntry(listOf(before,
            before.copy(timestamp = cutoff.plusSeconds(1))))))
        for (id in 2L..11L) queue.insertEntry(validEntry(id))
        val stored = mutableListOf<ChronicleUsageEvent>()
        var discarded = 0
        val uploader = executor { rows ->
            val (kept, late) = rows.partition { it.timestamp.isBefore(cutoff) }
            stored += kept
            discarded += late.size
            UploadReceipt(ids(kept), ids(late), 0)
        }
        assertEquals(0, uploader.run())
        assertEquals(11, stored.size)
        assertEquals(1, discarded)
        assertEquals(0, queue.getSize())
        uploader.run()
        assertEquals("No discarded capture is retried after the queue drains", 1, discarded)
        // K2: the phone's delivered count is what the server stored, never the discarded capture.
        assertEquals(stored.size, db.uploadServerDao().getById(server.id)!!.usageUploadSuccessCount)
        assertEquals(stored.size, count("SELECT sum(usageEventsUploaded) FROM upload_stats"))
    }

    @Test fun activityRedactionPreservesHistoricalVersionForDisabledUsageFlush() {
        val queue = db.queueEntryData()
        applyUsagePolicy(1)
        queue.insertEntry(validEntry(1, activityClass = "MainActivity"))
        applyUsagePolicy(2, enabled = false)

        db.runInTransaction { eraseSharedQueueModule(db, CollectionModuleId.IN_APP_ACTIVITY_CLASS) }

        Assert.assertNull(queued().single().activityClass)
        executor().run()
        assertEquals(listOf(1), usagePosts)
        assertEquals(listOf(1), usageVersions)
    }

    @Test fun malformedExpansionUsesItsPayloadFamilyWithoutAnEmptyUpload() {
        val dao = db.connectivityStateSampleDao()
        dao.insertAll(listOf(ConnectivityStateSampleEntry(
            "malformed", "not-a-date", "UTC", "SNAPSHOT", "WIFI", true, false, true,
        )))
        val failures = ExpansionUploadWorkerDelegate(context, db).uploadStream(
            servers = listOf(server),
            source = DedicatedUploadSource(
                table = UploadQueueTable.CONNECTIVITY_STATE,
                elementType = AndroidConnectivityStateEvent::class.java,
                getOldest = dao::getOldest,
                idOf = { it.id },
                toDto = { it.toAndroidConnectivityStateEvent() },
                deleteByIds = dao::deleteByIds,
                plainUpload = { _, _, _, _ -> throw AssertionError("Malformed rows must not upload") },
            ),
        )
        assertEquals(0, failures)
        assertEquals(0, dao.count())
        assertEquals(1, db.localDataQuarantineDao().count(UploadQueueTable.CONNECTIVITY_STATE.tableName))
        val diagnostic = db.uploadDiagnosticDao().forEnrollment(server.studyId, server.participantId,
            server.sourceRandomDeviceId, "${server.id}:${server.createdAt}").single()
        assertEquals(LocalUploadModuleFamily.CONNECTIVITY.name, diagnostic.moduleFamily)
        assertEquals(LocalOperationalIssue.SAMPLE_QUARANTINED.name, diagnostic.issueCode)
    }

    @Test fun unreadableRowMovesToQuarantineWithoutAnEmptyRequest() {
        db.queueEntryData().insertEntry(QueueEntry(2_000L, 7L, "not json".toByteArray()))

        assertEquals(0, executor().run())

        assertEquals(emptyList<Int>(), usagePosts)
        assertEquals(0, db.queueEntryData().getSize())
        assertEquals(1, count("SELECT count(*) FROM local_data_quarantine"))
    }

    @Test fun acknowledgmentAndUploadCountCommitTogether() {
        db.queueEntryData().insertEntry(validEntry(1))
        db.openHelper.writableDatabase.execSQL(
            "CREATE TRIGGER fail_stats BEFORE UPDATE ON upload_stats BEGIN SELECT RAISE(ABORT, 'stats down'); END",
        )

        assertEquals(1, executor().run())

        assertEquals(listOf(1), usagePosts)
        assertEquals("server accepted, but the row must not go without the count", 1, db.queueEntryData().getSize())

        db.openHelper.writableDatabase.execSQL("DROP TRIGGER fail_stats")
        assertEquals(0, executor().run())

        assertEquals("the retry carries the same event identity", ids(posts[0]), ids(posts[1]))
        assertEquals(0, db.queueEntryData().getSize())
        assertEquals(1, count("SELECT sum(usageEventsUploaded) FROM upload_stats"))
    }

    @Test fun malformedItemDoesNotHideValidSibling() = malformedItemDoesNotHideValidSiblings(validFirst = false)

    @Test fun malformedMiddleItemDoesNotHideValidSiblings() = malformedItemDoesNotHideValidSiblings(validFirst = true)

    /** K6: malformed first and middle items are quarantined with a visible refusal; every valid sibling uploads. */
    private fun malformedItemDoesNotHideValidSiblings(validFirst: Boolean) {
        val valid = validEntry(1).data.toString(Charsets.UTF_8).removeSurrounding("[", "]")
        val malformed = valid.replace(Regex("\"timestamp\":\"[^\"]+\""),
            "\"timestamp\":\"invalid-timestamp\"")
        check(malformed != valid)
        db.queueEntryData().insertEntry(QueueEntry(3_000L, 9L,
            (if (validFirst) "[$valid,$malformed,$valid]" else "[$malformed,$valid]").toByteArray(Charsets.UTF_8)))

        assertEquals(0, executor().run())

        assertEquals(listOf(if (validFirst) 2 else 1), usagePosts)
        val quarantined = db.localDataQuarantineDao().forSource("dataQueue").single()
        assertEquals(JSONObject(malformed).toString(), JSONObject(quarantined.rawData.toString(Charsets.UTF_8)).toString())
        assertEquals(0, db.queueEntryData().getSize())
        val refusal = db.uploadDiagnosticDao().forEnrollment(server.studyId, server.participantId,
            server.sourceRandomDeviceId, "${server.id}:${server.createdAt}").single()
        assertEquals(LocalOperationalIssue.SAMPLE_QUARANTINED.name, refusal.issueCode)
    }

    @Test fun largeBacklogStopsAfterTheRunCap() {
        db.queueEntryData().insertEntries((1L..(BATCH_SIZE.toLong() * (MAX_USAGE_BATCHES_PER_RUN + 1))).map(::validEntry))
        executor().run()
        assertEquals(MAX_USAGE_BATCHES_PER_RUN, usagePosts.size)
        assertEquals(BATCH_SIZE, db.queueEntryData().getSize())
    }
}

/** The upload loop's contract: a lost response, a partial receipt or a stop never loses or duplicates an event. */
class UploadQueueContractTest : UsageUploadFixture() {
    /**
     * Thirty days offline in Santiago (every 15-minute poll queued, across the September DST start), then
     * a reconnect that drains through the run cap with outages, lost responses and short receipts, the store reopened
     * from disk before every run. Every event reaches the server with its original instant, and the queue empties.
     */
    @Test fun aMonthOfflineDrainsThroughEveryFailureLosingAndChangingNothing() {
        fun openOnDisk() = Room.databaseBuilder(context, ChronicleDb::class.java, "month-backlog")
            .allowMainThreadQueries().addCallback(UPLOAD_EVIDENCE_CALLBACK).build()
        db.close()
        db = openOnDisk()
        server = db.uploadServerDao().getById(db.uploadServerDao().insert(server.copy(id = 0)))!!
        val zone = java.time.ZoneId.of("America/Santiago")
        val start = java.time.Instant.parse("2026-08-23T04:00:00Z")
        val generated = linkedMapOf<UUID, java.time.Instant>()
        var nextRow = 0L
        for (poll in 0 until POLLS_PER_DAY * BACKLOG_DAYS) {
            val events = (0 until EVENTS_PER_POLL).map { n ->
                val at = start.plusSeconds(poll * 900L + n * 60L)
                val id = UUID.randomUUID().also { generated[it] = at }
                ExtractedUsageEvent(eventType = fromInteractionType("Activity Resumed"), appPackageName = "app.$n",
                    interactionType = "Activity Resumed", timestamp = at.atZone(zone).toOffsetDateTime(),
                    timezone = zone.id, user = "", applicationLabel = "App $n", clientEventId = id)
            }
            db.queueEntryData().insertEntries(buildUsageQueueEntries(
                events, start.toEpochMilli() + poll) { nextRow++ })
        }
        assertEquals(POLLS_PER_DAY * BACKLOG_DAYS, db.queueEntryData().getSize())
        val stored = linkedMapOf<UUID, java.time.Instant>()
        var run = 0
        fun behaviour(rows: List<ChronicleUsageEvent>): UploadReceipt {
            if (run % 7 == 1) throw IOException("offline")
            val acceptedRows = if (run % 7 == 5) rows.take(rows.size / 2) else rows
            acceptedRows.forEach {
                val id = checkNotNull(it.clientEventId)
                assertEquals("every retry keeps the event instant", generated.getValue(id), it.timestamp.toInstant())
                stored.putIfAbsent(id, it.timestamp.toInstant())
            }
            if (run % 7 == 3) throw IOException("server committed, response lost")
            return accepted(acceptedRows)
        }

        while (db.queueEntryData().getSize() > 0) {
            check(++run < MAX_DRAIN_RUNS) { "backlog still holds ${db.queueEntryData().getSize()} rows after $run runs" }
            executor(::behaviour).run()
            db.close()
            db = openOnDisk()
        }

        assertEquals(generated, stored)
        Assert.assertTrue("one POST never exceeds a batch", posts.all { it.size <= BATCH_SIZE * EVENTS_PER_POLL })
        assertEquals(0, count("SELECT count(*) FROM local_data_quarantine"))
        assertEquals(0, count("SELECT count(*) FROM upload_queue_evidence"))
    }

    @Test fun responseLostAfterServerCommitResendsTheSameIdsAndTheServerStoresEachOnce() {
        db.queueEntryData().insertEntries(listOf(validEntry(1), validEntry(2)))
        val stored = linkedSetOf<UUID>()
        var loseResponse = true
        val server = { rows: List<ChronicleUsageEvent> ->
            stored += ids(rows)
            if (loseResponse) { loseResponse = false; throw IOException("server committed, response lost") }
            accepted(rows)
        }

        assertEquals(1, executor(server).run())
        assertEquals("legacy rows get their IDs persisted before the first send", ids(posts[0]),
            queued().mapTo(linkedSetOf()) { it.clientEventId })
        // A new executor models the process restarting with only the database.
        assertEquals(0, executor(server).run())

        assertEquals(ids(posts[0]), ids(posts[1]))
        assertEquals(2, stored.size)
        assertEquals(0, db.queueEntryData().getSize())
        // K2: the lost first receipt counted nothing, so the delivered count equals the unique stored rows.
        assertEquals(stored.size, db.uploadServerDao().getById(this.server.id)!!.usageUploadSuccessCount)
    }

    /** The queue lives on disk: offline, a kill after the server committed, then a reboot lose and duplicate nothing. */
    @Test fun offlineThenKillMidUploadThenRebootResendsFromDiskAndTheServerStoresEachOnce() {
        fun openOnDisk() = Room.databaseBuilder(context, ChronicleDb::class.java, "kill-reboot-queue")
            .allowMainThreadQueries().addCallback(UPLOAD_EVIDENCE_CALLBACK).build()
        db.close()
        db = openOnDisk()
        server = db.uploadServerDao().getById(db.uploadServerDao().insert(server.copy(id = 0)))!!
        db.queueEntryData().insertEntries(listOf(validEntry(1), validEntry(2)))
        val stored = linkedSetOf<UUID>()

        executor { throw IOException("offline") }.run()
        assertEquals(2, db.queueEntryData().getSize())
        executor { rows -> stored += ids(rows); throw IOException("killed after the server committed") }.run()
        db.close()
        db = openOnDisk()
        executor { rows -> stored += ids(rows); accepted(rows) }.run()

        assertEquals("every attempt sends the same event IDs", 1, posts.map(::ids).distinct().size)
        assertEquals(2, stored.size)
        assertEquals(0, db.queueEntryData().getSize())
    }

    @Test fun shortOrMalformedReceiptDeletesNothing() {
        db.queueEntryData().insertEntries(listOf(validEntry(1), validEntry(2)))
        val receipts = listOf<(List<ChronicleUsageEvent>) -> UploadReceipt>(
            { UploadReceipt(setOf(ids(it).first()), emptySet(), 0) },
            { UploadReceipt(ids(it), ids(it), 0) },
            { UploadReceipt(setOf(UUID.randomUUID()), emptySet(), 0) },
            { UploadReceipt(emptySet(), emptySet(), 0) },
        )
        for (receipt in receipts) {
            assertEquals(1, executor(receipt).run())
            assertEquals(2, db.queueEntryData().getSize())
        }
        assertEquals(receipts.size, failures.size)
    }

    @Test fun backlogDrainsInOneRunUntilEmptyOrStopped() {
        db.queueEntryData().insertEntries((1L..BATCH_SIZE * 3L).map(::validEntry))
        assertEquals(0, executor().run())
        assertEquals(listOf(BATCH_SIZE, BATCH_SIZE, BATCH_SIZE), usagePosts)
        assertEquals(0, db.queueEntryData().getSize())

        db.queueEntryData().insertEntries((101L..100L + BATCH_SIZE * 3L).map(::validEntry))
        posts.clear()
        assertEquals(0, executor().run(stopped = { posts.isNotEmpty() }))
        assertEquals(listOf(BATCH_SIZE), usagePosts)
        assertEquals(BATCH_SIZE * 2, db.queueEntryData().getSize())
    }
}

class UploadDisabledModuleFlushTest : UsageUploadFixture() {
    @Test fun aStudyDisabledUsageModuleFlushesWithItsLifecycleSibling() {
        val usage = disableUsageEvents()
        val lifecycle = lifecycleEvent(usage)
        db.queueEntryData().insertEntry(QueueEntry(1_001, 1, JsonSerializer.serializeQueueEntry(listOf(usage, lifecycle))))

        assertEquals(0, executor().run())

        assertEquals(listOf(2), usagePosts)
        assertEquals(0, db.queueEntryData().getSize())
    }
}

class UploadRejectionRegressionTest : UsageUploadFixture() {
    @Test fun invalid400BatchQuarantinesOnlyTheRejectedRow() = assertRejectedRowIsIsolated(HttpURLConnection.HTTP_BAD_REQUEST)

    @Test fun aRowRejectedThroughTheCallAdapterIsIsolated() = assertRejectedRowIsIsolated(HttpURLConnection.HTTP_BAD_REQUEST) {
        throw ChronicleCallException("POST", "upload", rowRejectedBody, it)
    }

    @Test fun aRequestLevel400QuarantinesNothingAndKeepsTheRows() {
        db.queueEntryData().insertEntries(listOf(validEntry(1), validEntry(2)))
        // An older server refusing a zone id it does not know, or contract drift: no row-level code.
        for (body in listOf("refused", """{"code":"VALIDATION_FAILED","message":"refused"}""")) {
            failures.clear()
            assertEquals(1, executor { httpError(HttpURLConnection.HTTP_BAD_REQUEST, body) }.run())
            assertEquals(400, uploadHttpStatus(failures.single()))
        }
        assertEquals(listOf(2, 2), usagePosts)
        assertEquals(2, db.queueEntryData().getSize())
        assertEquals(0, db.localDataQuarantineDao().forSource("dataQueue").size)
    }

    /**
     * A 413 splits one queue row of four events; the server accepts the first half and the connection resets on the
     * second. Only the accepted events leave the row and the counters; the unsent siblings keep their IDs and the row
     * its capture evidence, and the retry sends exactly those siblings.
     */
    @Test fun splitSuccessThenFailureSettlesOnlyAccountedItems() {
        applyUsagePolicy(3)
        val one = JsonSerializer.deserializeQueueEntry(validEntry(1).data).single() as ExtractedUsageEvent
        val four = (0L..3L).map { one.copy(appPackageName = "app$it", clientEventId = UUID.randomUUID(),
            timestamp = one.timestamp.plusSeconds(it)) }
        db.queueEntryData().insertEntry(QueueEntry(1_001L, 1L, JsonSerializer.serializeQueueEntry(four)))
        val firstHalf = four.take(2).mapTo(linkedSetOf()) { it.clientEventId!! }
        val secondHalf = four.drop(2).mapTo(linkedSetOf()) { it.clientEventId!! }

        assertEquals(1, executor { rows ->
            when {
                rows.size > 2 -> httpError(HttpURLConnection.HTTP_ENTITY_TOO_LARGE)
                ids(rows) == firstHalf -> accepted(rows)
                else -> throw IOException("connection reset")
            }
        }.run())

        assertEquals(listOf(4, 2, 2), usagePosts)
        assertEquals("the unsent siblings stay queued with their IDs", secondHalf,
            queued().mapTo(linkedSetOf()) { it.clientEventId })
        assertEquals("only the accepted half is counted", 2, count("SELECT coalesce(sum(usageEventsUploaded), 0) FROM upload_stats"))
        assertEquals("the remaining row keeps its capture evidence", 3,
            count("SELECT settingsVersion FROM upload_queue_evidence WHERE payloadType = 'USAGE'"))
        assertEquals(0, db.localDataQuarantineDao().forSource("dataQueue").size)

        posts.clear()
        assertEquals(0, executor().run())
        assertEquals("the retry sends exactly the siblings", listOf(secondHalf), posts.map(::ids))
        assertEquals(0, db.queueEntryData().getSize())
        assertEquals(4, count("SELECT sum(usageEventsUploaded) FROM upload_stats"))
    }

    /** The same split, with the acknowledgment transaction failing: nothing settles and every event is resent. */
    @Test fun splitWhoseAcknowledgmentFailsKeepsEveryEvent() {
        val one = JsonSerializer.deserializeQueueEntry(validEntry(1).data).single() as ExtractedUsageEvent
        val four = (0L..3L).map { one.copy(appPackageName = "app$it", clientEventId = UUID.randomUUID()) }
        db.queueEntryData().insertEntry(QueueEntry(1_001L, 1L, JsonSerializer.serializeQueueEntry(four)))
        db.openHelper.writableDatabase.execSQL(
            "CREATE TRIGGER fail_stats BEFORE UPDATE ON upload_stats BEGIN SELECT RAISE(ABORT, 'stats down'); END")
        val all = four.mapTo(linkedSetOf()) { it.clientEventId!! }

        assertEquals(1, executor { rows -> if (rows.size > 2) httpError(HttpURLConnection.HTTP_ENTITY_TOO_LARGE) else accepted(rows) }.run())
        assertEquals(all, queued().mapTo(linkedSetOf()) { it.clientEventId })

        db.openHelper.writableDatabase.execSQL("DROP TRIGGER fail_stats")
        posts.clear()
        assertEquals(0, executor().run())
        assertEquals(all, posts.flatMap(::ids).toSet())
        assertEquals(0, db.queueEntryData().getSize())
    }

    @Test fun oversizedBatchIsSplitUntilItFitsWithoutBlockingLaterRows() {
        db.queueEntryData().insertEntries((1L..4L).map(::validEntry))
        executor { rows -> if (rows.size > 1) httpError(HttpURLConnection.HTTP_ENTITY_TOO_LARGE) else accepted(rows) }.run()
        assertEquals(listOf(4, 2, 1, 1, 2, 1, 1), usagePosts)
        assertEquals(0, db.queueEntryData().getSize())
    }
}

private const val BACKLOG_DAYS = 30
private const val POLLS_PER_DAY = 96
private const val EVENTS_PER_POLL = 8

/** 2,880 queued polls drain 50 per run; four runs in seven succeed, so about 101 runs empty the queue. */
private const val MAX_DRAIN_RUNS = 150
