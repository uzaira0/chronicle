package com.openlattice.chronicle.storage

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.room.Room
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.core.app.ApplicationProvider
import org.json.JSONObject
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** Executes real migration SQL and Room's current-schema validation in the authorized JVM gate. */
@RunWith(RobolectricTestRunner::class)
class RoomMigrationJvmCorpusTest {
    @Test fun everyReleasedSchemaThreeThroughTwentyNinePreservesRowsKeysAndCursors() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        for (version in 3..29) {
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
                        ChronicleDb.ALL_MIGRATIONS.filter { it.startVersion >= base && it.endVersion <= version }.forEach { it.migrate(db) }
                    }
                    override fun onUpgrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) = error("unexpected fixture upgrade")
                }).build())
            val prior = helper.writableDatabase
            val payload = byteArrayOf(0, 1, 2, 127, -1)
            val rows = seedEveryTable(prior, payload)
            helper.close()
            val current = Room.databaseBuilder(context, ChronicleDb::class.java, name).allowMainThreadQueries()
                .addMigrations(*ChronicleDb.ALL_MIGRATIONS).build()
            try {
                val db = current.openHelper.writableDatabase // Room validates every column and index.
                db.query("SELECT writeTimestamp,id,data FROM dataQueue").use {
                    assertTrue("v$version queue lost", it.moveToFirst());assertEquals(42L,it.getLong(0));assertEquals(2L,it.getLong(1));
                        assertArrayEquals(payload,it.getBlob(2));assertFalse(it.moveToNext())
                }
                db.query("SELECT user FROM userQueue").use { assertTrue(it.moveToFirst());assertEquals("Target",it.getString(0)) }
                db.query("SELECT id,x FROM sensor_samples").use { assertTrue(it.moveToFirst());assertEquals("preserved-sensor",it.getString(0));
                    assertEquals(1.25,it.getDouble(1),0.0) }
                if (version >= 4) db.query("SELECT lastUploadedTimestamp,apiKey FROM upload_servers WHERE id=1").use {
                    assertTrue("v$version server lost",it.moveToFirst());assertEquals(41L,it.getLong(0))
                    if (version >= 7) assertEquals("synthetic-preserved-key",it.getString(1))
                }
                assertRows(db, version, rows)
            } finally { current.close();context.deleteDatabase(name) }
        }
    }
    private fun seedEveryTable(db: SupportSQLiteDatabase, payload: ByteArray): Map<String, ContentValues> {
        val pending = linkedSetOf<String>()
        db.query("SELECT name FROM sqlite_master WHERE type='table' ORDER BY name").use { tables ->
            while (tables.moveToNext()) {
                val table = tables.getString(0)
                if (!table.startsWith("sqlite_") && table !in setOf("room_master_table", "android_metadata")) pending += table
            }
        }
        val foreignKeys = pending.associateWith { table ->
            buildMap {
                db.query("PRAGMA foreign_key_list(`$table`)").use { keys ->
                    while (keys.moveToNext()) put(keys.getString(3), keys.getString(2) to keys.getString(4))
                }
            }
        }
        val rows = linkedMapOf<String, ContentValues>()
        while (pending.isNotEmpty()) {
            val table = pending.firstOrNull { name ->
                foreignKeys.getValue(name).values.none { (parent, _) -> parent in pending && parent != name }
            } ?: error("Cyclic fixture foreign keys: $pending")
            val values = ContentValues()
            db.query("PRAGMA table_info(`$table`)").use { columns ->
                while (columns.moveToNext()) {
                    val column = columns.getString(1)
                    when (columns.getString(2).uppercase()) {
                        "INTEGER" -> values.put(column, if (column in setOf("id", "singletonKey", "serverEnabled")) 1L else 10L + columns.getInt(0))
                        "REAL" -> values.put(column, columns.getInt(0) + 0.25)
                        "BLOB" -> values.put(column, payload)
                        "TEXT" -> values.put(column, if (column in setOf("timestamp", "createdAt")) "2026-10-03T00:00:00Z" else "$table-$column")
                        else -> error("Unsupported fixture type for $table.$column")
                    }
                }
            }
            when (table) {
                "dataQueue" -> { values.put("writeTimestamp",42L);values.put("id",2L) }
                "userQueue" -> { values.put("writeTimestamp",40L);values.put("user","Target") }
                "sensor_samples" -> {
                    values.put("id","preserved-sensor");values.put("sensorType","accelerometer")
                    values.put("timezone","UTC");values.put("x",1.25)
                }
                "upload_servers" -> {
                    values.put("name","Synthetic");values.put("url","https://localhost")
                    values.put("studyId","11111111-1111-1111-1111-111111111111");values.put("participantId","participant")
                    values.put("enabled",1L);values.put("lastUploadedTimestamp",41L)
                    if (values.containsKey("authMode")) values.put("authMode","apiKey")
                    if (values.containsKey("apiKey")) values.put("apiKey","synthetic-preserved-key")
                }
            }
            for ((column, reference) in foreignKeys.getValue(table)) {
                val (parent, key) = reference
                when (val value = (if (parent == table) values else rows.getValue(parent)).get(key)) {
                    is Number -> values.put(column, value.toLong())
                    is String -> values.put(column, value)
                    else -> error("Unsupported fixture foreign key for $table.$column")
                }
            }
            db.insert(table, SQLiteDatabase.CONFLICT_ABORT, values)
            rows[table] = values
            pending -= table
        }
        return rows
    }

    private fun assertRows(db: SupportSQLiteDatabase, version: Int, rows: Map<String, ContentValues>) {
        for ((table, expected) in rows) {
            db.query("SELECT * FROM `$table`").use { cursor ->
                assertTrue("v$version lost $table", cursor.moveToFirst())
                for ((oldColumn, oldValue) in expected.valueSet()) {
                    val column = if (oldColumn == "acknowledgedAtEpochMillis") "decidedAtEpochMillis" else oldColumn
                    val index = cursor.getColumnIndexOrThrow(column)
                    val value = if (table == "upload_servers" && version < 28 && oldColumn.endsWith("Error")) "UPLOAD_FAILURE" else oldValue
                    val label = "v$version $table.$column"
                    when (value) {
                        is ByteArray -> assertArrayEquals(label, value, cursor.getBlob(index))
                        is Double -> assertEquals(label, value, cursor.getDouble(index), 0.0)
                        is Number -> assertEquals(label, value.toLong(), cursor.getLong(index))
                        else -> assertEquals(label, value, cursor.getString(index))
                    }
                }
                assertFalse("v$version duplicated $table", cursor.moveToNext())
            }
        }
    }
}
