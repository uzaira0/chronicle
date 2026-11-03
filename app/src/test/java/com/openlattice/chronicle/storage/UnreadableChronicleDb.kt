package com.openlattice.chronicle.storage

import android.content.Context
import android.database.sqlite.SQLiteException
import androidx.room.Room
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper

/**
 * Builds a ChronicleDb whose every query fails, as a locked or corrupt store would.
 * Closing a Room database does not simulate this: Room reopens it on the next query.
 */
fun createUnreadableChronicleDb(
    context: Context,
    failure: RuntimeException = SQLiteException("storage unavailable"),
    onAccess: () -> Unit = {},
): ChronicleDb = Room.inMemoryDatabaseBuilder(context, ChronicleDb::class.java)
        .openHelperFactory { config ->
            object : SupportSQLiteOpenHelper {
                override val databaseName: String? = config.name
                private fun unavailable(): Nothing { onAccess(); throw failure }
                override val writableDatabase: SupportSQLiteDatabase get() = unavailable()
                override val readableDatabase: SupportSQLiteDatabase get() = unavailable()
                override fun setWriteAheadLoggingEnabled(enabled: Boolean) = Unit
                override fun close() = Unit
            }
        }
        .allowMainThreadQueries()
        .build()
