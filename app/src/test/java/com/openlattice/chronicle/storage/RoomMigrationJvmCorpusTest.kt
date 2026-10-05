package com.openlattice.chronicle.storage

import android.content.ContentValues
import android.content.Context
import androidx.room.Room
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.core.app.ApplicationProvider
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** Executes real migration SQL and Room's current-schema validation in the authorized JVM gate. */
@RunWith(RobolectricTestRunner::class)
class RoomMigrationJvmCorpusTest {
    private val migrations = arrayOf(
        MIGRATION_3_4, MIGRATION_4_5, MIGRATION_5_6, MIGRATION_6_7, MIGRATION_7_8,
        MIGRATION_8_9, MIGRATION_9_10, MIGRATION_10_11, MIGRATION_11_12, MIGRATION_12_13,
        MIGRATION_13_14, MIGRATION_14_15, MIGRATION_15_16, MIGRATION_16_17, MIGRATION_17_18,
        MIGRATION_18_19, MIGRATION_19_20, MIGRATION_20_21, MIGRATION_21_22, MIGRATION_22_23,
        MIGRATION_23_24, MIGRATION_24_25, MIGRATION_25_26, MIGRATION_26_27, MIGRATION_27_28, MIGRATION_28_29,
    )
    @Test fun everyReleasedSchemaThreeThroughTwentyEightPreservesRowsKeysAndCursors() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        for (version in 3..28) {
            val name = "migration-jvm-$version.db"
            context.deleteDatabase(name)
            // Schemas 4–6 were never exported. Derive them from the real schema 3 using the
            // same released migrations, rather than inventing replacement CREATE statements.
            val base = if (version in 4..6) 3 else version
            val resource = javaClass.getResource("/com.openlattice.chronicle.storage.ChronicleDb/$base.json")
            assertNotNull("exported schema $base must be wired into the app JVM gate", resource)
            val schema = JSONObject(resource!!.readText()).getJSONObject("database")
            val helper = FrameworkSQLiteOpenHelperFactory().create(SupportSQLiteOpenHelper.Configuration.builder(context)
                .name(name).callback(object : SupportSQLiteOpenHelper.Callback(version) {
                    override fun onCreate(db: SupportSQLiteDatabase) {
                        val entities = schema.getJSONArray("entities")
                        for (i in 0 until entities.length()) {
                            val table = entities.getJSONObject(i)
                            val tableName = table.getString("tableName")
                            fun sql(value: String) = value.replace("\${TABLE_NAME}", tableName)
                            db.execSQL(sql(table.getString("createSql")))
                            val indices = table.optJSONArray("indices") ?: continue
                            for (j in 0 until indices.length()) db.execSQL(sql(indices.getJSONObject(j).getString("createSql")))
                        }
                        migrations.filter { it.startVersion >= base && it.endVersion <= version }.forEach { it.migrate(db) }
                    }
                    override fun onUpgrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) = error("unexpected fixture upgrade")
                }).build())
            val prior = helper.writableDatabase
            val payload = byteArrayOf(0, 1, 2, 127, -1)
            prior.execSQL("INSERT INTO dataQueue(writeTimestamp,id,data) VALUES(42,2,?)", arrayOf(payload))
            prior.execSQL("INSERT INTO userQueue(writeTimestamp,user) VALUES(40,'Target')")
            prior.execSQL("INSERT INTO sensor_samples(id,sensorType,timestamp,timezone,x) VALUES('preserved-sensor','accelerometer','2026-10-03T00:00:00Z','UTC',1.25)")
            if (version >= 4) seedServer(prior)
            helper.close()
            val current = Room.databaseBuilder(context, ChronicleDb::class.java, name).allowMainThreadQueries()
                .addMigrations(*migrations).build()
            try {
                val db = current.openHelper.writableDatabase // Room validates every column and index.
                db.query("SELECT writeTimestamp,id,data FROM dataQueue").use {
                    assertTrue("v$version queue lost", it.moveToFirst());assertEquals(42L,it.getLong(0));assertEquals(2L,it.getLong(1));assertArrayEquals(payload,it.getBlob(2));assertFalse(it.moveToNext())
                }
                db.query("SELECT user FROM userQueue").use { assertTrue(it.moveToFirst());assertEquals("Target",it.getString(0)) }
                db.query("SELECT id,x FROM sensor_samples").use { assertTrue(it.moveToFirst());assertEquals("preserved-sensor",it.getString(0));assertEquals(1.25,it.getDouble(1),0.0) }
                if (version >= 4) db.query("SELECT lastUploadedTimestamp,apiKey FROM upload_servers WHERE id=1").use {
                    assertTrue("v$version server lost",it.moveToFirst());assertEquals(41L,it.getLong(0))
                    if (version >= 7) assertEquals("synthetic-preserved-key",it.getString(1))
                }
            } finally { current.close();context.deleteDatabase(name) }
        }
    }
    private fun seedServer(db: SupportSQLiteDatabase) {
        val values = ContentValues()
        db.query("PRAGMA table_info(upload_servers)").use { columns ->
            while (columns.moveToNext()) {
                val name = columns.getString(1);val type=columns.getString(2)
                if (columns.getInt(3)==1 && columns.isNull(4)) {
                    if (type=="INTEGER") values.put(name,0L) else values.put(name,"")
                }
            }
        }
        values.put("id",1);values.put("name","Synthetic");values.put("url","https://localhost")
        values.put("studyId","11111111-1111-1111-1111-111111111111");values.put("participantId","participant")
        values.put("enabled",1);values.put("lastUploadedTimestamp",41L);values.put("createdAt","2026-10-03T00:00:00Z")
        if (values.containsKey("authMode")) values.put("authMode","apiKey")
        db.query("PRAGMA table_info(upload_servers)").use { columns ->
            while(columns.moveToNext()) if(columns.getString(1)=="apiKey") values.put("apiKey","synthetic-preserved-key")
        }
        db.insert("upload_servers",android.database.sqlite.SQLiteDatabase.CONFLICT_ABORT,values)
    }
}
