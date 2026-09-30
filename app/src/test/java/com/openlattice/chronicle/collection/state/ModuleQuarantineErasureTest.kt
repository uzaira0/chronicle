package com.openlattice.chronicle.collection.state

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.openlattice.chronicle.collection.CollectionDataDisposition
import com.openlattice.chronicle.collection.CollectionModuleId
import com.openlattice.chronicle.constants.ANDROID_SYSTEM_PACKAGE
import com.openlattice.chronicle.constants.INTERACTION_BATTERY_LOW
import com.openlattice.chronicle.preferences.EncryptedPrefsHelper
import com.openlattice.chronicle.models.ExtractedUsageEvent
import com.openlattice.chronicle.serialization.JsonSerializer
import com.openlattice.chronicle.services.release.purgeRestrictedPlayRows
import com.openlattice.chronicle.storage.ChronicleDb
import com.openlattice.chronicle.storage.LocalDataQuarantineEntity
import com.openlattice.chronicle.storage.QueueEntry
import java.time.OffsetDateTime
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class ModuleQuarantineErasureTest {
    @get:org.junit.Rule val backgroundPersistence = com.openlattice.chronicle.collection.state.BackgroundPersistenceRule()
    private val context: Context = ApplicationProvider.getApplicationContext()
    private lateinit var db: ChronicleDb

    @Before fun setUp() {
        val prefs = context.getSharedPreferences("module-erasure-test", Context.MODE_PRIVATE)
        prefs.edit().clear().commit()
        com.openlattice.chronicle.preferences.EncryptedPrefsHelper::class.java.getDeclaredField("instance")
            .apply { isAccessible = true }.set(com.openlattice.chronicle.preferences.EncryptedPrefsHelper, prefs)
        db = Room.inMemoryDatabaseBuilder(context, ChronicleDb::class.java).allowMainThreadQueries().build()
        ChronicleDb::class.java.getDeclaredField("INSTANCE").apply { isAccessible = true }.set(null, db)
        listOf("audio_content", "app_network_usage", "battery_samples", "direct_boot_diagnostic").forEach { source ->
            db.localDataQuarantineDao().insert(LocalDataQuarantineEntity(
                id = source, sourceTable = source, sourceId = "row-1",
                studyId = "study", participantId = "participant", deviceId = "device",
                rawData = "malformed".toByteArray(), reason = "SAMPLE_QUARANTINED",
                createdAt = "2026-09-28T00:00:00Z",
            ))
        }
    }

    @After fun tearDown() {
        com.openlattice.chronicle.preferences.EncryptedPrefsHelper::class.java.getDeclaredField("instance")
            .apply { isAccessible = true }.set(com.openlattice.chronicle.preferences.EncryptedPrefsHelper, null)
        ChronicleDb::class.java.getDeclaredField("INSTANCE").apply { isAccessible = true }.set(null, null)
        db.close()
    }

    private fun usage(activity: String? = "MainActivity") = ExtractedUsageEvent(
        appPackageName = "example.app", interactionType = "Activity Resumed",
        timestamp = OffsetDateTime.parse("2026-09-28T10:00:00Z"), timezone = "UTC",
        user = "participant", applicationLabel = "Example", activityClass = activity,
    )

    private fun quarantine(id: String, raw: ByteArray) = LocalDataQuarantineEntity(
        id = id, sourceTable = "dataQueue", sourceId = id,
        studyId = "study", participantId = "participant", deviceId = "device",
        rawData = raw, reason = "SAMPLE_QUARANTINED", createdAt = "2026-09-28T00:00:00Z",
    ).also { db.localDataQuarantineDao().insert(it) }

    private fun discard(moduleId: CollectionModuleId) {
        CollectionLoopCoordinator::class.java.getDeclaredMethod("applyDisposition",
            CollectionModuleId::class.java, CollectionDataDisposition::class.java).apply { isAccessible = true }
            .invoke(CollectionLoopCoordinator(context), moduleId, CollectionDataDisposition.DISCARD_AND_STOP)
    }

    @Test fun identificationDiscardRedactsLiveAndQuarantinedUsers() {
        val preference = context.getString(com.openlattice.chronicle.R.string.current_user)
        EncryptedPrefsHelper.getEncryptedPrefs(context).edit().putString(preference, "identified-user").commit()
        val raw = JsonSerializer.serializeQueueEntry(listOf(usage()))
        quarantine("identified", raw)
        db.queueEntryData().insertEntry(QueueEntry(1_000, 1, raw))
        discard(CollectionModuleId.USER_IDENTIFICATION)
        org.junit.Assert.assertFalse(EncryptedPrefsHelper.getEncryptedPrefs(context).contains(preference))
        val live = db.queueEntryData().getEntriesAfter(Long.MIN_VALUE, Long.MIN_VALUE, 10).single()
        assertEquals("", (JsonSerializer.deserializeQueueEntry(live.data).single() as ExtractedUsageEvent).user)
        val kept = db.localDataQuarantineDao().get("dataQueue", "identified")!!
        assertEquals("", (JsonSerializer.deserializeQueueEntry(kept.rawData).single() as ExtractedUsageEvent).user)
    }

    @Test fun activityDiscardRedactsQuarantinedUsageAndLiveUsageTogether() {
        val raw = JsonSerializer.serializeQueueEntry(listOf(usage()))
        quarantine("usage", raw)
        db.queueEntryData().insertEntry(QueueEntry(1_000, 1, raw))

        discard(CollectionModuleId.IN_APP_ACTIVITY_CLASS)

        val kept = db.localDataQuarantineDao().get("dataQueue", "usage")!!
        assertEquals(listOf(usage(null)), JsonSerializer.deserializeQueueEntry(kept.rawData).toList())
        val live = db.queueEntryData().getEntriesAfter(Long.MIN_VALUE, Long.MIN_VALUE, 10).single()
        assertEquals(listOf(usage(null)), JsonSerializer.deserializeQueueEntry(live.data).toList())
    }

    @Test fun lifecycleDiscardKeepsQuarantinedUsageOnlyRowUnchanged() {
        val raw = JsonSerializer.serializeQueueEntry(listOf(usage(null)))
        quarantine("usage", raw)

        discard(CollectionModuleId.DEVICE_LIFECYCLE)

        assertArrayEquals(raw, db.localDataQuarantineDao().get("dataQueue", "usage")!!.rawData)
    }

    @Test fun sharedDiscardDeletesUnparseableQuarantineIncludingRowsQuarantinedByLiveEraser() {
        quarantine("broken", "not json".toByteArray())
        db.queueEntryData().insertEntry(QueueEntry(1_000, 1, "not json".toByteArray()))

        discard(CollectionModuleId.IN_APP_ACTIVITY_CLASS)

        assertEquals(0, db.localDataQuarantineDao().count("dataQueue"))
        assertEquals(0, db.queueEntryData().getEntriesAfter(Long.MIN_VALUE, Long.MIN_VALUE, 10).size)
        assertNotNull(db.localDataQuarantineDao().get("battery_samples", "row-1"))
    }

    @Test fun usageDiscardKeepsLifecycleInMixedQuarantineAndDeletesUsageOnlyRow() {
        val lifecycle = usage(null).copy(appPackageName = ANDROID_SYSTEM_PACKAGE, interactionType = INTERACTION_BATTERY_LOW)
        quarantine("mixed", JsonSerializer.serializeQueueEntry(listOf(usage(), lifecycle)))
        quarantine("usage", JsonSerializer.serializeQueueEntry(listOf(usage())))

        discard(CollectionModuleId.USAGE_EVENTS)

        assertEquals(listOf(lifecycle), JsonSerializer.deserializeQueueEntry(
            db.localDataQuarantineDao().get("dataQueue", "mixed")!!.rawData).toList())
        assertNull(db.localDataQuarantineDao().get("dataQueue", "usage"))
    }

    @Test fun quarantineFailureRollsBackSharedLiveQueueErasure() {
        val raw = JsonSerializer.serializeQueueEntry(listOf(usage()))
        quarantine("usage", raw)
        db.queueEntryData().insertEntry(QueueEntry(1_000, 1, raw))
        db.openHelper.writableDatabase.execSQL(
            "CREATE TRIGGER fail_quarantine BEFORE UPDATE ON local_data_quarantine BEGIN SELECT RAISE(ABORT, 'quarantine down'); END",
        )

        assertThrows(java.lang.reflect.InvocationTargetException::class.java) {
            discard(CollectionModuleId.IN_APP_ACTIVITY_CLASS)
        }

        assertArrayEquals(raw, db.localDataQuarantineDao().get("dataQueue", "usage")!!.rawData)
        assertArrayEquals(raw, db.queueEntryData().getEntriesAfter(Long.MIN_VALUE, Long.MIN_VALUE, 10).single().data)
    }

    @Test fun moduleDiscardErasesOnlyItsQuarantineSourceInTheTableTransaction() {
        assertThrows(IllegalStateException::class.java) {
            db.runInTransaction {
                eraseRestrictedQueueAndQuarantineInTransaction(db, DispositionQueue.AUDIO_CONTENT_SAMPLES)
                error("rollback")
            }
        }
        assertEquals(1, db.localDataQuarantineDao().count("audio_content"))

        db.runInTransaction {
            eraseRestrictedQueueAndQuarantineInTransaction(db, DispositionQueue.AUDIO_CONTENT_SAMPLES)
        }
        assertNull(db.localDataQuarantineDao().get("audio_content", "row-1"))
        assertNotNull(db.localDataQuarantineDao().get("app_network_usage", "row-1"))
        assertNotNull(db.localDataQuarantineDao().get("battery_samples", "row-1"))
    }

    @Test fun playBoundaryErasesRestrictedQuarantineAndRetainsApprovedModuleQuarantine() {
        purgeRestrictedPlayRows(db)

        assertNull(db.localDataQuarantineDao().get("audio_content", "row-1"))
        assertNull(db.localDataQuarantineDao().get("app_network_usage", "row-1"))
        assertNotNull(db.localDataQuarantineDao().get("battery_samples", "row-1"))
        // Ownerless direct-boot diagnostic counts hold no sample data; diagnostics are retained.
        assertNotNull(db.localDataQuarantineDao().get("direct_boot_diagnostic", "row-1"))
    }
}
