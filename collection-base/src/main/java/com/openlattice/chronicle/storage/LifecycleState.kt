package com.openlattice.chronicle.storage

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Entity(tableName = "lifecycle_state")
data class LifecycleStateEntity(@PrimaryKey val key: String, val value: String, val observedAt: Long)

@Dao
interface LifecycleStateDao {
    @Query("SELECT * FROM lifecycle_state WHERE `key` = :key")
    fun get(key: String): LifecycleStateEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun upsert(state: LifecycleStateEntity)

    @Query("DELETE FROM lifecycle_state")
    fun clear()
}

val MIGRATION_29_30 = object : Migration(29, 30) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("CREATE TABLE IF NOT EXISTS `lifecycle_state` " +
            "(`key` TEXT NOT NULL, `value` TEXT NOT NULL, `observedAt` INTEGER NOT NULL, PRIMARY KEY(`key`))")
        db.execSQL("CREATE TABLE IF NOT EXISTS `pending_upload_batches` " +
            "(`key` TEXT NOT NULL, `payloadType` TEXT NOT NULL, `rowIds` TEXT NOT NULL, `identity` TEXT NOT NULL, PRIMARY KEY(`key`))")
    }
}
