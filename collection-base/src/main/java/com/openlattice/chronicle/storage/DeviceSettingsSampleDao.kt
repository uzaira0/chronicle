package com.openlattice.chronicle.storage

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction

/** Room DAO for `device_settings_samples`. Duplicate ids de-duplicate (IGNORE). */
@Dao
interface DeviceSettingsSampleDao : BoundedQueueWriter {
    @Query("SELECT * FROM device_settings_samples WHERE id IN (:ids)")
    fun getByIds(ids: List<String>): List<DeviceSettingsSampleEntry>

    @Query("SELECT * FROM device_settings_samples ORDER BY timestamp ASC LIMIT :limit")
    fun getOldest(limit: Int): List<DeviceSettingsSampleEntry>

    @Transaction
    fun insertAll(samples: List<DeviceSettingsSampleEntry>) = insertAllUnchecked(
        boundedRows(
            UploadQueueTable.DEVICE_SETTINGS, samples, DeviceSettingsSampleEntry::persistedPayloadBytes,
        ) { it.id },
    )

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    fun insertAllUnchecked(samples: List<DeviceSettingsSampleEntry>)

    @Query("DELETE FROM device_settings_samples WHERE id IN (:ids)")
    fun deleteByIds(ids: List<String>)

    @Query("SELECT COUNT(*) FROM device_settings_samples")
    fun count(): Int

    @Query("DELETE FROM device_settings_samples")
    fun deleteAll()
}
