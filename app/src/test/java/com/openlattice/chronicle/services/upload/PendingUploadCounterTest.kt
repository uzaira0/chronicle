package com.openlattice.chronicle.services.upload

import android.app.Application
import android.content.ContentValues
import android.database.sqlite.SQLiteDatabase
import androidx.room.Room
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.test.core.app.ApplicationProvider
import com.openlattice.chronicle.BuildConfig
import com.openlattice.chronicle.storage.ChronicleDb
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class PendingUploadCounterTest {
    @Test
    fun `pending total includes every upload table and excludes local participant labels`() {
        val auxiliary = AuxiliaryPendingUploadCounts(
            connectivityState = 11,
            appNetworkUsage = 12,
            deviceSettings = 13,
            restricted = RestrictedPendingUploadCounts(
                interactionEvents = 4,
                audioActivity = 5,
                audioContent = 6,
                notificationActivity = 7,
                sleep = 8,
                activityRecognition = 9,
                healthMetrics = 10,
            ),
        )
        val pending = PendingUploadCounts(
            usageAndLifecycle = 1,
            sensorSamples = 2,
            batterySamples = 3,
            auxiliary = auxiliary,
            localParticipantLabels = 10_000,
        )

        assertEquals(85L, auxiliary.total)
        assertEquals(91L, pending.total)
    }

    @Test
    fun `zero auxiliary counts do not change the core pending total`() {
        val pending = PendingUploadCounts(
            usageAndLifecycle = 3,
            sensorSamples = 5,
            batterySamples = 7,
            auxiliary = AuxiliaryPendingUploadCounts(
                connectivityState = 0,
                appNetworkUsage = 0,
                deviceSettings = 0,
            ),
            localParticipantLabels = 99,
        )

        assertEquals(15L, pending.total)
    }

    @Test
    fun `snapshot returns persisted counts and keeps local labels out of the upload total`() {
        val context = ApplicationProvider.getApplicationContext<Application>()
        val db = Room.inMemoryDatabaseBuilder(context, ChronicleDb::class.java).allowMainThreadQueries().build()
        try {
            val counts = mapOf("dataQueue" to 1, "sensor_samples" to 2, "battery_samples" to 3,
                "connectivity_state_samples" to 11, "app_network_usage_samples" to 12,
                "device_settings_samples" to 13, "userQueue" to 100,
                "interaction_samples" to 4, "audio_activity_samples" to 5, "audio_content_samples" to 6,
                "notification_activity_samples" to 7, "sleep_samples" to 8,
                "activity_recognition_samples" to 9, "health_metric_samples" to 10)
            counts.forEach { (table, count) -> seed(db.openHelper.writableDatabase, table, count) }
            val pending = PendingUploadCounter.snapshot(db)
            val restricted = if (BuildConfig.ALLOW_RESTRICTED_RESEARCH_PERMISSIONS)
                RestrictedPendingUploadCounts(4, 5, 6, 7, 8, 9, 10) else null
            assertEquals(PendingUploadCounts(1, 2, 3,
                AuxiliaryPendingUploadCounts(11, if (BuildConfig.HAS_APP_NETWORK_USAGE) 12 else 0, 13, restricted),
                100), pending)
            val expectedTotal = when {
                BuildConfig.ALLOW_RESTRICTED_RESEARCH_PERMISSIONS -> 91L
                BuildConfig.HAS_APP_NETWORK_USAGE -> 42L
                else -> 30L
            }
            assertEquals(expectedTotal, pending.total)
        } finally { db.close() }
    }

    /** Queue contents are opaque to the counter; use distinct rows in each real Room table. */
    private fun seed(db: SupportSQLiteDatabase, table: String, count: Int) {
        repeat(count) { index ->
            val values = ContentValues()
            db.query("PRAGMA table_info(`$table`)").use { columns ->
                while (columns.moveToNext()) {
                    val name = columns.getString(columns.getColumnIndexOrThrow("name"))
                    val type = columns.getString(columns.getColumnIndexOrThrow("type"))
                    when (type) {
                        "INTEGER" -> values.put(name, index + 1)
                        "REAL" -> values.put(name, index + 1.0)
                        "BLOB" -> values.put(name, byteArrayOf(1))
                        else -> values.put(name, "fixture-$index")
                    }
                }
            }
            db.insert(table, SQLiteDatabase.CONFLICT_ABORT, values)
        }
    }
}
