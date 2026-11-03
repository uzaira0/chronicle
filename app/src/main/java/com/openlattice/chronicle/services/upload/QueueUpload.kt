package com.openlattice.chronicle.services.upload

import com.openlattice.chronicle.services.enrollment.enqueueEnrollmentStatusRefresh

import android.content.Context
import android.util.Log
import com.openlattice.chronicle.android.UploadReceipt
import com.openlattice.chronicle.api.CollectedUploadHeaders
import com.openlattice.chronicle.collection.state.CollectionAckRetryQueue
import com.openlattice.chronicle.generated.UploadLimits
import com.openlattice.chronicle.serialization.ChronicleCallException
import com.openlattice.chronicle.storage.ChronicleDb
import com.openlattice.chronicle.storage.UploadQueueTable
import com.openlattice.chronicle.storage.UploadServerEntity
import com.openlattice.chronicle.storage.UploadSizeRejection
import com.openlattice.chronicle.storage.insertOwnedDay
import com.openlattice.chronicle.storage.serializeMalformedRow
import java.lang.reflect.Type
import java.net.HttpURLConnection
import java.time.OffsetDateTime
import java.util.UUID
import retrofit2.HttpException

internal const val MAX_SQL_BIND_PARAMETERS = 900

/** One local queue as the upload loop sees it: what to select, how to send it, how to settle it. */
internal interface QueueStream<T : Any, D : Any> {
    val table: UploadQueueTable
    val elementType: Type
    val batchRows: Int get() = QueueUpload.DEFAULT_BATCH_ROWS

    fun next(limit: Int): List<T>

    /** The queue row that carries the item; it keys the capture evidence. */
    fun rowId(item: T): String
    fun toWire(item: T): D

    /** The server's per-row receipt, or null when a 2xx accepts the whole body. */
    fun post(headers: Map<String, String>, body: CollectedUploadBody<D>): UploadReceipt?

    /** Runs inside the acknowledgment transaction. */
    fun remove(done: List<T>)
    fun wireId(row: D): UUID = error("${table.name} uploads have no row receipts")
    fun quarantineId(item: T): String = rowId(item)
    fun raw(item: T): ByteArray = serializeMalformedRow(item)

    /** Runs inside the acknowledgment transaction with the rows the server accepted. */
    fun delivered(items: List<T>) = Unit
}

internal const val UNPROCESSABLE_PAYLOAD_STATUS = 422
internal const val UPLOAD_REJECTED_REASON = "UPLOAD_REJECTED"

/**
 * The upload loop for one local queue. Select a bounded batch of rows captured under one settings revision and one
 * decision sequence, POST it with both, then in one local transaction delete what the server accepted or discarded.
 * Repeat until the queue is empty, the worker stops, or the next rows wait for a decision the server has not received.
 * A lost response deletes nothing: the next attempt sends the same row identities and the server stores each once.
 */
internal class QueueUpload<T : Any, D : Any>(
    private val context: Context,
    private val db: ChronicleDb,
    private val server: UploadServerEntity,
    private val stream: QueueStream<T, D>,
) {
    private val table = stream.table
    private val family = uploadFamily(table)

    private data class Quarantined<T>(val row: T, val reason: String, val httpStatus: Int?)

    private inner class Outcome {
        val done = mutableListOf<T>()
        val quarantined = mutableListOf<Quarantined<T>>()
        val accepted = mutableListOf<T>()
    }

    /** What a batch was captured under; it travels in the request headers. */
    private data class Capture(val settingsVersion: Int?, val decisionSeq: Long?)

    /** @return 1 when the run failed and its rows stay queued for retry, otherwise 0. */
    // Transport, receipt and storage failures all leave the unacknowledged rows queued.
    @Suppress("TooGenericExceptionCaught")
    fun execute(stopped: () -> Boolean, maxBatches: Int = Int.MAX_VALUE,
                onFailure: (Exception) -> Unit = ::recordFailure): Int = try {
        drain(stopped, maxBatches)
        0
    } catch (error: Exception) {
        onFailure(error)
        1
    }

    private fun drain(stopped: () -> Boolean, maxBatches: Int) {
        repeat(maxBatches) {
            if (stopped()) return
            val selected = stream.next(stream.batchRows)
            if (selected.isEmpty()) return
            val (rows, capture) = sameCapture(selected) ?: return
            val version = capture.settingsVersion
            val outcome = Outcome()
            try {
                val mapped = rows.mapNotNull { row ->
                    // A row the current app cannot map is kept in quarantine rather than sent or dropped.
                    @Suppress("TooGenericExceptionCaught")
                    try { row to stream.toWire(row) } catch (error: Exception) {
                        Log.w(TAG, "Quarantined ${table.name} row after mapping failure", error)
                        outcome.quarantined += Quarantined(row, LocalOperationalIssue.SAMPLE_QUARANTINED.name, null)
                        null
                    }
                }
                if (mapped.isNotEmpty()) send(mapped, capture, outcome)
            } finally {
                acknowledge(outcome, version)
            }
            if (outcome.accepted.isNotEmpty()) Log.i(TAG, "Uploaded ${outcome.accepted.size} ${table.name}")
        }
    }

    /**
     * The rows captured under the first row's revision and decision sequence, or null while that row waits for a
     * decision this phone has not delivered (B7): the server must judge it with the decision that covers it.
     */
    private fun sameCapture(rows: List<T>): Pair<List<T>, Capture>? {
        val evidence = rows.map(stream::rowId).distinct().chunked(MAX_SQL_BIND_PARAMETERS)
            .flatMap { db.uploadQueueEvidenceDao().get(table.name, it) }
            .associate { it.rowId to Capture(it.settingsVersion, it.decisionSeq) }
        val capture = evidence[stream.rowId(rows.first())] ?: Capture(null, null)
        val floor = CollectionAckRetryQueue.pendingDecisionFloor(db)
        if (capture.decisionSeq != null && floor != null && capture.decisionSeq >= floor) return null
        return rows.filter { (evidence[stream.rowId(it)] ?: Capture(null, null)) == capture } to capture
    }

    private fun send(rows: List<Pair<T, D>>, capture: Capture, outcome: Outcome) {
        val body = CollectedUploadBody.encode(rows.map { it.second }, stream.elementType)
            ?: return split(rows, capture, outcome, UploadSizeRejection.SINGLE_SAMPLE_EXCEEDS_REQUEST_BUDGET.name, null)
        val receipt = try {
            stream.post(CollectedUploadHeaders(server.sourceRandomDeviceId, server.apiKey, capture.settingsVersion,
                capture.decisionSeq).toMap(), body)
        } catch (error: HttpException) {
            return refused(rows, capture, outcome, error, error.code())
        } catch (error: ChronicleCallException) {
            return refused(rows, capture, outcome, error, error.code)
        }
        if (receipt == null) {
            outcome.done += rows.map { it.first }
            outcome.accepted += rows.map { it.first }
            return
        }
        val byId = rows.associateBy { stream.wireId(it.second) }
        // A short or malformed receipt throws here, before any row is removed.
        receipt.validateSubmitted(byId.keys)
        outcome.done += (receipt.acceptedIds + receipt.discardedIds).map { byId.getValue(it).first }
        outcome.accepted += receipt.acceptedIds.map { byId.getValue(it).first }
    }

    /**
     * A body the server refused for its size, or a 400 naming a defect in a row (the row-level code), is split;
     * any other HTTP failure, a request-level 400 included, fails the run and keeps every row queued. A 403 is the
     * server refusing this enrollment (withdrawn, not enrolled, a required module declined, collection unavailable):
     * the status poll runs now, and a hard stop it reports clears the queue.
     */
    private fun refused(rows: List<Pair<T, D>>, capture: Capture, outcome: Outcome, error: Exception, status: Int) =
        when {
            status == HttpURLConnection.HTTP_ENTITY_TOO_LARGE -> split(rows, capture, outcome,
                UploadSizeRejection.SERVER_LIMIT_BELOW_CLIENT_CONTRACT.name, status)
            status == HttpURLConnection.HTTP_BAD_REQUEST && serverErrorCode(error) == UploadLimits.UPLOAD_ROW_REJECTED_CODE ->
                split(rows, capture, outcome, UPLOAD_REJECTED_REASON, status)
            status == HttpURLConnection.HTTP_FORBIDDEN -> {
                enqueueEnrollmentStatusRefresh(context)
                throw error
            }
            else -> throw error
        }

    /** Halves a refused or oversized body; a single refused row moves to quarantine. */
    private fun split(rows: List<Pair<T, D>>, capture: Capture, outcome: Outcome, reason: String, status: Int?) {
        if (rows.size == 1) {
            outcome.quarantined += Quarantined(rows.single().first, reason, status)
            return
        }
        rows.chunked((rows.size + 1) / 2).forEach { send(it, capture, outcome) }
    }

    private fun acknowledge(outcome: Outcome, version: Int?) {
        if (outcome.done.isEmpty() && outcome.quarantined.isEmpty()) return
        db.runInTransaction {
            outcome.quarantined.forEach { (row, reason, httpStatus) ->
                quarantineMalformedSample(db, server, table.tableName, stream.quarantineId(row), stream.raw(row), family,
                    reason = reason, httpStatus = httpStatus, settingsVersion = version)
            }
            stream.remove(outcome.done + outcome.quarantined.map { it.row })
            if (outcome.accepted.isNotEmpty()) stream.delivered(outcome.accepted)
        }
    }

    private fun recordFailure(error: Exception) {
        Log.e(TAG, "Failed to upload ${table.name}", error)
        LocalUploadDiagnosticsStore.of(context).recordFailure(family, error)
    }

    companion object {
        private const val TAG = "QueueUpload"
        const val DEFAULT_BATCH_ROWS = 5000
    }
}

/** Deletes finished rows by ID. */
internal fun <T> deleteFinishedRows(idOf: (T) -> String, delete: (List<String>) -> Unit): (List<T>) -> Unit = { done ->
    done.map(idOf).chunked(MAX_SQL_BIND_PARAMETERS).forEach(delete)
}

/** Counts accepted rows on the day's stats and the enrollment; call inside the acknowledgment transaction. */
internal fun recordDelivered(db: ChronicleDb, server: UploadServerEntity, table: UploadQueueTable, count: Int,
                             newestSensorTimestamp: String? = null) {
    val now = OffsetDateTime.now()
    val today = now.toLocalDate().toString()
    val stats = db.uploadStatsDao()
    val servers = db.uploadServerDao()
    stats.insertOwnedDay(server, today)
    val updated = when (table) {
        UploadQueueTable.USAGE -> {
            stats.incrementUsageCount(server.id, today, count)
            servers.recordUsageUploadSuccess(server.id, now.toString(), count)
        }
        UploadQueueTable.SENSOR -> {
            stats.incrementSensorCount(server.id, today, count)
            servers.recordSensorUploadSuccess(server.id, now.toString(), newestSensorTimestamp, count)
        }
        UploadQueueTable.BATTERY -> {
            stats.incrementBatteryCount(server.id, today, count)
            servers.recordBatteryUploadSuccess(server.id, now.toString(), count)
        }
        else -> return
    }
    check(updated == 1) { "Upload acknowledgment lost its enrollment owner" }
}
