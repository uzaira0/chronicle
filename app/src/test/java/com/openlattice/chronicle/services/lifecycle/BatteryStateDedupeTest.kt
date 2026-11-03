package com.openlattice.chronicle.services.lifecycle

import android.content.Context
import android.content.Intent
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.openlattice.chronicle.collection.lifecycle.LifecycleEventMapper
import com.openlattice.chronicle.models.ExtractedUsageEvent
import com.openlattice.chronicle.serialization.JsonSerializer
import com.openlattice.chronicle.storage.ChronicleDb
import com.openlattice.chronicle.storage.QueueEntry
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** The plug broadcasts and the battery sampler report one charging state, recorded once per change. */
@RunWith(RobolectricTestRunner::class)
class BatteryStateDedupeTest {
    private val db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext<Context>(), ChronicleDb::class.java)
        .allowMainThreadQueries().build()
    private val dedupe = RoomLifecycleDedupeStore(db)
    private var now = 1_000_000L

    @After fun tearDown() = db.close()

    /** Persists [event] when the dedupe store admits it, as the recorder and the sampler do; returns its type or null. */
    private fun record(event: ExtractedUsageEvent?): String? {
        now += 60_000
        if (event == null || !dedupe.shouldPersist(event, now)) return null
        persistLifecycleEntries(db, listOf(QueueEntry(now, 1, JsonSerializer.serializeQueueEntry(listOf(event)))))
        return event.interactionType
    }

    private fun broadcast(action: String) = record(LifecycleEventMapper.eventForBroadcastAction(action, now + 60_000))
    private fun sample(plugged: Int, level: Int) = record(LifecycleEventMapper.batteryStateEvent(plugged, level, 100, now + 60_000))

    @Test fun plugRowsAndSamplesShareOneChargingState() {
        assertEquals("Battery Discharging", sample(plugged = 0, level = 80))
        assertEquals("a bucket crossing is no state change", null, sample(plugged = 0, level = 50))
        assertEquals("Battery Charging", broadcast(Intent.ACTION_POWER_CONNECTED))
        assertEquals("the sampler agrees with the plug row", null, sample(plugged = 1, level = 50))
        assertEquals("Battery Discharging", broadcast(Intent.ACTION_POWER_DISCONNECTED))
        assertEquals(null, sample(plugged = 0, level = 20))
        assertEquals("Battery Charging", sample(plugged = 2, level = 20))
    }
}
