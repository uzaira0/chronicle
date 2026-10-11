package com.openlattice.chronicle.services.upload

import okhttp3.ResponseBody.Companion.toResponseBody
import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.openlattice.chronicle.android.ChronicleUsageEvent
import com.openlattice.chronicle.api.ChronicleStudyApi
import com.openlattice.chronicle.collection.CollectionDataDisposition
import com.openlattice.chronicle.collection.CollectionModuleId
import com.openlattice.chronicle.collection.CollectionUploadEvidence
import com.openlattice.chronicle.collection.state.ParticipantDecision
import com.openlattice.chronicle.collection.state.eraseSharedQueueModule
import com.openlattice.chronicle.collection.device.toAndroidConnectivityStateEvent
import com.openlattice.chronicle.models.ExtractedUsageEvent
import com.openlattice.chronicle.preferences.EncryptedPrefsHelper
import com.openlattice.chronicle.serialization.JsonSerializer
import com.openlattice.chronicle.services.crypto.EncryptionSettingStore
import com.openlattice.chronicle.study.StudyEncryptionSetting
import com.openlattice.chronicle.storage.ChronicleDb
import com.openlattice.chronicle.storage.CollectionModuleStateEntity
import com.openlattice.chronicle.storage.LifecycleStateEntity
import com.openlattice.chronicle.storage.QUEUE_CAPTURE_VERSION_KEY
import com.openlattice.chronicle.storage.UPLOAD_EVIDENCE_CALLBACK
import com.openlattice.chronicle.storage.QueueEntry
import com.openlattice.chronicle.storage.UploadServerEntity
import com.openlattice.chronicle.collection.core.NoOpCollectionLog
import com.openlattice.chronicle.crypto.EncryptedEnvelope
import com.openlattice.chronicle.services.sinks.UploadEncryptionFixture
import com.openlattice.chronicle.collection.upload.runCombinedUploadCore
import com.openlattice.chronicle.services.crypto.FileSealedEnvelopeStore
import com.openlattice.chronicle.services.crypto.PayloadSealer
import java.lang.reflect.Proxy
import java.time.OffsetDateTime
import java.util.UUID
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class UploadExecutorRegressionTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private lateinit var db: ChronicleDb
    private lateinit var server: UploadServerEntity
    private val usagePosts = mutableListOf<Int>()
    private val usageVersions = mutableListOf<Int?>()

    @Before fun setUp() {
        EncryptedPrefsHelper::class.java.getDeclaredField("instance").apply { isAccessible = true }
            .set(EncryptedPrefsHelper, context.getSharedPreferences("executor-test", Context.MODE_PRIVATE))
        EncryptedPrefsHelper.getEncryptedPrefs(context).edit().clear().commit()
        db = Room.inMemoryDatabaseBuilder(context, ChronicleDb::class.java).allowMainThreadQueries()
            .addCallback(UPLOAD_EVIDENCE_CALLBACK).build()
        val id = db.uploadServerDao().insert(UploadServerEntity(
            name = "test", url = "https://localhost", studyId = "11111111-1111-1111-1111-111111111111",
            participantId = "p1", sourceDeviceId = "d1", createdAt = "2026-09-01T00:00:00Z",
        ))
        server = db.uploadServerDao().getById(id)!!
        // A known plaintext policy; an unknown one fails closed before any post.
        EncryptionSettingStore.of(context).put(UUID.fromString(server.studyId), StudyEncryptionSetting(enabled = false))
    }

    @After fun tearDown() {
        db.close()
        EncryptedPrefsHelper::class.java.getDeclaredField("instance").apply { isAccessible = true }.set(EncryptedPrefsHelper, null)
    }

    private fun executor(accept: (Int) -> Int = { it }) = UploadExecutor(context, db, emptyMap(), studyApiFor = {
        Proxy.newProxyInstance(ChronicleStudyApi::class.java.classLoader, arrayOf(ChronicleStudyApi::class.java)) { _, method, args ->
            check(method.name == "uploadAndroidUsageEventData") { "unexpected call ${method.name}" }
            val size = (args[4] as com.openlattice.chronicle.android.ChronicleData).size
            usagePosts += size
            usageVersions += args[5] as Int?
            accept(size)
        } as ChronicleStudyApi
    })

    private fun cursor() = db.uploadServerDao().getById(server.id)!!.let { it.lastUploadedTimestamp to it.lastUploadedQueueId }

    private fun count(sql: String) = db.openHelper.readableDatabase.query(sql).use { it.moveToFirst(); it.getInt(0) }

    private fun validEntry(id: Long, activityClass: String? = null) = QueueEntry(1_000L + id, id, JsonSerializer.serializeQueueEntry(listOf(
        ExtractedUsageEvent(
            appPackageName = "a.b.c", interactionType = "Activity Resumed",
            timestamp = OffsetDateTime.parse("2026-09-24T10:00:00Z"), timezone = "UTC", user = "u", applicationLabel = "abc",
            activityClass = activityClass,
        ),
    )))

    private fun applyUsagePolicy(version: Int, enabled: Boolean = true) {
        db.collectionModuleStateDao().upsert(CollectionModuleStateEntity(CollectionModuleId.USAGE_EVENTS.id,
            enabled, ParticipantDecision.ACCEPTED.name, 0, false, version, null,
            if (enabled) null else CollectionDataDisposition.FLUSH_THEN_STOP.id))
    }

    private fun overrideCaptureVersion(version: Int) {
        db.lifecycleStateDao().upsert(LifecycleStateEntity(QUEUE_CAPTURE_VERSION_KEY, version.toString(), 0))
    }

    @Test fun interleavedCaptureVersionsUploadEveryRowBeforeCursorCleanup() {
        val queue = db.queueEntryData()
        applyUsagePolicy(1)
        queue.insertEntry(validEntry(1))
        applyUsagePolicy(2)
        queue.insertEntry(validEntry(2))
        overrideCaptureVersion(1)
        queue.insertEntry(validEntry(3))

        executor().uploadForServer(server)
        queue.deleteEntriesBeforeOrAt(cursor().first, cursor().second)

        assertEquals(listOf(1, 1, 1), usagePosts)
        assertEquals(listOf(1, 2, 1), usageVersions)
        assertEquals(1_003L to 3L, cursor())
        assertEquals(0, queue.getSize())
    }

    @Test fun cutoffAcknowledgmentDrainsMixedCapturesAndTheOlderBacklogBehindThem() {
        applyUsagePolicy(1)
        val cutoff = OffsetDateTime.parse("2026-09-24T10:01:00Z")
        val before = JsonSerializer.deserializeQueueEntry(validEntry(1).data).filterIsInstance<ExtractedUsageEvent>().single()
        val queue = db.queueEntryData()
        queue.insertEntry(QueueEntry(1_001L, 1L, JsonSerializer.serializeQueueEntry(listOf(before,
            before.copy(timestamp = cutoff.plusSeconds(1))))))
        for (id in 2L..11L) queue.insertEntry(validEntry(id))
        val stored = mutableListOf<ChronicleUsageEvent>()
        var discarded = 0
        val api = Proxy.newProxyInstance(ChronicleStudyApi::class.java.classLoader, arrayOf(ChronicleStudyApi::class.java)) { _, method, args ->
            check(method.name == "uploadAndroidUsageEventData")
            val submitted = (args[4] as com.openlattice.chronicle.android.ChronicleData).filterIsInstance<ChronicleUsageEvent>()
            stored += submitted.filter { it.timestamp.isBefore(cutoff) }
            discarded += submitted.count { !it.timestamp.isBefore(cutoff) }
            // The server acknowledges every handled row, including its permanent cutoff discards.
            submitted.size
        } as ChronicleStudyApi
        val uploader = UploadExecutor(context, db, emptyMap(), studyApiFor = { api })
        uploader.uploadForServer(server)
        queue.deleteEntriesBeforeOrAt(cursor().first, cursor().second)
        assertEquals(11, stored.size)
        assertEquals(1, discarded)
        assertEquals(1_011L to 11L, cursor())
        assertEquals(0, queue.getSize())
        assertEquals(0, count("SELECT count(*) FROM pending_upload_batches"))
        uploader.uploadForServer(db.uploadServerDao().getById(server.id)!!)
        assertEquals("No discarded capture is retried after the queue drains", 1, discarded)
    }

    @Test fun forbiddenCollectionResponsesBackOffWithoutPermanentlyBlockingAnyFamily() {
        val forbidden = retrofit2.HttpException(retrofit2.Response.error<Any>(403, "collection stopped".toResponseBody()))
        for (family in LocalUploadModuleFamily.entries) {
            UploadRetryGate.recordFailure(context, server, family, forbidden, db)
            org.junit.Assert.assertFalse(UploadRetryGate.shouldAttempt(context, server, family, db))
            org.junit.Assert.assertTrue(UploadRetryGate.shouldAttempt(context, server, family, db,
                System.currentTimeMillis() + 900_001))
        }
        org.junit.Assert.assertFalse(UploadRetryGate.isBlocked(context, server, db))
        val unauthorized = retrofit2.HttpException(retrofit2.Response.error<Any>(401, "invalid credential".toResponseBody()))
        UploadRetryGate.recordFailure(context, server, LocalUploadModuleFamily.USAGE_LIFECYCLE, unauthorized, db)
        org.junit.Assert.assertFalse(UploadRetryGate.shouldAttempt(context, server, LocalUploadModuleFamily.USAGE_LIFECYCLE,
            db, System.currentTimeMillis() + 900_001))
        org.junit.Assert.assertTrue(UploadRetryGate.isBlocked(context, server, db))
    }

    @Test fun activityRedactionPreservesHistoricalVersionForDisabledUsageFlush() {
        val queue = db.queueEntryData()
        applyUsagePolicy(1)
        queue.insertEntry(validEntry(1, activityClass = "MainActivity"))
        applyUsagePolicy(2, enabled = false)

        db.runInTransaction { eraseSharedQueueModule(db, CollectionModuleId.IN_APP_ACTIVITY_CLASS) }

        val retained = JsonSerializer.deserializeQueueEntry(queue.getNextEntries(1).single().data).single() as ExtractedUsageEvent
        org.junit.Assert.assertNull(retained.activityClass)
        executor().uploadForServer(server)
        assertEquals(listOf(1), usagePosts)
        assertEquals(listOf(1), usageVersions)
    }

    @Test fun replayedUsageRetainsKnownAndUnknownCaptureVersionsAfterPolicyChanges() {
        val queue = db.queueEntryData()
        val rows = listOf(validEntry(1), validEntry(2))
        applyUsagePolicy(1)
        queue.insertEntry(rows.first())
        overrideCaptureVersion(CollectionUploadEvidence.UNKNOWN_SETTINGS_VERSION)
        queue.insertEntry(rows.last())
        quarantineRejectedRows(db, server, com.openlattice.chronicle.crypto.EncryptedPayloadType.USAGE,
            UNPROCESSABLE_PAYLOAD_STATUS, "dataQueue", rows, { "${it.writeTimestamp}:${it.id}" },
            remove = { ids -> queue.deleteEntries(rows.filter { "${it.writeTimestamp}:${it.id}" in ids }) },
            rawOf = { it.data })
        val quarantine = db.localDataQuarantineDao()
        val old = OffsetDateTime.now(java.time.ZoneOffset.UTC).minusDays(2).toString()
        quarantine.forSource("dataQueue").forEach { quarantine.update(it.copy(createdAt = old)) }
        applyUsagePolicy(2, enabled = false)

        executor().uploadForServer(server)
        queue.deleteEntriesBeforeOrAt(cursor().first, cursor().second)

        assertEquals(listOf(1, 1), usagePosts)
        assertEquals(listOf(1, null), usageVersions)
        assertEquals(0, quarantine.count("dataQueue"))
        assertEquals(0, queue.getSize())
    }

    @Test fun malformedExpansionUsesItsPayloadFamilyWithoutAnEmptyUpload() {
        val dao = db.connectivityStateSampleDao()
        dao.insertAll(listOf(com.openlattice.chronicle.storage.ConnectivityStateSampleEntry(
            "malformed", "not-a-date", "UTC", "SNAPSHOT", "WIFI", true, false, true,
        )))
        val failures = com.openlattice.chronicle.collection.device.ExpansionUploadWorkerDelegate(context, db).uploadStream(
            servers = listOf(server), getOldest = dao::getOldest, getByIds = dao::getByIds, idOf = { it.id },
            toDto = { it.toAndroidConnectivityStateEvent() },
            payloadType = com.openlattice.chronicle.crypto.EncryptedPayloadType.CONNECTIVITY_STATE,
            deleteByIds = dao::deleteByIds, plainUpload = { _, _, _, _ -> org.junit.Assert.fail("Malformed rows must not upload") },
            label = "connectivity_state",
        )
        assertEquals(0, failures)
        assertEquals(0, dao.count())
        assertEquals(1, db.localDataQuarantineDao().count("connectivity_state"))
        val diagnostic = db.uploadDiagnosticDao().forEnrollment(server.studyId, server.participantId,
            server.sourceDeviceId, "${server.id}:${server.createdAt}").single()
        assertEquals(LocalUploadModuleFamily.CONNECTIVITY.name, diagnostic.moduleFamily)
        assertEquals(LocalOperationalIssue.SAMPLE_QUARANTINED.name, diagnostic.issueCode)
    }

    private fun holdUsageEvents(): ExtractedUsageEvent {
        val usage = JsonSerializer.deserializeQueueEntry(validEntry(1).data).single() as ExtractedUsageEvent
        db.collectionModuleStateDao().upsertAll(listOf(
            com.openlattice.chronicle.storage.CollectionModuleStateEntity("usage_events", false, "ACCEPTED", 0, false, 2, null, "hold_pending"),
            com.openlattice.chronicle.storage.CollectionModuleStateEntity("device_lifecycle", true, "ACCEPTED", 0, false, 2, null, null)))
        return usage
    }

    private fun lifecycleEvent(usage: ExtractedUsageEvent) = usage.copy(
        appPackageName = com.openlattice.chronicle.constants.ANDROID_SYSTEM_PACKAGE,
        interactionType = com.openlattice.chronicle.constants.INTERACTION_BATTERY_CHARGING, user = "")

    @Test fun heldUsageInMixedQueueIsRetainedWhileActiveLifecycleUploads() {
        val usage = holdUsageEvents()
        val lifecycle = lifecycleEvent(usage)
        db.queueEntryData().insertEntry(QueueEntry(1_001, 1, JsonSerializer.serializeQueueEntry(listOf(usage, lifecycle))))
        executor().uploadForServer(server)
        assertEquals(listOf(1), usagePosts)
        assertEquals(0L, cursor().first)
        val retained = db.queueEntryData().getNextEntries(10)
        assertEquals(1, retained.size)
        assertEquals(listOf(usage), JsonSerializer.deserializeQueueEntry(retained.single().data))
        executor().uploadForServer(db.uploadServerDao().getById(server.id)!!)
        assertEquals("held-only retry must not send or acknowledge", listOf(1), usagePosts)
        db.openHelper.writableDatabase.execSQL("UPDATE collection_module_state SET serverEnabled = 1 WHERE moduleId = 'usage_events'")
        executor().uploadForServer(db.uploadServerDao().getById(server.id)!!)
        assertEquals(listOf(1, 1), usagePosts)
        assertEquals(1_001L, cursor().first)
        db.collectionModuleStateDao().upsertAll(listOf(com.openlattice.chronicle.storage.CollectionModuleStateEntity(
            "battery_telemetry", false, "ACCEPTED", 0, false, 2, null, "hold_pending")))
        db.batterySampleDao().insertAll(listOf(com.openlattice.chronicle.storage.BatterySampleEntry(
            "held-battery", "2026-10-01T00:00:00Z", "UTC", 50, "CHARGING", "USB", 250, 4000, "GOOD")))
        assertEquals(0, com.openlattice.chronicle.collection.battery.BatteryUploadWorkerDelegate(context, db).execute())
        assertEquals(1, db.batterySampleDao().count())
    }

    @Test fun retryingLaterPendingBatchDoesNotDeleteEarlierHeldUsage() {
        val before = cursor()
        val usage = holdUsageEvents()
        val held = (1L..BATCH_SIZE.toLong()).map(::validEntry)
        val later = QueueEntry(1_000L + BATCH_SIZE + 1, BATCH_SIZE.toLong() + 1,
            JsonSerializer.serializeQueueEntry(listOf(lifecycleEvent(usage))))
        db.queueEntryData().insertEntries(held + later)
        assertThrows(Exception::class.java) {
            executor { throw java.io.IOException("response lost") }.uploadForServer(server)
        }

        executor().uploadForServer(db.uploadServerDao().getById(server.id)!!)
        val (timestamp, id) = cursor()
        db.queueEntryData().deleteEntriesBeforeOrAt(timestamp, id)

        assertEquals(before, cursor())
        assertEquals(held.map { it.id }, db.queueEntryData().getNextEntries(BATCH_SIZE).map { it.id })
        assertEquals(listOf(1, 1), usagePosts)
    }

    @Test
    fun fullyQuarantinedBatchAdvancesWithoutSubmittingAnEmptyRequest() {
        db.queueEntryData().insertEntry(QueueEntry(2_000L, 7L, "not json".toByteArray()))

        executor().uploadForServer(server)

        assertEquals(emptyList<Int>(), usagePosts)
        assertEquals(2_000L to 7L, cursor())
        assertEquals(1, count("SELECT count(*) FROM local_data_quarantine"))
    }

    @Test
    fun usageCursorAndUploadCountCommitTogether() {
        db.queueEntryData().insertEntry(validEntry(1))
        val before = cursor()
        db.openHelper.writableDatabase.execSQL(
            "CREATE TRIGGER fail_stats BEFORE UPDATE ON upload_stats BEGIN SELECT RAISE(ABORT, 'stats down'); END",
        )

        assertThrows(Exception::class.java) { executor().uploadForServer(server) }

        assertEquals(listOf(1), usagePosts)
        assertEquals("server accepted, but cursor must not move without the count", before, cursor())

        db.openHelper.writableDatabase.execSQL("DROP TRIGGER fail_stats")
        executor().uploadForServer(server)

        assertEquals(1_001L to 1L, cursor())
        assertEquals(1, count("SELECT sum(usageEventsUploaded) FROM upload_stats"))
    }

    @Test
    fun legacyQueueItemsAreParsedAndQuarantinedIndividually() {
        val quarantined = mutableListOf<Pair<Int, String>>()
        val uploaded = mapLegacyQueueItems(listOf("first", "bad", "last"),
            mapItem = { if (it == "bad") error("missing field") else listOf(it) },
            onMalformed = { index, raw -> quarantined += index to raw },
        )
        assertEquals(listOf("first", "last"), uploaded)
        assertEquals(listOf(1 to "bad"), quarantined)
        assertThrows(IllegalStateException::class.java) {
            mapLegacyQueueItems(listOf("bad", "last"),
                mapItem = { if (it == "bad") error("missing field") else listOf(it) },
                onMalformed = { _, _ -> error("quarantine failed") },
            )
        }
    }

    @Test
    fun malformedModernItemDoesNotHideValidModernSibling() {
        val valid = validEntry(1).data.toString(Charsets.UTF_8).removeSurrounding("[", "]")
        val malformed = valid.replace(Regex("\"timestamp\":\"[^\"]+\""),
            "\"timestamp\":\"invalid-timestamp\"")
        check(malformed != valid)
        db.queueEntryData().insertEntry(QueueEntry(3_000L, 9L,
            "[$malformed,$valid]".toByteArray(Charsets.UTF_8)))

        executor().uploadForServer(server)

        assertEquals(listOf(1), usagePosts)
        assertEquals(1, count("SELECT count(*) FROM local_data_quarantine"))
        assertEquals(3_000L to 9L, cursor())
    }
    @Test fun largeBacklogStopsAfterTheRunCapAndLeavesTheNextStreamTimeToRun() {
        val batchSize = BATCH_SIZE
        db.queueEntryData().insertEntries((1L..(batchSize.toLong() * (MAX_USAGE_BATCHES_PER_RUN + 1))).map(::validEntry))
        executor().uploadForServer(server)
        assertEquals(MAX_USAGE_BATCHES_PER_RUN, usagePosts.size)
        assertEquals(1_000L + batchSize * MAX_USAGE_BATCHES_PER_RUN, cursor().first)
        var sensorRan = false
        runCombinedUploadCore(0,
            runUsageUpload = { executor().uploadForServer(db.uploadServerDao().getById(server.id)!!); 0 },
            runSensorUpload = { sensorRan = true; 0 }, cleanupStats = {},
            log = NoOpCollectionLog)
        org.junit.Assert.assertTrue(sensorRan)
    }

    @Test fun rejectedRowIsRecoverableAndLaterValidRowsUploadWithoutCredentialChanges() {
        val first = validEntry(1)
        db.queueEntryData().insertEntries(listOf(first, validEntry(2)))
        UploadRetryGate.reduceBatchLimit(context, server, LocalUploadModuleFamily.USAGE_LIFECYCLE, 2)
        var reject = true
        executor { count ->
            if (reject) {
                reject = false
                throw retrofit2.HttpException(retrofit2.Response.error<Any>(422,
                    "rejected".toResponseBody()))
            }
            count
        }.uploadForServer(server)
        assertEquals(listOf(1, 1), usagePosts)
        org.junit.Assert.assertArrayEquals(first.data, db.localDataQuarantineDao().forSource("dataQueue").single().rawData)
        assertEquals(1_002L to 2L, cursor())
    }

    @Test fun invalid400BatchQuarantinesOnlyTheRejectedRow() = assertRejectedRowIsIsolated(java.net.HttpURLConnection.HTTP_BAD_REQUEST)

    @Test fun invalid422BatchQuarantinesOnlyTheRejectedRow() = assertRejectedRowIsIsolated(UNPROCESSABLE_PAYLOAD_STATUS)

    @Test fun isolatingRejectedRowRestoresNormalBatchSize() {
        assertRejectedRowIsIsolated(UNPROCESSABLE_PAYLOAD_STATUS)
        db.queueEntryData().insertEntries((3L..BATCH_SIZE.toLong() + 2).map(::validEntry))

        executor().uploadForServer(db.uploadServerDao().getById(server.id)!!)

        assertEquals(listOf(2, 1, 1, BATCH_SIZE), usagePosts)
        assertEquals(BATCH_SIZE, UploadRetryGate.batchLimit(context, server,
            LocalUploadModuleFamily.USAGE_LIFECYCLE, BATCH_SIZE))
    }

    private fun assertRejectedRowIsIsolated(status: Int) {
        val first = validEntry(1)
        val last = validEntry(2)
        db.queueEntryData().insertEntries(listOf(first, last))
        var rejectedSingleton = false
        executor { count ->
            if (count > 1 || !rejectedSingleton) {
                if (count == 1) rejectedSingleton = true
                throw retrofit2.HttpException(retrofit2.Response.error<Any>(status, "invalid row".toResponseBody()))
            }
            count
        }.uploadForServer(server)
        assertEquals(listOf(2, 1, 1), usagePosts)
        org.junit.Assert.assertArrayEquals(first.data, db.localDataQuarantineDao().forSource("dataQueue").single().rawData)
        assertEquals(status, db.uploadDiagnosticDao().forEnrollment(server.studyId, server.participantId,
            server.sourceDeviceId, "${server.id}:${server.createdAt}").single().httpStatus)
        assertEquals(last.writeTimestamp to last.id, cursor())
    }

    @Test fun rejectedRowsReplayAfterServerRecoveryWhileMalformedAndOtherEnrollmentRowsStayQuarantined() {
        assertRejectedRowIsIsolated(java.net.HttpURLConnection.HTTP_BAD_REQUEST)
        val quarantine = db.localDataQuarantineDao()
        val rejected = quarantine.forSource("dataQueue").single()
        val old = OffsetDateTime.now(java.time.ZoneOffset.UTC).minusDays(2).toString()
        quarantine.update(rejected.copy(createdAt = old))
        quarantine.insert(rejected.copy(id = "parse-failure", sourceId = "${rejected.sourceId}:parse",
            reason = LocalOperationalIssue.SAMPLE_QUARANTINED.name, createdAt = old))
        quarantine.insert(rejected.copy(id = "previous-enrollment", sourceId = "old:${rejected.sourceId}", createdAt = old))

        executor().uploadForServer(db.uploadServerDao().getById(server.id)!!)

        assertEquals(listOf(2, 1, 1, 1), usagePosts)
        assertEquals(setOf("parse-failure", "previous-enrollment"), quarantine.forSource("dataQueue").map { it.id }.toSet())
        org.junit.Assert.assertTrue(cursor().first > 1_002L)
    }

    @Test fun sensorReplayPreservesQuarantineOnWriteFailureAndRestoresItsCaptureVersion() {
        val dao = db.sensorSampleDao()
        val type = com.openlattice.chronicle.crypto.EncryptedPayloadType.SENSOR
        val sample = com.openlattice.chronicle.storage.SensorSampleEntry(
            "rejected-sensor", "accelerometer", "2026-10-01T00:00:00Z", "UTC", 1f, 2f, 3f, null, 3)
        applyUsagePolicy(1)
        dao.insertAll(listOf(sample))
        quarantineRejectedRows(db, server, type, UNPROCESSABLE_PAYLOAD_STATUS,
            "sensor_samples", listOf(sample), { it.id }, dao::deleteByIds)
        val quarantine = db.localDataQuarantineDao()
        val rejected = quarantine.forSource("sensor_samples").single()
        quarantine.update(rejected.copy(reason = rejected.reason.replace(
            com.openlattice.chronicle.collection.state.currentAppVersion(), "previous-app")))
        val sql = db.openHelper.writableDatabase
        sql.execSQL("CREATE TRIGGER fail_sensor_insert BEFORE INSERT ON sensor_samples " +
            "BEGIN SELECT RAISE(ABORT, 'synthetic write failure'); END")

        assertThrows(android.database.sqlite.SQLiteException::class.java) { replayRejectedUploadRows(db, server, type) }
        sql.execSQL("DROP TRIGGER fail_sensor_insert")
        assertEquals(1, quarantine.count("sensor_samples"))
        assertEquals(0, dao.count())

        replayRejectedUploadRows(db, server, type)

        assertEquals(listOf(sample), dao.getByIds(listOf(sample.id)))
        assertEquals(1, db.uploadQueueEvidenceDao().get(type.name, listOf(sample.id)).single().settingsVersion)
        assertEquals(0, quarantine.count("sensor_samples"))
    }

    @Test fun rejectedTelemetryReplaysAfterAppUpdateWithItsOriginalIdentity() {
        val dao = db.connectivityStateSampleDao()
        val sample = com.openlattice.chronicle.storage.ConnectivityStateSampleEntry(
            "rejected", "2026-10-01T00:00:00Z", "UTC", "SNAPSHOT", "WIFI", true, false, true)
        applyUsagePolicy(1)
        dao.insertAll(listOf(sample))
        quarantineRejectedRows(db, server, com.openlattice.chronicle.crypto.EncryptedPayloadType.CONNECTIVITY_STATE,
            UNPROCESSABLE_PAYLOAD_STATUS, "connectivity_state", listOf(sample), { it.id }, dao::deleteByIds)
        val quarantine = db.localDataQuarantineDao()
        val rejected = quarantine.forSource("connectivity_state").single()
        assertEquals(1, rejected.settingsVersion)
        applyUsagePolicy(2, enabled = false)
        replayRejectedUploadRows(db, server, com.openlattice.chronicle.crypto.EncryptedPayloadType.CONNECTIVITY_STATE)
        assertEquals(0, dao.count())
        quarantine.update(rejected.copy(reason = rejected.reason.replace(
            com.openlattice.chronicle.collection.state.currentAppVersion(), "previous-app")))

        replayRejectedUploadRows(db, server, com.openlattice.chronicle.crypto.EncryptedPayloadType.CONNECTIVITY_STATE)

        assertEquals(listOf(sample), dao.getByIds(listOf(sample.id)))
        assertEquals(1, db.uploadQueueEvidenceDao().get(
            com.openlattice.chronicle.crypto.EncryptedPayloadType.CONNECTIVITY_STATE.name, listOf(sample.id)).single().settingsVersion)
        assertEquals(0, quarantine.count("connectivity_state"))
    }

    @Test fun oversizedBatchIsSplitUntilItFitsWithoutBlockingLaterRows() {
        db.queueEntryData().insertEntries((1L..4L).map(::validEntry))
        executor { count ->
            if (count > 1) throw retrofit2.HttpException(retrofit2.Response.error<Any>(413,
                "too large".toResponseBody()))
            count
        }.uploadForServer(server)
        assertEquals(listOf(4, 2, 1, 3, 1), usagePosts)
        executor().uploadForServer(db.uploadServerDao().getById(server.id)!!)
        assertEquals(1_004L to 4L, cursor())
    }

    @Test fun lostEncryptedResponseAndNewRowsRetryOnlyTheOriginalRowsAfterProcessDeath() {
        val setting = UploadEncryptionFixture.setting
        EncryptionSettingStore.of(context).put(UUID.fromString(server.studyId), setting)
        val dir = java.nio.file.Files.createTempDirectory("envelope-retry").toFile()
        val previous = PayloadSealer.sealedEnvelopeStore
        val accepted = linkedMapOf<String, EncryptedEnvelope>()
        var loseResponse = true
        val api = Proxy.newProxyInstance(
            ChronicleStudyApi::class.java.classLoader, arrayOf(ChronicleStudyApi::class.java),
        ) { _, method, args ->
            check(method.name == "uploadAndroidEncryptedData")
            @Suppress("UNCHECKED_CAST") val envelopes = args[4] as List<EncryptedEnvelope>
            val envelope = envelopes.single()
            accepted[JsonSerializer.toJson(envelope)] = envelope
            if (loseResponse) { loseResponse = false; throw java.io.IOException("response lost") }
            1
        } as ChronicleStudyApi
        try {
            PayloadSealer.sealedEnvelopeStore = FileSealedEnvelopeStore(dir)
            db.queueEntryData().insertEntry(validEntry(1))
            val uploader = UploadExecutor(context, db, emptyMap(), { api })
            assertThrows(Exception::class.java) { uploader.uploadForServer(server) }
            db.queueEntryData().insertEntry(validEntry(2))
            PayloadSealer.sealedEnvelopeStore = FileSealedEnvelopeStore(dir)
            uploader.uploadForServer(db.uploadServerDao().getById(server.id)!!)
            assertEquals(2, accepted.size)
            assertEquals(2, accepted.values.sumOf { envelope ->
                org.json.JSONArray(String(UploadEncryptionFixture.decrypt(
                    envelope, UUID.fromString(server.studyId), server.participantId))).length()
            })
            assertEquals(1_002L to 2L, cursor())
        } finally {
            PayloadSealer.sealedEnvelopeStore = previous
            dir.deleteRecursively()
        }
    }

}
