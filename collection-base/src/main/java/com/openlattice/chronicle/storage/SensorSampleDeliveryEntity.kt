package com.openlattice.chronicle.storage

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index

/**
 * Durable acknowledgement that one configured upload destination accepted one sensor sample.
 *
 * On the normal upload path, a sample leaves [SensorSampleEntry]'s queue only after every row in
 * [UploadServerEntity] has a matching acknowledgement. Disabled destinations therefore keep
 * their place without timestamp watermarks. Explicit retention/capacity cleanup and malformed
 * quarantine are separate, observable boundaries and do not create delivery receipts.
 */
@Entity(
    tableName = "sensor_sample_deliveries",
    primaryKeys = ["sampleId", "serverId"],
    foreignKeys = [
        ForeignKey(
            entity = SensorSampleEntry::class,
            parentColumns = ["id"],
            childColumns = ["sampleId"],
            onDelete = ForeignKey.CASCADE,
        ),
        ForeignKey(
            entity = UploadServerEntity::class,
            parentColumns = ["id"],
            childColumns = ["serverId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index("serverId")],
)
data class SensorSampleDeliveryEntity(
    val sampleId: String,
    val serverId: Long,
    /** Enrollment generation accepted by the destination; stale-generation receipts never trim. */
    val serverGeneration: Long,
    val deliveredAt: String,
)
