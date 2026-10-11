package com.openlattice.chronicle.services.upload

import android.content.Context
import com.openlattice.chronicle.crypto.EncryptedPayloadType
import com.openlattice.chronicle.serialization.JsonSerializer
import com.openlattice.chronicle.storage.ChronicleDb
import com.openlattice.chronicle.storage.PendingUploadBatchEntity
import com.openlattice.chronicle.storage.UploadServerEntity
import java.util.UUID

private const val MAX_SQL_BIND_PARAMETERS = 900

private fun batchKey(server: UploadServerEntity, type: EncryptedPayloadType) = "${server.id}:${server.createdAt}:${type.name}"

internal fun <T> selectPendingUploadBatch(
    db: ChronicleDb, server: UploadServerEntity, type: EncryptedPayloadType,
    loadNew: () -> List<T>, loadByIds: (List<String>) -> List<T>, idOf: (T) -> String,
): List<T> {
    var rows = emptyList<T>()
    db.runInTransaction {
        val dao = db.pendingUploadBatchDao()
        val key = batchKey(server, type)
        val pending = dao.get(key)
        if (pending == null) {
            replayRejectedUploadRows(db, server, type)
            rows = loadNew()
            if (rows.isNotEmpty()) {
                val versions = rows.map(idOf).chunked(MAX_SQL_BIND_PARAMETERS)
                    .flatMap { db.uploadQueueEvidenceDao().get(type.name, it) }
                    .associate { it.rowId to it.settingsVersion }
                val version = versions[idOf(rows.first())]
                rows = rows.takeWhile { versions[idOf(it)] == version }
                dao.insert(PendingUploadBatchEntity(key, type.name,
                    JsonSerializer.toJson(rows.map(idOf)), UUID.randomUUID().toString(), version))
            }
        } else {
            val ids = checkNotNull(JsonSerializer.fromJson<List<String>>(pending.rowIds))
            val byId = ids.chunked(MAX_SQL_BIND_PARAMETERS).flatMap(loadByIds).associateBy(idOf)
            rows = ids.mapNotNull(byId::get)
            if (rows.isEmpty()) dao.delete(key)
        }
    }
    return rows
}

internal fun pendingUploadBatchIdentity(db: ChronicleDb, server: UploadServerEntity, type: EncryptedPayloadType): String =
    checkNotNull(db.pendingUploadBatchDao().get(batchKey(server, type))).identity

internal fun pendingUploadBatchVersion(db: ChronicleDb, server: UploadServerEntity, type: EncryptedPayloadType): Int? =
    db.pendingUploadBatchDao().get(batchKey(server, type))?.settingsVersion

/** Call inside the transaction that acknowledges/deletes the selected queue rows. */
internal fun acknowledgePendingUploadBatch(
    db: ChronicleDb, server: UploadServerEntity, type: EncryptedPayloadType, context: Context,
    resetBatchLimit: Boolean = true,
) {
    if (resetBatchLimit) UploadRetryGate.resetBatchLimit(context, server, UploadRetryGate.familyFor(type))
    db.pendingUploadBatchDao().delete(batchKey(server, type))
}
