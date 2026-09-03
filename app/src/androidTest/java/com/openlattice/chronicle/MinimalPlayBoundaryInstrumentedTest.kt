package com.openlattice.chronicle

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.openlattice.chronicle.services.release.purgeRestrictedPlayRows
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class MinimalPlayBoundaryInstrumentedTest {
    @Before
    fun setUp() {
        AppTestState.resetPrefs()
        AppTestState.clearMutableTables()
    }

    @Test
    fun purgeRemovesRestrictedRowsAndPreservesApprovedState() {
        val db = AppTestState.db()
        val sql = db.openHelper.writableDatabase
        sql.execSQL(
            "INSERT INTO sensor_samples " +
                "(id,sensorType,timestamp,timezone,x,y,z,w,accuracy,valuesJson) " +
                "VALUES ('restricted-sensor','accelerometer','2026-08-21T00:00:00Z','UTC',1,2,3,NULL,3,NULL)",
        )
        sql.execSQL(
            "INSERT INTO audio_activity_samples " +
                "(id,timestamp,timezone,eventType,audioActive,audioPackage,contentType,playbackState," +
                "outputRoute,routeConnected,mediaVolume,maxMediaVolume,ringerMode,dndActive,callActive) " +
                "VALUES ('restricted-audio','2026-08-21T00:00:00Z','UTC','SNAPSHOT',0,NULL,NULL,NULL," +
                "NULL,NULL,NULL,NULL,NULL,NULL,NULL)",
        )
        sql.execSQL(
            "UPDATE collection_module_state SET serverEnabled = 1 WHERE moduleId = 'audio_content'",
        )
        sql.execSQL(
            "UPDATE collection_module_state SET serverEnabled = 1 WHERE moduleId = 'usage_events'",
        )

        purgeRestrictedPlayRows(db)

        assertEquals(0, count(sql, "sensor_samples"))
        assertEquals(0, count(sql, "audio_activity_samples"))
        assertEquals(0, countWhere(sql, "collection_module_state", "moduleId = 'audio_content'"))
        assertEquals(1, countWhere(sql, "collection_module_state", "moduleId = 'usage_events'"))
    }

    private fun count(
        db: androidx.sqlite.db.SupportSQLiteDatabase,
        table: String,
    ): Int = countWhere(db, table, "1 = 1")

    private fun countWhere(
        db: androidx.sqlite.db.SupportSQLiteDatabase,
        table: String,
        predicate: String,
    ): Int = db.query("SELECT COUNT(*) FROM `$table` WHERE $predicate").use { cursor ->
        check(cursor.moveToFirst())
        cursor.getInt(0)
    }
}
