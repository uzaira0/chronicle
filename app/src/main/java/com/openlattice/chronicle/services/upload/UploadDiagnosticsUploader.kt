package com.openlattice.chronicle.services.upload

import android.content.Context
import android.util.Log
import com.openlattice.chronicle.api.CollectedUploadHeaders
import com.openlattice.chronicle.collection.AndroidUploadDiagnosticEvent
import com.openlattice.chronicle.collection.state.ResearchPersistenceGate
import com.openlattice.chronicle.storage.ChronicleDb
import com.openlattice.chronicle.storage.UploadServerEntity
import java.util.UUID

private const val UPLOAD_DIAGNOSTICS_TAG = "UploadDiagnosticsUploader"

/** Uploads previously recorded failures without recursively recording its own failures. */
internal class UploadDiagnosticsUploader(private val context: Context, private val db: ChronicleDb) {
    fun execute(): Int = ResearchPersistenceGate.runIfEnrolled(context) { executeActive() } ?: 0

    private fun executeActive(): Int {
        val server = exactActiveEnrollmentServer(context, db) ?: return 0
        val store = LocalUploadDiagnosticsStore.of(context)
        val pending = store.pending()
        return if (pending.isEmpty()) 0 else uploadBatch(server, store, pending)
    }

    private fun uploadBatch(server: UploadServerEntity, store: LocalUploadDiagnosticsStore,
                            batch: List<LocalUploadIssueBucket>): Int {
        val events = store.toWireEvents(batch)
        if (events.size != batch.size) {
            val valid = events.mapTo(hashSetOf()) { it.id }
            store.quarantineMalformed(batch.mapTo(hashSetOf()) { it.id } - valid)
        }
        return try {
            val headers = CollectedUploadHeaders(server.sourceRandomDeviceId, server.apiKey).toMap()
            val api = UploadWorker.getChronicleStudyApi(server.url)
            // A bounded batch of closed issue codes always fits one request.
            val body = checkNotNull(CollectedUploadBody.encode(events, AndroidUploadDiagnosticEvent::class.java))
            val receipt = api.uploadAndroidUploadDiagnostics(UUID.fromString(server.studyId), server.participantId, headers, body)
            // A short or malformed receipt throws here, before any row is settled.
            receipt.validateSubmitted(events.mapTo(linkedSetOf()) { UUID.fromString(it.id) })
            store.settle(receipt)
            0
        } catch (error: Exception) {
            Log.w(UPLOAD_DIAGNOSTICS_TAG, "Upload diagnostics remain queued for retry", error)
            1
        }
    }
}
