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
interface SensorSampleDao : BoundedQueueWriter {
    @Query("SELECT * FROM sensor_samples WHERE id IN (:ids)")
    fun getByIds(ids: List<String>): List<SensorSampleEntry>

    @Query("SELECT * FROM sensor_samples ORDER BY timestamp ASC, id ASC LIMIT :limit")
    fun getOldest(limit: Int): List<SensorSampleEntry>

    @Query("SELECT * FROM sensor_samples WHERE sensorType NOT IN (:excludedTypes) ORDER BY timestamp ASC, id ASC LIMIT :limit")
    fun getOldestExcludingTypes(limit: Int, excludedTypes: List<String>): List<SensorSampleEntry>

    @Transaction
    fun insertAll(samples: List<SensorSampleEntry>) = insertAllUnchecked(
        boundedRows(
            UploadQueueTable.SENSOR, samples, SensorSampleEntry::persistedPayloadBytes,
        ) { it.id },
    )

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    fun insertAllUnchecked(samples: List<SensorSampleEntry>)

    @Query("DELETE FROM sensor_samples WHERE id IN (:ids)")
    fun deleteByIds(ids: List<String>)

    @Query("DELETE FROM sensor_samples")
    fun deleteAll(): Int

    @Query("SELECT COUNT(*) FROM sensor_samples")
    fun count(): Int

    @Query("SELECT sensorType, COUNT(*) AS count FROM sensor_samples GROUP BY sensorType ORDER BY count DESC, sensorType ASC")
    fun countBySensorType(): List<SensorSampleTypeCount>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun insertDeadLetters(deadLetters: List<SensorSampleDeadLetterEntity>)

    /** Moves malformed rows out of the active queue without claiming network delivery. */
    @Transaction
    fun quarantineMalformed(
        deadLetters: List<SensorSampleDeadLetterEntity>,
        sampleIds: List<String>,
    ) {
        insertDeadLetters(deadLetters)
        deleteByIds(sampleIds)
    }

    @Query("DELETE FROM sensor_samples WHERE sensorType = :sensorType")
    fun deleteSamplesBySensorType(sensorType: String): Int

    @Query("DELETE FROM sensor_sample_dead_letters WHERE sensorType = :sensorType")
    fun deleteDeadLettersBySensorType(sensorType: String): Int
}
