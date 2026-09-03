package com.openlattice.chronicle.storage

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction

data class SensorSampleTypeCount(
    val sensorType: String,
    val count: Int,
)

@Dao
interface SensorSampleDao {
    @Query("SELECT * FROM sensor_samples ORDER BY timestamp ASC, id ASC LIMIT :limit")
    fun getOldest(limit: Int): List<SensorSampleEntry>

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    fun insertAll(samples: List<SensorSampleEntry>)

    @Query("DELETE FROM sensor_samples WHERE id IN (:ids)")
    fun deleteByIds(ids: List<String>)

    @Query("SELECT COUNT(*) FROM sensor_samples")
    fun count(): Int

    @Query("SELECT sensorType, COUNT(*) AS count FROM sensor_samples GROUP BY sensorType ORDER BY count DESC, sensorType ASC")
    fun countBySensorType(): List<SensorSampleTypeCount>

    @Query(
        """
        DELETE FROM sensor_samples
        WHERE id IN (
            SELECT id FROM sensor_samples
            WHERE timestamp < :cutoffTimestamp
            ORDER BY timestamp ASC, id ASC
            LIMIT :limit
        )
        """,
    )
    fun deleteOldestBefore(cutoffTimestamp: String, limit: Int): Int

    @Query(
        """
        DELETE FROM sensor_samples
        WHERE id IN (
            SELECT id FROM sensor_samples
            ORDER BY timestamp ASC, id ASC
            LIMIT :limit
        )
        """,
    )
    fun deleteOldest(limit: Int): Int

    @Query(
        "SELECT COUNT(*) FROM sensor_sample_deliveries " +
            "WHERE serverId = :serverId AND serverGeneration = :serverGeneration " +
            "AND sampleId IN (:sampleIds)",
    )
    fun countDeliveriesForServer(
        serverId: Long,
        serverGeneration: Long,
        sampleIds: List<String>,
    ): Int

    // REPLACE is intentional: re-enrollment increments the server generation, so the new
    // destination must be able to supersede the old generation's receipt for this sample.
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun insertDeliveries(deliveries: List<SensorSampleDeliveryEntity>)

    @Query(
        """
        DELETE FROM sensor_samples
        WHERE id IN (:sampleIds)
          AND NOT EXISTS (
              SELECT 1
              FROM upload_servers AS server
              WHERE NOT EXISTS (
                  SELECT 1
                  FROM sensor_sample_deliveries AS delivery
                  WHERE delivery.sampleId = sensor_samples.id
                    AND delivery.serverId = server.id
                    AND delivery.serverGeneration = server.sensorDeliveryGeneration
              )
          )
        """,
    )
    fun deleteFullyDeliveredByIds(sampleIds: List<String>): Int

    /** Atomically records a complete successful batch and trims only fully-acknowledged IDs. */
    @Transaction
    fun acknowledgeAndDeleteFullyDelivered(
        deliveries: List<SensorSampleDeliveryEntity>,
        sampleIds: List<String>,
    ): Int {
        insertDeliveries(deliveries)
        return deleteFullyDeliveredByIds(sampleIds)
    }

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun insertDeadLetters(deadLetters: List<SensorSampleDeadLetterEntity>)

    @Query("SELECT COUNT(*) FROM sensor_sample_dead_letters")
    fun countDeadLetters(): Int

    @Query("SELECT * FROM sensor_sample_dead_letters ORDER BY quarantinedAt ASC, sampleId ASC LIMIT :limit")
    fun getOldestDeadLetters(limit: Int): List<SensorSampleDeadLetterEntity>

    @Query("DELETE FROM sensor_sample_dead_letters WHERE sampleId IN (:sampleIds)")
    fun deleteDeadLettersByIds(sampleIds: List<String>): Int

    @Query(
        """
        DELETE FROM sensor_sample_dead_letters
        WHERE sampleId IN (
            SELECT sampleId FROM sensor_sample_dead_letters
            ORDER BY quarantinedAt ASC, sampleId ASC
            LIMIT :limit
        )
        """,
    )
    fun deleteOldestDeadLetters(limit: Int): Int

    /** Moves malformed rows out of the active queue without claiming network delivery. */
    @Transaction
    fun quarantineMalformed(
        deadLetters: List<SensorSampleDeadLetterEntity>,
        sampleIds: List<String>,
    ) {
        insertDeadLetters(deadLetters)
        deleteByIds(sampleIds)
    }

    /** Drops every pending sample — used by the DISCARD_AND_STOP disable disposition. */
    @Query("DELETE FROM sensor_samples")
    fun deleteAll()

    @Query("DELETE FROM sensor_samples WHERE sensorType = :sensorType")
    fun deleteSamplesBySensorType(sensorType: String): Int

    @Query("DELETE FROM sensor_sample_dead_letters WHERE sensorType = :sensorType")
    fun deleteDeadLettersBySensorType(sensorType: String): Int

    /** Drops only one consent module's tagged pending and quarantined sensor rows. */
    @Transaction
    fun deleteBySensorType(sensorType: String) {
        deleteSamplesBySensorType(sensorType)
        deleteDeadLettersBySensorType(sensorType)
    }
}
