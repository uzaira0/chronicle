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

    @Query("SELECT * FROM upload_stats WHERE serverId = :serverId AND enrollmentEpoch = :epoch ORDER BY date DESC LIMIT :days")
    fun getRecentStats(serverId: Long, epoch: String, days: Int): List<UploadStatsEntity>

    /**
     * Total number of `(serverId, date)` counter rows. Read-only; used by the
     * upload-telemetry diagnostics module.
     */
    @Query("SELECT COUNT(*) FROM upload_stats")
    fun rowCount(): Int

    @Query("SELECT COALESCE(SUM(usageEventsUploaded), 0) FROM upload_stats WHERE date = :date AND serverId = :serverId AND enrollmentEpoch = :epoch")
    fun usageUploadedOn(date: String, serverId: Long, epoch: String): Int

    @Query(
        "SELECT COALESCE(SUM(sensorSamplesUploaded), 0) FROM upload_stats WHERE date = :date AND serverId = :serverId AND enrollmentEpoch = :epoch")
    fun sensorUploadedOn(date: String, serverId: Long, epoch: String): Int

    @Query(
        "SELECT COALESCE(SUM(batterySamplesUploaded), 0) FROM upload_stats WHERE date = :date AND serverId = :serverId AND enrollmentEpoch = :epoch")
    fun batteryUploadedOn(date: String, serverId: Long, epoch: String): Int

    @Query("SELECT COALESCE(SUM(usageUploadFailures), 0) FROM upload_stats WHERE date = :date AND serverId = :serverId AND enrollmentEpoch = :epoch")
    fun usageFailuresOn(date: String, serverId: Long, epoch: String): Int

    @Query("SELECT COALESCE(SUM(sensorUploadFailures), 0) FROM upload_stats WHERE date = :date AND serverId = :serverId AND enrollmentEpoch = :epoch")
    fun sensorFailuresOn(date: String, serverId: Long, epoch: String): Int

    @Query(
        "SELECT COALESCE(SUM(batteryUploadFailures), 0) FROM upload_stats WHERE date = :date AND serverId = :serverId AND enrollmentEpoch = :epoch")
    fun batteryFailuresOn(date: String, serverId: Long, epoch: String): Int
}

/** Counters are attributed only to their original, complete enrollment identity. */
fun UploadStatsDao.insertOwnedDay(stats: UploadStatsEntity) {
    requireNotNull(stats.studyId)
    requireNotNull(stats.participantId)
    requireNotNull(stats.randomDeviceId)
    requireNotNull(stats.enrollmentEpoch)
    insertDay(stats)
}

fun UploadStatsDao.insertOwnedDay(server: UploadServerEntity, date: String) = insertOwnedDay(
    UploadStatsEntity(
        serverId = server.id, date = date, studyId = server.studyId,
        participantId = server.participantId, randomDeviceId = server.sourceRandomDeviceId,
        enrollmentEpoch = "${server.id}:${server.createdAt}",
    ),
)
