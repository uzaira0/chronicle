package com.openlattice.chronicle.collection.sink

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.openlattice.chronicle.storage.ChronicleDb
import com.openlattice.chronicle.storage.UploadStatsEntity
import com.openlattice.chronicle.storage.insertOwnedDay
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** Upload counts belong to one enrollment epoch; a legacy ownerless day is claimed, never shared. */
@RunWith(RobolectricTestRunner::class)
class UploadStatsOwnershipTest {
    private val db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext<Context>(), ChronicleDb::class.java)
        .allowMainThreadQueries().build()
    private val dao = db.uploadStatsDao()

    @After fun tearDown() = db.close()

    private fun owned(server: Long, date: String, epoch: String) = UploadStatsEntity(serverId = server, date = date,
        studyId = "study", participantId = "participant", deviceId = "device", enrollmentEpoch = epoch)

    @Test fun legacyOwnerlessDayIsClaimedByTheWritingEnrollment() {
        dao.insertDay(UploadStatsEntity(serverId = 7, date = "2026-09-20"))

        dao.insertOwnedDay(owned(7, "2026-09-20", "7:created"))
        dao.incrementSensorCount(7, "2026-09-20", 4)

        assertEquals(4, dao.sensorUploadedOn("2026-09-20", 7, "7:created"))
        assertEquals(listOf("7:created"), dao.getRecentStats(7, "7:created", 7).map { it.enrollmentEpoch })
    }

    @Test fun historyExcludesDaysOwnedByAnotherEpoch() {
        dao.insertOwnedDay(owned(7, "2026-09-20", "7:old"))
        dao.insertOwnedDay(owned(7, "2026-09-21", "7:new"))
        dao.incrementUsageCount(7, "2026-09-20", 5)
        dao.incrementUsageCount(7, "2026-09-21", 2)

        assertEquals(listOf("2026-09-21"), dao.getRecentStats(7, "7:new", 7).map { it.date })
        assertEquals(0, dao.usageUploadedOn("2026-09-20", 7, "7:new"))
    }
}
