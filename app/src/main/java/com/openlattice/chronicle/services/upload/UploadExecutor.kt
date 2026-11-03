package com.openlattice.chronicle.services.upload

import android.content.Context
import com.openlattice.chronicle.android.ChronicleSample
import com.openlattice.chronicle.android.ChronicleUsageEvent
import com.openlattice.chronicle.api.ChronicleStudyApi
import com.openlattice.chronicle.models.ExtractedUsageEvent
import com.openlattice.chronicle.serialization.JsonSerializer
import com.openlattice.chronicle.storage.ChronicleDb
import com.openlattice.chronicle.storage.QueueEntry
import com.openlattice.chronicle.storage.UploadQueueTable
import com.openlattice.chronicle.storage.UploadServerEntity
import com.openlattice.chronicle.storage.queueKey
import com.openlattice.chronicle.storage.updateUsageData
import com.openlattice.chronicle.utils.Utils.updateUploadInfo
import java.io.IOException
import java.lang.reflect.Type
import java.time.OffsetDateTime
import java.util.UUID
import org.json.JSONArray

/** One sample of a queued usage row; [index] locates it when the row is rewritten. */
internal data class UsageItem(val row: QueueEntry, val index: Int, val sample: ChronicleSample)

/**
 * The usage stream. A queue row carries up to 1,000 events, so rows are read past an in-memory cursor:
 * events this distribution cannot upload stay in their row, and rows left behind are offered again next run.
 */
class UploadExecutor(
    private val context: Context,
    private val db: ChronicleDb,
    private val studyApiFor: (UploadServerEntity) -> ChronicleStudyApi = { UploadWorker.getChronicleStudyApi(it.url) },
) {
    /** @return 1 when the run failed and its rows stay queued, otherwise 0. */
    fun uploadForServer(server: UploadServerEntity, stopped: () -> Boolean, onFailure: (Exception) -> Unit): Int {
        val stream = UsageStream(server, studyApiFor(server))
        val failures = QueueUpload(context, db, server, stream).execute(stopped, MAX_USAGE_BATCHES_PER_RUN, onFailure)
        stream.latest?.let { updateUploadInfo(context, it) }
        return failures
    }

    private inner class UsageStream(
        private val server: UploadServerEntity,
        private val api: ChronicleStudyApi,
    ) : QueueStream<UsageItem, ChronicleSample> {
        override val table = UploadQueueTable.USAGE
        override val elementType: Type = ChronicleSample::class.java
        override val batchRows = BATCH_SIZE
        private val studyId = UUID.fromString(server.studyId)
        private val queue = db.queueEntryData()
        private var cursor: QueueEntry? = null
        var latest: OffsetDateTime? = null
            private set

        override fun next(limit: Int): List<UsageItem> {
            while (true) {
                val rows = queue.getEntriesAfter(cursor?.writeTimestamp ?: Long.MIN_VALUE, cursor?.id ?: Long.MIN_VALUE, limit)
                if (rows.isEmpty()) return emptyList()
                val captures = db.uploadQueueEvidenceDao().get(UploadQueueTable.USAGE.name, rows.map { it.queueKey })
                    .associate { it.rowId to (it.settingsVersion to it.decisionSeq) }
                val revision = rows.takeWhile { captures[it.queueKey] == captures[rows.first().queueKey] }
                cursor = revision.last()
                revision.flatMap(::items).takeIf { it.isNotEmpty() }?.let { return it }
            }
        }

        private fun items(row: QueueEntry): List<UsageItem> {
            val samples = try { JsonSerializer.deserializeQueueEntry(row.data) } catch (_: IOException) {
                keepReadableItems(row, server)
                return reread(row)
            }
            // Rows queued before client event IDs existed get theirs once, persisted before the first send.
            if (samples.any { it is ExtractedUsageEvent && it.clientEventId == null }) {
                db.updateUsageData(row, JsonSerializer.serializeQueueEntry(samples.map(::withClientEventId)))
                return reread(row)
            }
            return samples.withIndex().filterNot { (_, sample) -> held(sample) }.map { UsageItem(row, it.index, it.value) }
        }

        private fun reread(row: QueueEntry) = queue.getByKeys(listOf(row.queueKey)).singleOrNull()?.let(::items).orEmpty()

        private fun held(sample: ChronicleSample) =
            DistributionUploadPolicy.sharedHeld.isNotEmpty() && (sample !is ExtractedUsageEvent || DistributionUploadPolicy.holds(sample))

        override fun rowId(item: UsageItem) = item.row.queueKey
        override fun toWire(item: UsageItem) =
            mapUsageSamplesForUpload(listOf(item.sample), studyId, server.participantId).single()
        override fun post(headers: Map<String, String>, body: CollectedUploadBody<ChronicleSample>) =
            api.uploadAndroidUsageEventData(studyId, server.participantId, headers, body)

        override fun remove(done: List<UsageItem>) {
            done.groupBy { it.row }.forEach { (row, finished) ->
                val indexes = finished.mapTo(hashSetOf()) { it.index }
                val remaining = JsonSerializer.deserializeQueueEntry(row.data).filterIndexed { index, _ -> index !in indexes }
                if (remaining.isEmpty()) queue.deleteEntry(row)
                else db.updateUsageData(row, JsonSerializer.serializeQueueEntry(remaining))
            }
        }

        override fun wireId(row: ChronicleSample) = checkNotNull((row as ChronicleUsageEvent).clientEventId)

        // Indexes shift when a row is rewritten, so a quarantined event is keyed by its own ID.
        override fun quarantineId(item: UsageItem) =
            "${item.row.queueKey}:${(item.sample as? ExtractedUsageEvent)?.clientEventId ?: UUID.randomUUID()}"
        override fun raw(item: UsageItem) = JsonSerializer.serializeQueueEntry(listOf(item.sample))

        override fun delivered(items: List<UsageItem>) {
            recordDelivered(db, server, UploadQueueTable.USAGE, items.size)
            latest = (items.mapNotNull { (it.sample as? ExtractedUsageEvent)?.timestamp } + listOfNotNull(latest)).maxOrNull()
        }
    }

    /** An unreadable item never hides its readable siblings: it moves to quarantine and the row keeps the rest. */
    private fun keepReadableItems(row: QueueEntry, server: UploadServerEntity) = db.runInTransaction {
        val queue = db.queueEntryData()
        val raw = runCatching { JSONArray(row.data.toString(Charsets.UTF_8)).let { array ->
            List(array.length()) { array.get(it).toString() } } }.getOrNull()
        fun quarantine(id: String, bytes: ByteArray) = quarantineMalformedSample(db, server, UploadQueueTable.USAGE.tableName,
            id, bytes, LocalUploadModuleFamily.USAGE_LIFECYCLE)
        if (raw == null) quarantine(row.queueKey, row.data)
        val readable = raw.orEmpty().mapIndexedNotNull { index, item ->
            runCatching { JsonSerializer.deserializeQueueEntry("[$item]".toByteArray()).single() }.getOrNull()
                ?: null.also { quarantine("${row.queueKey}:item:$index", item.toByteArray()) }
        }
        if (readable.isEmpty()) queue.deleteEntry(row) else db.updateUsageData(row, JsonSerializer.serializeQueueEntry(readable))
    }
}

internal const val MAX_USAGE_BATCHES_PER_RUN = 5
