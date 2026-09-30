package com.openlattice.chronicle.collection.state

import android.content.Context
import android.os.SystemClock
import androidx.test.core.app.ApplicationProvider
import com.openlattice.chronicle.layout.TestStores
import com.openlattice.chronicle.services.lifecycle.ANDROID_SYSTEM_PACKAGE
import com.openlattice.chronicle.services.lifecycle.DeviceLifecycleEventRecorder
import com.openlattice.chronicle.services.lifecycle.INTERACTION_BATTERY_LOW
import com.openlattice.chronicle.storage.ChronicleDb
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

    private fun lifecycleLoss(): Int = ChronicleDb.getInstance(context).openHelper.readableDatabase
        .query("SELECT coalesce(sum(count), 0) FROM upload_diagnostics WHERE moduleFamily = 'USAGE_LIFECYCLE' " +
            "AND issueCode = 'COLLECTION_GATE_DROPPED'")
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
}
