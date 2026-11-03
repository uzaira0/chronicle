package com.openlattice.chronicle.services.upload

import java.time.OffsetDateTime

import android.content.Context
import android.util.Log
import com.openlattice.chronicle.constants.TelemetryEvents
import com.openlattice.chronicle.storage.ChronicleDb
import com.openlattice.chronicle.storage.insertOwnedDay
import com.openlattice.chronicle.telemetry.LocalTelemetry
import java.time.LocalDate

private val UPLOAD_WORKER_DELEGATE_TAG = UploadWorkerDelegate::class.java.simpleName

class UploadWorkerDelegate(
    private val context: Context,
    private val chronicleDb: ChronicleDb
) {
    /**
     * @return 1 when the active study server failed during this run, otherwise 0.
     *   Caller (worker) uses the count to decide between Result.success/retry/failure
     *   so partial-failure runs aren't silently reported as success.
     */
    fun execute(stopped: () -> Boolean = { false }): Int {
        Log.i(UPLOAD_WORKER_DELEGATE_TAG, "Usage upload started")
        LocalTelemetry.logEvent(TelemetryEvents.UPLOAD_START, null)

        val policy = UploadPolicy(context, chronicleDb)
        val destination = policy.resolveDestination()
        val server = destination.server
        if (server == null) {
            LocalUploadDiagnosticsStore.of(context).record(
                LocalUploadModuleFamily.USAGE_LIFECYCLE,
                requireNotNull(destination.issue),
            )
            Log.w(UPLOAD_WORKER_DELEGATE_TAG, "Active enrollment has no eligible upload server")
            return 1
        }
        return UploadExecutor(context, chronicleDb).uploadForServer(server, stopped) { error ->
            handleServerUploadFailure(context, UPLOAD_WORKER_DELEGATE_TAG, error,
                LocalUploadModuleFamily.USAGE_LIFECYCLE, server.consecutiveFailures) { failures, errorMsg ->
                chronicleDb.uploadServerDao().recordUsageUploadFailure(server.id, OffsetDateTime.now().toString(), errorMsg, failures)
            }
            val today = LocalDate.now().toString()
            chronicleDb.uploadStatsDao().insertOwnedDay(server, today)
            chronicleDb.uploadStatsDao().incrementUsageFailureCount(server.id, today, 1)
        }
    }
}

/** Reserve for existing data and the diagnostics journal. Collection pauses below this level. */
internal const val LOW_STORAGE_BYTES: Long = 200L * 1024 * 1024
internal const val LOCAL_STORAGE_RESERVE_BYTES: Long = LOW_STORAGE_BYTES
