package com.openlattice.chronicle.storage

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.PrimaryKey
import androidx.room.Query

@Entity(tableName = "pending_upload_batches")
data class PendingUploadBatchEntity(@PrimaryKey val key: String, val payloadType: String, val rowIds: String, val identity: String,
    val settingsVersion: Int? = null)

@Dao
interface PendingUploadBatchDao {
    @Query("SELECT * FROM pending_upload_batches WHERE `key` = :key")
    fun get(key: String): PendingUploadBatchEntity?

    @Insert
    fun insert(batch: PendingUploadBatchEntity)

    @Query("DELETE FROM pending_upload_batches WHERE `key` = :key")
    fun delete(key: String)

    @Query("DELETE FROM pending_upload_batches WHERE payloadType IN (:types)")
    fun eraseTypes(types: List<String>)
}
