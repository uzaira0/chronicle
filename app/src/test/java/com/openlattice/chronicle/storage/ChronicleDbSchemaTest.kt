package com.openlattice.chronicle.storage

import android.app.Application
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.core.app.ApplicationProvider
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.assertThrows
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class ChronicleDbSchemaTest {
    private val context: Context get() = ApplicationProvider.getApplicationContext()

    @Test fun newerDatabaseIsRefusedWithoutDeletingItsData() {
        val name = withDatabase { room ->
            room.openHelper.writableDatabase.execSQL("CREATE TABLE preserved_participant_data (value TEXT)")
            room.openHelper.writableDatabase.execSQL("INSERT INTO preserved_participant_data VALUES ('kept')")
            room.openHelper.databaseName!!
        }
        SQLiteDatabase.openDatabase(context.getDatabasePath(name).path, null, SQLiteDatabase.OPEN_READWRITE).use {
            it.version++
        }
        assertThrows(IllegalStateException::class.java) { withDatabase { it.openHelper.writableDatabase } }
        SQLiteDatabase.openDatabase(context.getDatabasePath(name).path, null, SQLiteDatabase.OPEN_READONLY).use { db ->
            db.rawQuery("SELECT value FROM preserved_participant_data", null).use {
                assertTrue(it.moveToFirst())
                assertEquals("kept", it.getString(0))
            }
        }
    }

    @Test fun exportedIdentityMatchesTheCompiledVersion() {
        withDatabase { room ->
            val db = room.openHelper.writableDatabase
            val schema = schema("/${ChronicleDb::class.java.name}/${db.version}.json")
            db.query("SELECT identity_hash FROM room_master_table").use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertEquals("Compiled schema must match the exported Room schema",
                    schema.getString("identityHash"), cursor.getString(0))
            }
        }
    }

    private fun <T> withDatabase(block: (ChronicleDb) -> T): T {
        val db = ChronicleDb.buildRoomInstance(context, FrameworkSQLiteOpenHelperFactory())
        try { return block(db) } finally { db.close() }
    }

    private fun schema(path: String): JSONObject = JSONObject(
        requireNotNull(javaClass.getResourceAsStream(path)).bufferedReader().use { it.readText() })
        .getJSONObject("database")
}
