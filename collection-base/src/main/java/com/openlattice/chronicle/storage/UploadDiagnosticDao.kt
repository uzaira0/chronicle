package com.openlattice.chronicle.storage

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Update

@Dao
interface UploadDiagnosticDao : BoundedQueueWriter {
    @Query(
        "SELECT * FROM upload_diagnostics WHERE studyId = :studyId AND participantId = :participantId AND randomDeviceId = :randomDeviceId AND " +
        "enrollmentEpoch = :epoch ORDER BY firstOccurredAt, id")
    fun forEnrollment(studyId: String, participantId: String, randomDeviceId: String, epoch: String): List<UploadDiagnosticEntity>

    @Query(
        "SELECT * FROM upload_diagnostics WHERE studyId = :studyId AND participantId = :participantId AND randomDeviceId = :randomDeviceId AND " +
        "enrollmentEpoch = :epoch AND day >= :cutoff AND day <= :today ORDER BY day DESC, moduleFamily, issueCode, firstOccurredAt")
    fun recent(studyId: String, participantId: String, randomDeviceId: String, epoch: String,
        cutoff: String, today: String): List<UploadDiagnosticEntity>

    @Query(
        "SELECT * FROM upload_diagnostics WHERE studyId = :studyId AND participantId = :participantId AND randomDeviceId = :randomDeviceId AND " +
        "enrollmentEpoch = :epoch AND deliveryState = 'PENDING' ORDER BY firstOccurredAt, id LIMIT 500")
    fun pending(studyId: String, participantId: String, randomDeviceId: String, epoch: String): List<UploadDiagnosticEntity>

    @Query("SELECT * FROM upload_diagnostics WHERE id IN (:ids)")
    fun byIds(ids: Set<String>): List<UploadDiagnosticEntity>

    /** Settles only this enrollment's PENDING rows; a late receipt never rewrites an earlier outcome. */
    @Query(
        "UPDATE upload_diagnostics SET deliveryState = :outcome WHERE id IN (:ids) AND studyId = :studyId AND " +
        "participantId = :participantId AND randomDeviceId = :randomDeviceId AND enrollmentEpoch = :epoch AND deliveryState = 'PENDING'")
    fun settle(ids: Set<String>, outcome: DiagnosticDeliveryState, studyId: String, participantId: String, randomDeviceId: String,
        epoch: String): Int

    @Query("SELECT * FROM upload_diagnostics WHERE id = :id")
    fun get(id: String): UploadDiagnosticEntity?

    /** Both writes store occurrence times in the one sortable form: pending rows upload oldest first. */
    @Transaction
    fun upsert(row: UploadDiagnosticEntity) {
        boundedRows(UploadQueueTable.DIAGNOSTICS, listOf(row.stored()), UploadDiagnosticEntity::persistedPayloadBytes) { it.id }
            .forEach(::upsertUnchecked)
    }

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun upsertUnchecked(row: UploadDiagnosticEntity)

    @Transaction
    fun insertIfAbsent(row: UploadDiagnosticEntity): Long {
        val bounded = boundedRows(UploadQueueTable.DIAGNOSTICS, listOf(row.stored()), UploadDiagnosticEntity::persistedPayloadBytes) { it.id }
        return bounded.singleOrNull()?.let(::insertIfAbsentUnchecked) ?: -1L
    }

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    fun insertIfAbsentUnchecked(row: UploadDiagnosticEntity): Long

    @Query("DELETE FROM upload_diagnostics WHERE studyId = :studyId AND participantId = :participantId")
    fun eraseEnrollment(studyId: String, participantId: String): Int

    @Query("DELETE FROM upload_diagnostics")
    fun eraseAll(): Int


}

private fun UploadDiagnosticEntity.stored() =
    copy(firstOccurredAt = storedInstant(firstOccurredAt), lastOccurredAt = storedInstant(lastOccurredAt))

@Dao
interface LocalDataQuarantineDao {
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    fun insert(row: LocalDataQuarantineEntity): Long

    @Query("SELECT * FROM local_data_quarantine WHERE sourceTable = :sourceTable AND sourceId = :sourceId")
    fun get(sourceTable: String, sourceId: String): LocalDataQuarantineEntity?

    @Query("SELECT * FROM local_data_quarantine WHERE sourceTable = :sourceTable")
    fun forSource(sourceTable: String): List<LocalDataQuarantineEntity>

    @Query("SELECT id, sourceTable, sourceId, studyId, participantId, randomDeviceId, X'' AS rawData, reason, createdAt, " +
        "settingsVersion, httpStatus, appVersion " +
        "FROM local_data_quarantine WHERE sourceTable=:sourceTable")
    fun archiveMetadata(sourceTable: String): List<LocalDataQuarantineEntity>

    @Query("SELECT substr(rawData, :offset, :length) FROM local_data_quarantine WHERE id=:id")
    fun archiveChunk(id: String, offset: Int, length: Int): ByteArray

    @Query("SELECT q.id, q.sourceTable, q.createdAt FROM local_data_quarantine q WHERE q.studyId=:studyId AND " +
        "q.participantId=:participantId AND q.randomDeviceId=:randomDeviceId AND q.reason=:reason AND " +
        "NOT EXISTS (SELECT 1 FROM upload_diagnostics d WHERE d.id=q.id)")
    fun unreportedOversizedRows(studyId: String, participantId: String, randomDeviceId: String, reason: String): List<QuarantinedQueueRow>

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
