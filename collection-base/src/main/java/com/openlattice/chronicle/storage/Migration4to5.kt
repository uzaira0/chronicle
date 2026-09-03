package com.openlattice.chronicle.storage

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

val MIGRATION_4_5 = object : Migration(4, 5) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("""
            CREATE TABLE IF NOT EXISTS upload_stats (
                id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                serverId INTEGER NOT NULL,
                date TEXT NOT NULL,
                usageEventsUploaded INTEGER NOT NULL DEFAULT 0,
                sensorSamplesUploaded INTEGER NOT NULL DEFAULT 0,
                FOREIGN KEY (serverId) REFERENCES upload_servers(id) ON DELETE CASCADE
            )
        """.trimIndent())
        db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS idx_upload_stats_server_date ON upload_stats(serverId, date)")
        db.execSQL("CREATE INDEX IF NOT EXISTS index_upload_stats_serverId ON upload_stats(serverId)")
    }
}
