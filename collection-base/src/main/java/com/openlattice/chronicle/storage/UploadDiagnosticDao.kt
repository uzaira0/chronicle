package com.openlattice.chronicle.storage

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Delete
import androidx.room.Update

@Dao
interface UploadDiagnosticDao {
    @Query("SELECT * FROM upload_diagnostics WHERE studyId = :studyId AND participantId = :participantId AND deviceId = :deviceId AND enrollmentEpoch = :epoch ORDER BY firstOccurredAt, id")
    fun forEnrollment(studyId: String, participantId: String, deviceId: String, epoch: String): List<UploadDiagnosticEntity>

    @Query("SELECT * FROM upload_diagnostics WHERE studyId = :studyId AND participantId = :participantId AND deviceId = :deviceId AND enrollmentEpoch = :epoch AND day >= :cutoff AND day <= :today ORDER BY day DESC, moduleFamily, issueCode")
    fun recent(studyId: String, participantId: String, deviceId: String, epoch: String, cutoff: String, today: String): List<UploadDiagnosticEntity>

    @Query("SELECT * FROM upload_diagnostics WHERE studyId = :studyId AND participantId = :participantId AND deviceId = :deviceId AND enrollmentEpoch = :epoch AND day = :day AND moduleFamily = :family AND issueCode = :issue AND httpStatus IS :status AND errorType IS :errorType AND deliveryState = 'PENDING' AND sealedForUpload = 0 AND count < 2147483647 ORDER BY firstOccurredAt DESC LIMIT 1")
    fun findOpen(studyId: String, participantId: String, deviceId: String, epoch: String, day: String, family: String, issue: String, status: Int?, errorType: String?): UploadDiagnosticEntity?

    @Query("SELECT * FROM upload_diagnostics WHERE studyId = :studyId AND participantId = :participantId AND deviceId = :deviceId AND enrollmentEpoch = :epoch AND deliveryState = 'PENDING' ORDER BY firstOccurredAt, id LIMIT 500")
    fun pending(studyId: String, participantId: String, deviceId: String, epoch: String): List<UploadDiagnosticEntity>

    @Query("SELECT COUNT(*) FROM upload_diagnostics WHERE studyId = :studyId AND participantId = :participantId AND deviceId = :deviceId AND enrollmentEpoch = :epoch AND deliveryState = 'PARKED'")
    fun parkedCount(studyId: String, participantId: String, deviceId: String, epoch: String): Int

    @Query("SELECT * FROM upload_diagnostics WHERE studyId = :studyId AND participantId = :participantId AND deviceId = :deviceId AND enrollmentEpoch = :epoch AND deliveryState = 'PARKED' ORDER BY firstOccurredAt, id LIMIT 500 OFFSET :offset")
    fun parked(studyId: String, participantId: String, deviceId: String, epoch: String, offset: Int): List<UploadDiagnosticEntity>

    @Query("SELECT COUNT(*) FROM upload_diagnostics WHERE studyId = :studyId AND participantId = :participantId AND deviceId = :deviceId AND enrollmentEpoch = :epoch AND deliveryState = 'DELIVERED'")
    fun deliveredCount(studyId: String, participantId: String, deviceId: String, epoch: String): Int

    @Query("SELECT * FROM upload_diagnostics WHERE studyId = :studyId AND participantId = :participantId AND deviceId = :deviceId AND enrollmentEpoch = :epoch AND deliveryState = 'DELIVERED' ORDER BY firstOccurredAt, id LIMIT 500 OFFSET :offset")
    fun delivered(studyId: String, participantId: String, deviceId: String, epoch: String, offset: Int): List<UploadDiagnosticEntity>

    @Query("SELECT * FROM upload_diagnostics WHERE id IN (:ids)")
    fun byIds(ids: Set<String>): List<UploadDiagnosticEntity>

    @Query("UPDATE upload_diagnostics SET deliveryState = 'DELIVERED', sealedForUpload = 1 WHERE id IN (:ids) AND studyId = :studyId AND participantId = :participantId AND deviceId = :deviceId AND enrollmentEpoch = :epoch")
    fun acknowledge(ids: Set<String>, studyId: String, participantId: String, deviceId: String, epoch: String): Int

    @Query("SELECT * FROM upload_diagnostics WHERE id = :id")
    fun get(id: String): UploadDiagnosticEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun upsert(row: UploadDiagnosticEntity)

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    fun insertIfAbsent(row: UploadDiagnosticEntity): Long

    @Query("DELETE FROM upload_diagnostics WHERE studyId = :studyId AND participantId = :participantId")
    fun eraseEnrollment(studyId: String, participantId: String): Int

    @Query("DELETE FROM upload_diagnostics")
    fun eraseAll(): Int

    @Query("SELECT * FROM diagnostic_import_checkpoints WHERE digest = :digest")
    fun checkpoint(digest: String): DiagnosticImportCheckpointEntity?

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    fun insertCheckpoint(checkpoint: DiagnosticImportCheckpointEntity)
}

@Dao
interface LocalDataQuarantineDao {
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    fun insert(row: LocalDataQuarantineEntity): Long

    @Query("SELECT * FROM local_data_quarantine WHERE sourceTable = :sourceTable AND sourceId = :sourceId")
    fun get(sourceTable: String, sourceId: String): LocalDataQuarantineEntity?

    @Query("SELECT * FROM local_data_quarantine WHERE sourceTable = :sourceTable")
    fun forSource(sourceTable: String): List<LocalDataQuarantineEntity>

    @Update
    fun update(row: LocalDataQuarantineEntity)

    @Delete
    fun delete(row: LocalDataQuarantineEntity)

    @Query("SELECT COUNT(*) FROM local_data_quarantine WHERE sourceTable = :sourceTable")
    fun count(sourceTable: String): Int

    @Query("DELETE FROM local_data_quarantine WHERE sourceTable IN (:sourceTables)")
    fun eraseSources(sourceTables: List<String>): Int

    @Query("DELETE FROM local_data_quarantine WHERE studyId = :studyId AND participantId = :participantId")
    fun eraseEnrollment(studyId: String, participantId: String): Int

    @Query("DELETE FROM local_data_quarantine")
    fun eraseAll(): Int
}
