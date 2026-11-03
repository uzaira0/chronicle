package com.openlattice.chronicle.collection.state

import com.openlattice.chronicle.preferences.CURRENT_USER
import android.database.sqlite.SQLiteException
import org.junit.Assert
import org.junit.Rule

import com.openlattice.chronicle.android.fromInteractionType

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.openlattice.chronicle.EnrollmentTestStores
import com.openlattice.chronicle.audit.AuditStores
import com.openlattice.chronicle.collection.CollectionModuleId
import com.openlattice.chronicle.constants.ANDROID_SYSTEM_PACKAGE
import com.openlattice.chronicle.constants.INTERACTION_BATTERY_LOW
import com.openlattice.chronicle.models.ExtractedUsageEvent
import com.openlattice.chronicle.preferences.EncryptedPrefsHelper
import com.openlattice.chronicle.serialization.JsonSerializer
import com.openlattice.chronicle.services.upload.LocalOperationalIssue
import com.openlattice.chronicle.storage.ChronicleDb
import com.openlattice.chronicle.storage.LocalDataQuarantineEntity
import com.openlattice.chronicle.storage.QueueEntry
import com.openlattice.chronicle.storage.SensorSampleEntry
import com.openlattice.chronicle.storage.serializeMalformedRow
import java.time.OffsetDateTime
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class ModuleQuarantineErasureTest {
    @get:Rule val backgroundPersistence = BackgroundPersistenceRule()
    private val context: Context = ApplicationProvider.getApplicationContext()
    private lateinit var db: ChronicleDb

    @Before fun setUp() {
        AuditStores.install(context, enrolled = true)
        db = ChronicleDb.getInstance(context)
        ResearchPersistenceGate.initialize(context)
        listOf(restrictedQueueTable(ModuleQueue.AUDIO_CONTENT_SAMPLES), restrictedQueueTable(ModuleQueue.APP_NETWORK_USAGE_SAMPLES),
            "battery_samples", "direct_boot_diagnostic").forEach { source ->
            db.localDataQuarantineDao().insert(LocalDataQuarantineEntity(
                id = source, sourceTable = source, sourceId = "row-1",
                studyId = "study", participantId = "participant", randomDeviceId = "device",
                rawData = "malformed".toByteArray(), reason = LocalOperationalIssue.SAMPLE_QUARANTINED.name,
                createdAt = "2026-09-28T00:00:00Z",
            ))
        }
    }

    @After fun tearDown() {
        ResearchPersistenceGate.resetAfterLocalStoreRecovery()
        StorageAdmission.clear(context)
        EnrollmentTestStores.prefs = null
        EnrollmentTestStores.db = null
        db.close()
    }

    private fun usage(activity: String? = "MainActivity") = ExtractedUsageEvent(eventType = fromInteractionType("Activity Resumed"),
        appPackageName = "example.app", interactionType = "Activity Resumed",
        timestamp = OffsetDateTime.parse("2026-09-28T10:00:00Z"), timezone = "UTC",
        user = "participant", applicationLabel = "Example", activityClass = activity,
    )

    private fun quarantine(id: String, raw: ByteArray) = LocalDataQuarantineEntity(
        id = id, sourceTable = "dataQueue", sourceId = id,
        studyId = "study", participantId = "participant", randomDeviceId = "device",
        rawData = raw, reason = LocalOperationalIssue.SAMPLE_QUARANTINED.name, createdAt = "2026-09-28T00:00:00Z",
    ).also { db.localDataQuarantineDao().insert(it) }

    private fun discard(moduleId: CollectionModuleId) {
        ResearchPersistenceGate.stop {
            ResearchErasureFence(context).erase(setOf(moduleId), ownerKey =
                ResearchErasureFence.enrollmentKey(db.uploadServerDao().getConfiguredServer()!!))
            CollectionLoopCoordinator(context).replayPendingErasures()
        }
    }

    @Test fun identificationDiscardRedactsLiveAndQuarantinedUsers() {
        EncryptedPrefsHelper.getEncryptedPrefs(context).edit().putString(CURRENT_USER, "identified-user").commit()
        val raw = JsonSerializer.serializeQueueEntry(listOf(usage()))
        quarantine("identified", raw)
        db.queueEntryData().insertEntry(QueueEntry(1_000, 1, raw))
        discard(CollectionModuleId.USER_IDENTIFICATION)
        Assert.assertFalse(EncryptedPrefsHelper.getEncryptedPrefs(context).contains(CURRENT_USER))
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

        assertThrows(SQLiteException::class.java) {
            discard(CollectionModuleId.IN_APP_ACTIVITY_CLASS)
        }

        assertArrayEquals(raw, db.localDataQuarantineDao().get("dataQueue", "usage")!!.rawData)
        assertArrayEquals(raw, db.queueEntryData().getEntriesAfter(Long.MIN_VALUE, Long.MIN_VALUE, 10).single().data)
    }

    @Test fun sensorDiscardErasesThatSensorsQuarantinedRowsAndKeepsOtherSensors() {
        listOf("gyroscope", "light").forEach { type ->
            val row = SensorSampleEntry("$type-row", type, "2026-09-28T00:00:00Z", "UTC", 1f, 2f, 3f, null, 1)
            db.localDataQuarantineDao().insert(LocalDataQuarantineEntity(
                id = "sensor_samples:${row.id}", sourceTable = "sensor_samples", sourceId = row.id,
                studyId = "study", participantId = "participant", randomDeviceId = "device",
                rawData = serializeMalformedRow(row), reason = LocalOperationalIssue.SAMPLE_QUARANTINED.name,
                createdAt = "2026-09-28T00:00:00Z",
            ))
        }

        discard(CollectionModuleId.SENSOR_GYROSCOPE)

        assertNull(db.localDataQuarantineDao().get("sensor_samples", "gyroscope-row"))
        assertNotNull(db.localDataQuarantineDao().get("sensor_samples", "light-row"))
    }

    @Test fun moduleDiscardErasesOnlyItsQuarantineSourceInTheTableTransaction() {
        assertThrows(IllegalStateException::class.java) {
            db.runInTransaction {
                eraseRestrictedQueueAndQuarantineInTransaction(db, ModuleQueue.AUDIO_CONTENT_SAMPLES)
                error("rollback")
            }
        }
        assertEquals(1, db.localDataQuarantineDao().count(restrictedQueueTable(ModuleQueue.AUDIO_CONTENT_SAMPLES)))

        db.runInTransaction {
            eraseRestrictedQueueAndQuarantineInTransaction(db, ModuleQueue.AUDIO_CONTENT_SAMPLES)
        }
        assertNull(db.localDataQuarantineDao().get(restrictedQueueTable(ModuleQueue.AUDIO_CONTENT_SAMPLES), "row-1"))
        assertNotNull(db.localDataQuarantineDao().get(restrictedQueueTable(ModuleQueue.APP_NETWORK_USAGE_SAMPLES), "row-1"))
        assertNotNull(db.localDataQuarantineDao().get("battery_samples", "row-1"))
    }
}
