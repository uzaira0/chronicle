package com.openlattice.chronicle.storage

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "upload_stats",
    foreignKeys = [ForeignKey(
        entity = UploadServerEntity::class,
        parentColumns = ["id"],
        childColumns = ["serverId"],
        onDelete = ForeignKey.CASCADE
    )],
    indices = [
        Index(value = ["serverId", "date"], unique = true),
        Index(value = ["serverId"])
    ]
)
data class UploadStatsEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val serverId: Long,
    val date: String,
    val usageEventsUploaded: Int = 0,
    val sensorSamplesUploaded: Int = 0,
    val batterySamplesUploaded: Int = 0,
    val usageUploadFailures: Int = 0,
    val sensorUploadFailures: Int = 0,
    val batteryUploadFailures: Int = 0
)
