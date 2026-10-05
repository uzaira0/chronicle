package com.openlattice.chronicle.services.upload

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.openlattice.chronicle.android.ChronicleUsageEvent
import com.openlattice.chronicle.api.ChronicleStudyApi
import com.openlattice.chronicle.models.ExtractedUsageEvent
import com.openlattice.chronicle.preferences.EncryptedPrefsHelper
import com.openlattice.chronicle.serialization.JsonSerializer
import com.openlattice.chronicle.services.crypto.EncryptionSettingStore
import com.openlattice.chronicle.study.StudyEncryptionSetting
import com.openlattice.chronicle.storage.ChronicleDb
import com.openlattice.chronicle.storage.QueueEntry
import com.openlattice.chronicle.storage.UploadServerEntity
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

    @Before fun setUp() {
        EncryptedPrefsHelper::class.java.getDeclaredField("instance").apply { isAccessible = true }
            .set(EncryptedPrefsHelper, context.getSharedPreferences("executor-test", Context.MODE_PRIVATE))
        db = Room.inMemoryDatabaseBuilder(context, ChronicleDb::class.java).allowMainThreadQueries().build()
        val id = db.uploadServerDao().insert(UploadServerEntity(
            name = "test", url = "https://localhost", studyId = "11111111-1111-1111-1111-111111111111",
            participantId = "p1", sourceDeviceId = "d1", createdAt = "2026-09-01T00:00:00Z",
        ))
        server = db.uploadServerDao().getById(id)!!
        // A known plaintext policy; an unknown one fails closed before any post.
        EncryptionSettingStore.of(context).put(UUID.fromString(server.studyId), StudyEncryptionSetting(enabled = false))
    }

    @After fun tearDown() = db.close()

    private fun executor() = UploadExecutor(context, db, emptyMap(), studyApiFor = {
        Proxy.newProxyInstance(ChronicleStudyApi::class.java.classLoader, arrayOf(ChronicleStudyApi::class.java)) { _, method, args ->
            check(method.name == "uploadAndroidUsageEventData") { "unexpected call ${method.name}" }
            val size = (args[4] as com.openlattice.chronicle.android.ChronicleData).size
            usagePosts += size
            size
        } as ChronicleStudyApi
    })

    private fun cursor() = db.uploadServerDao().getById(server.id)!!.let { it.lastUploadedTimestamp to it.lastUploadedQueueId }

    private fun count(sql: String) = db.openHelper.readableDatabase.query(sql).use { it.moveToFirst(); it.getInt(0) }

    private fun validEntry(id: Long) = QueueEntry(1_000L + id, id, JsonSerializer.serializeQueueEntry(listOf(
        ExtractedUsageEvent(
            appPackageName = "a.b.c", interactionType = "Activity Resumed",
            timestamp = OffsetDateTime.parse("2026-09-24T10:00:00Z"), timezone = "UTC", user = "u", applicationLabel = "abc",
        ),
    )))

    @Test fun heldUsageInMixedQueueIsRetainedWhileActiveLifecycleUploads() {
        val usage = JsonSerializer.deserializeQueueEntry(validEntry(1).data).single() as ExtractedUsageEvent
        val lifecycle = usage.copy(appPackageName = com.openlattice.chronicle.constants.ANDROID_SYSTEM_PACKAGE,
            interactionType = com.openlattice.chronicle.constants.INTERACTION_BATTERY_CHARGING, user = "")
        db.collectionModuleStateDao().upsertAll(listOf(
            com.openlattice.chronicle.storage.CollectionModuleStateEntity("usage_events", false, "ACCEPTED", 0, false, 2, null, "hold_pending"),
            com.openlattice.chronicle.storage.CollectionModuleStateEntity("device_lifecycle", true, "ACCEPTED", 0, false, 2, null, null)))
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
}
