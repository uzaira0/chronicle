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
        if (pending.isEmpty()) return 0

        val events = store.toWireEvents(pending)
        if (events.isEmpty()) {
            store.acknowledge(pending.mapTo(linkedSetOf()) { it.id })
            return 0
        }

        return try {
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
            if (!submitted.all(acknowledged::contains)) {
                Log.w(UPLOAD_DIAGNOSTICS_TAG, "Server did not acknowledge every upload diagnostic")
                1
            } else {
                store.acknowledge(submitted)
                0
            }
        } catch (error: Exception) {
            // Intentionally do not call LocalUploadDiagnosticsStore.recordFailure here: a
            // diagnostic-delivery failure must not create a recursive diagnostic loop.
            Log.w(UPLOAD_DIAGNOSTICS_TAG, "Upload diagnostics remain queued for retry", error)
            1
        }
    }
}
