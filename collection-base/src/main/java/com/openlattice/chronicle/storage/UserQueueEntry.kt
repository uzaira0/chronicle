package com.openlattice.chronicle.storage

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import java.util.*

@Entity(
    tableName = "userQueue",
    primaryKeys = ["writeTimestamp"],
    indices = [Index("writeTimestamp", unique = true)]
)
data class UserQueueEntry(
    val writeTimestamp: Long = System.currentTimeMillis(),
    @ColumnInfo(name = "user") val user: String,
    /** Decision order: the wall clock can move back, so the later selection is the one with the larger seq. */
    @ColumnInfo(defaultValue = "0") val seq: Long = 0,
)
