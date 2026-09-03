package com.openlattice.chronicle.storage

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

@Dao
interface UploadStatsDao {

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    fun insertDay(stats: UploadStatsEntity)

    @Query("UPDATE upload_stats SET usageEventsUploaded = usageEventsUploaded + :count WHERE serverId = :serverId AND date = :date")
    fun incrementUsageCount(serverId: Long, date: String, count: Int)

    @Query("UPDATE upload_stats SET sensorSamplesUploaded = sensorSamplesUploaded + :count WHERE serverId = :serverId AND date = :date")
    fun incrementSensorCount(serverId: Long, date: String, count: Int)

    @Query("UPDATE upload_stats SET batterySamplesUploaded = batterySamplesUploaded + :count WHERE serverId = :serverId AND date = :date")
    fun incrementBatteryCount(serverId: Long, date: String, count: Int)

    @Query("UPDATE upload_stats SET usageUploadFailures = usageUploadFailures + :count WHERE serverId = :serverId AND date = :date")
    fun incrementUsageFailureCount(serverId: Long, date: String, count: Int)

    @Query("UPDATE upload_stats SET sensorUploadFailures = sensorUploadFailures + :count WHERE serverId = :serverId AND date = :date")
    fun incrementSensorFailureCount(serverId: Long, date: String, count: Int)

    @Query("UPDATE upload_stats SET batteryUploadFailures = batteryUploadFailures + :count WHERE serverId = :serverId AND date = :date")
    fun incrementBatteryFailureCount(serverId: Long, date: String, count: Int)

    @Query("SELECT * FROM upload_stats WHERE serverId = :serverId ORDER BY date DESC LIMIT :days")
    fun getRecentStats(serverId: Long, days: Int): List<UploadStatsEntity>

    /**
     * Total number of `(serverId, date)` counter rows. Read-only; used by the
     * upload-telemetry diagnostics module (Phase 8) — no schema change.
     */
    @Query("SELECT COUNT(*) FROM upload_stats")
    fun rowCount(): Int

    @Query("SELECT COALESCE(SUM(usageEventsUploaded), 0) FROM upload_stats WHERE date = :date")
    fun usageUploadedOn(date: String): Int

    @Query("SELECT COALESCE(SUM(sensorSamplesUploaded), 0) FROM upload_stats WHERE date = :date")
    fun sensorUploadedOn(date: String): Int

    @Query("SELECT COALESCE(SUM(batterySamplesUploaded), 0) FROM upload_stats WHERE date = :date")
    fun batteryUploadedOn(date: String): Int

    @Query("SELECT COALESCE(SUM(usageUploadFailures), 0) FROM upload_stats WHERE date = :date")
    fun usageFailuresOn(date: String): Int

    @Query("SELECT COALESCE(SUM(sensorUploadFailures), 0) FROM upload_stats WHERE date = :date")
    fun sensorFailuresOn(date: String): Int

    @Query("SELECT COALESCE(SUM(batteryUploadFailures), 0) FROM upload_stats WHERE date = :date")
    fun batteryFailuresOn(date: String): Int

    @Query("DELETE FROM upload_stats WHERE date < :cutoffDate")
    fun deleteOlderThan(cutoffDate: String): Int
}
