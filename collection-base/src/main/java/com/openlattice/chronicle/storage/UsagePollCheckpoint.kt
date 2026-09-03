package com.openlattice.chronicle.storage

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query

@Entity(tableName = "usage_poll_checkpoints")
data class UsagePollCheckpointEntity(
    @PrimaryKey val sensorName: String,
    val lastPollTimestamp: Long
)

@Dao
interface UsagePollCheckpointDao {
    @Query("SELECT lastPollTimestamp FROM usage_poll_checkpoints WHERE sensorName = :sensorName")
    fun getLastPollTimestamp(sensorName: String): Long?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun upsert(checkpoint: UsagePollCheckpointEntity)
}
