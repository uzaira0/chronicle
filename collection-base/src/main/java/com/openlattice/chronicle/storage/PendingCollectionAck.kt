package com.openlattice.chronicle.storage

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Index
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query

/**
 * A collection acknowledgment waiting for delivery. It lives in the same store as the module decisions so a
 * decision and its acknowledgment commit in one transaction, and a local-store reset clears both.
 */
@Entity(tableName = "pending_collection_acks", indices = [Index(value = ["stableKey"], unique = true)])
data class PendingCollectionAckEntity(
    @PrimaryKey(autoGenerate = true) val sequence: Long = 0,
    val stableKey: String,
    val record: String,
)

@Dao
interface PendingCollectionAckDao {
    @Query("SELECT * FROM pending_collection_acks ORDER BY sequence")
    fun getAll(): List<PendingCollectionAckEntity>

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    fun insertAll(rows: List<PendingCollectionAckEntity>)

    @Query("DELETE FROM pending_collection_acks")
    fun clear()
}
