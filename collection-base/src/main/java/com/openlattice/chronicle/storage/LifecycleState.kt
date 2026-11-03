package com.openlattice.chronicle.storage

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query

@Entity(tableName = "lifecycle_state")
data class LifecycleStateEntity(@PrimaryKey val key: String, val value: String, val observedAt: Long)

@Dao
interface LifecycleStateDao {
    @Query("SELECT * FROM lifecycle_state WHERE `key` = :key")
    fun get(key: String): LifecycleStateEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun upsert(state: LifecycleStateEntity)

    @Query("DELETE FROM lifecycle_state WHERE `key` = :key")
    fun delete(key: String)

    @Query("DELETE FROM lifecycle_state WHERE `key` LIKE :dedupePrefix || '%' OR `key` IN (:sampledKeys)")
    fun clearObservations(dedupePrefix: String, sampledKeys: List<String>)

    @Query("DELETE FROM lifecycle_state")
    fun clear()
}
