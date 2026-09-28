package com.openlattice.chronicle.services.upload

import android.content.Context
import android.util.Log
import com.openlattice.chronicle.storage.ChronicleDb
import java.util.UUID

private const val UPLOAD_DIAGNOSTICS_TAG = "UploadDiagnosticsUploader"

/** Uploads previously recorded failures without recursively recording its own failures. */
internal class UploadDiagnosticsUploader(
    private val context: Context,
    private val db: ChronicleDb,
) {
    /** Returns zero on success/no work and one when diagnostics remain pending after this attempt. */
    fun execute(): Int {
        val server = exactActiveEnrollmentServer(context, db) ?: return 0
        val store = LocalUploadDiagnosticsStore.of(context)
        val pending = store.pending()
        val parked = store.parked()
        val deliveredReplay = store.deliveredReplay()
        if (pending.isEmpty() && parked.isEmpty() && deliveredReplay.isEmpty()) return 0
        var failures = 0
        for (batch in diagnosticsUploadBatches(pending, parked, deliveredReplay)) {
            val events = store.toWireEvents(batch)
            if (events.size != batch.size) {
                // Preserve the raw bucket and take it out of the active upload window.
                val submittedIds = events.mapTo(hashSetOf()) { it.id }
                store.quarantineMalformed(batch.mapTo(hashSetOf()) { it.id } - submittedIds)
            }
            if (events.isEmpty()) continue
            try {
                val studyId = UUID.fromString(server.studyId)
                val acknowledged = UploadWorker.getChronicleStudyApi(
                    server.url,
                    server.mobileSigningSecretOverride,
                ).uploadAndroidUploadDiagnostics(
                    studyId,
                    server.participantId,
                    server.sourceDeviceId,
                    server.apiKey,
                    events,
                ).toSet()
                val submitted = events.mapTo(linkedSetOf()) { it.id }
                if (batch === deliveredReplay) {
                    store.acknowledgeDeliveredReplay(submitted, acknowledged)
                } else {
                    store.acknowledge(submitted.intersect(acknowledged))
                }
                if (!submitted.all(acknowledged::contains)) {
                    Log.w(UPLOAD_DIAGNOSTICS_TAG, "Server did not acknowledge every upload diagnostic")
                    failures++
                }
            } catch (error: Exception) {
                if (uploadHttpStatus(error) == 400) {
                    // Park unsupported codes, retaining their exact IDs and counts. They are
                    // probed again on every cycle so a server upgrade can accept them.
                    store.parkUnsupportedByLegacyServer(events.mapTo(linkedSetOf()) { it.id })
                }
                // Intentionally do not call LocalUploadDiagnosticsStore.recordFailure here: a
                // diagnostic-delivery failure must not create a recursive diagnostic loop.
                Log.w(UPLOAD_DIAGNOSTICS_TAG, "Upload diagnostics remain queued for retry", error)
                failures++
            }
        }
        return if (failures == 0) 0 else 1
    }
}

internal fun diagnosticsUploadBatches(
    pending: List<LocalUploadIssueBucket>,
    parked: List<LocalUploadIssueBucket>,
    deliveredReplay: List<LocalUploadIssueBucket>,
): List<List<LocalUploadIssueBucket>> =
    listOf(pending).filter(List<LocalUploadIssueBucket>::isNotEmpty) +
        parked.groupBy(::serverTier).toSortedMap().values.filter(List<LocalUploadIssueBucket>::isNotEmpty) +
        listOf(deliveredReplay).filter(List<LocalUploadIssueBucket>::isNotEmpty)
