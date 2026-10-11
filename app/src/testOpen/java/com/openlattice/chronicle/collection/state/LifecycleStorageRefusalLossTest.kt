package com.openlattice.chronicle.collection.state

import android.content.Context
import android.os.SystemClock
import androidx.test.core.app.ApplicationProvider
import com.openlattice.chronicle.layout.TestStores
import com.openlattice.chronicle.services.lifecycle.ANDROID_SYSTEM_PACKAGE
import com.openlattice.chronicle.services.lifecycle.DeviceLifecycleEventRecorder
import com.openlattice.chronicle.services.lifecycle.INTERACTION_BATTERY_LOW
import com.openlattice.chronicle.storage.ChronicleDb
import com.openlattice.chronicle.serialization.JsonSerializer
import com.openlattice.chronicle.services.lifecycle.DeviceStateSampler
import com.openlattice.chronicle.storage.QueueEntry
import com.openlattice.chronicle.services.lifecycle.persistLifecycleEntries
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** Captured lifecycle events refused by low storage are counted, not silently dropped. */
@RunWith(RobolectricTestRunner::class)
class LifecycleStorageRefusalLossTest {
    @get:org.junit.Rule val backgroundPersistence = BackgroundPersistenceRule()
    private val context: Context = ApplicationProvider.getApplicationContext()

    private fun cacheStorageDecision(allowed: Boolean, checkedAt: Long) {
        StorageAdmission::class.java.getDeclaredField("lastAllowed").apply { isAccessible = true }
            .setBoolean(StorageAdmission, allowed)
        StorageAdmission::class.java.getDeclaredField("lastCheckedAt").apply { isAccessible = true }
            .setLong(StorageAdmission, checkedAt)
    }

    @Before fun setUp() {
        TestStores.install(context, enrolled = true)
        ResearchPersistenceGate.resetAfterLocalStoreRecovery()
        ResearchPersistenceGate.initialize(context)
    }

    @After fun tearDown() = cacheStorageDecision(allowed = false, checkedAt = Long.MIN_VALUE)

    private fun lifecycleLoss(issue: String = "COLLECTION_GATE_DROPPED"): Int = ChronicleDb.getInstance(context).openHelper.readableDatabase
        .query("SELECT coalesce(sum(count), 0) FROM upload_diagnostics WHERE moduleFamily = 'USAGE_LIFECYCLE' " +
            "AND issueCode = ?", arrayOf(issue))
        .use { it.moveToFirst(); it.getInt(0) }

    @Test fun storageRefusedLifecycleEventsAreCountedForDeviceLifecycle() {
        cacheStorageDecision(allowed = false, checkedAt = SystemClock.elapsedRealtime())
        val now = System.currentTimeMillis()
        DeviceLifecycleEventRecorder.recordAsync(context, listOf(
            DeviceLifecycleEventRecorder.buildEvent(ANDROID_SYSTEM_PACKAGE, INTERACTION_BATTERY_LOW, now),
            DeviceLifecycleEventRecorder.buildEvent(ANDROID_SYSTEM_PACKAGE, INTERACTION_BATTERY_LOW, now + 1),
        ))
        val deadline = System.currentTimeMillis() + 5_000
        while (lifecycleLoss() == 0 && System.currentTimeMillis() < deadline) Thread.sleep(25)

        assertEquals(2, lifecycleLoss())
        assertEquals(0, ChronicleDb.getInstance(context).queueEntryData().getSize())
    }

    @Test fun storageRefusedObservedLifecycleEventsAreCounted() {
        cacheStorageDecision(allowed = false, checkedAt = SystemClock.elapsedRealtime())
        DeviceLifecycleEventRecorder.recordObserved(context) {
            listOf(DeviceLifecycleEventRecorder.lowMemoryEvent(10))
        }
        val deadline = System.currentTimeMillis() + 5_000
        while (lifecycleLoss() == 0 && System.currentTimeMillis() < deadline) Thread.sleep(25)

        assertEquals(1, lifecycleLoss())
        assertEquals(0, ChronicleDb.getInstance(context).queueEntryData().getSize())
    }

    @Test fun observationExceptionIsCountedAndLoggedWithoutItsMessage() {
        cacheStorageDecision(allowed = true, checkedAt = SystemClock.elapsedRealtime())
        org.robolectric.shadows.ShadowLog.clear()
        DeviceLifecycleEventRecorder.recordObserved(context) { error("private-observation-sentinel") }
        val deadline = System.currentTimeMillis() + 5_000
        while (lifecycleLoss("LOCAL_WRITE_FAILED") == 0 && System.currentTimeMillis() < deadline) Thread.sleep(25)
        assertEquals(1, lifecycleLoss("LOCAL_WRITE_FAILED"))
        val logs = org.robolectric.shadows.ShadowLog.getLogs()
        org.junit.Assert.assertTrue(logs.any { it.type == android.util.Log.ERROR && it.msg.contains("Lifecycle observation") })
        org.junit.Assert.assertFalse(logs.any { it.msg.contains("private-observation-sentinel") })
    }

    @Test fun failedLifecycleTransactionLeavesUnchangedStateEligibleForTheNextPoll() {
        cacheStorageDecision(allowed = true, checkedAt = SystemClock.elapsedRealtime())
        val db = ChronicleDb.getInstance(context)
        val sampler = DeviceStateSampler(context)
        val events = sampler.poll()
        org.junit.Assert.assertTrue(events.isNotEmpty())
        val entries = listOf(QueueEntry(System.currentTimeMillis(), 1,
            JsonSerializer.serializeQueueEntry(events)))
        db.openHelper.writableDatabase.execSQL(
            "CREATE TRIGGER refuse_state BEFORE INSERT ON lifecycle_state " +
                "WHEN NEW.key <> '${com.openlattice.chronicle.storage.QUEUE_CAPTURE_VERSION_KEY}' " +
                "BEGIN SELECT RAISE(ABORT, 'synthetic'); END",
        )
        org.junit.Assert.assertThrows(Exception::class.java) {
            persistLifecycleEntries(db, entries)
        }
        assertEquals(0, db.queueEntryData().getSize())
        assertEquals(events.map { it.activityClass }, sampler.poll().map { it.activityClass })
        db.openHelper.writableDatabase.execSQL("DROP TRIGGER refuse_state")
        persistLifecycleEntries(db, entries)
        assertEquals(1, db.queueEntryData().getSize())
        org.junit.Assert.assertTrue(sampler.poll().isEmpty())
    }
}
