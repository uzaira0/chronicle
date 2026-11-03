package com.openlattice.chronicle.services.upload

import android.content.Context
import com.openlattice.chronicle.android.UploadReceipt
import com.openlattice.chronicle.storage.ChronicleDb
import com.openlattice.chronicle.storage.UploadQueueTable
import com.openlattice.chronicle.storage.UploadServerEntity
import java.lang.reflect.Type
import java.util.UUID

/** One dedicated stream: its queue table, DAO and route. Its 2xx acknowledges the whole body. */
internal class DedicatedUploadSource<T : Any, D : Any>(
    val table: UploadQueueTable,
    val elementType: Type,
    val getOldest: (Int) -> List<T>,
    val idOf: (T) -> String,
    val toDto: (T) -> D,
    val deleteByIds: (List<String>) -> Unit,
    val plainUpload: (UUID, UploadServerEntity, Map<String, String>, CollectedUploadBody<D>) -> Unit,
)

/** Drains a dedicated stream through [QueueUpload]; a module this distribution does not support uploads nothing. */
internal class DedicatedUploadBatch<T : Any, D : Any>(
    private val context: Context,
    private val db: ChronicleDb,
    private val server: UploadServerEntity,
    private val source: DedicatedUploadSource<T, D>,
    private val stopped: () -> Boolean = { false },
) {
    /** @return 1 when the stream failed and its rows stay queued, otherwise 0. */
    fun execute(onFailure: ((Exception) -> Unit)? = null): Int {
        if (!DistributionUploadPolicy.allows(source.table)) return 0
        val removeFinished = deleteFinishedRows(source.idOf, source.deleteByIds)
        val upload = QueueUpload(context, db, server, object : QueueStream<T, D> {
            override val table = source.table
            override val elementType = source.elementType
            override fun next(limit: Int) = source.getOldest(limit)
            override fun rowId(item: T) = source.idOf(item)
            override fun toWire(item: T) = source.toDto(item)
            override fun post(headers: Map<String, String>, body: CollectedUploadBody<D>): UploadReceipt? {
                source.plainUpload(UUID.fromString(server.studyId), server, headers, body)
                return null
            }
            override fun remove(done: List<T>) = removeFinished(done)
            override fun delivered(items: List<T>) = recordDelivered(db, server, source.table, items.size)
        })
        return if (onFailure == null) upload.execute(stopped) else upload.execute(stopped, onFailure = onFailure)
    }
}
