package com.openlattice.chronicle.services.upload

import android.content.Context
import android.util.Log
import com.openlattice.chronicle.constants.TelemetryEvents
import com.openlattice.chronicle.preferences.*
import com.openlattice.chronicle.storage.ChronicleDb
import com.openlattice.chronicle.storage.UploadStatsEntity
import com.openlattice.chronicle.telemetry.LocalTelemetry
import com.openlattice.chronicle.utils.Utils.updateUploadQueueSize
import java.time.LocalDate

private val UPLOAD_WORKER_DELEGATE_TAG = UploadWorkerDelegate::class.java.simpleName

class UploadWorkerDelegate(
    private val context: Context,
    private val chronicleDb: ChronicleDb
) {
    private val settings = EnrollmentSettings(context)

    /**
     * @return 1 when the active study server failed during this run, otherwise 0.
     *   Caller (worker) uses the count to decide between Result.success/retry/failure
     *   so partial-failure runs aren't silently reported as success.
     */
    fun execute(): Int {
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
        val servers = listOf(server)

        val executor = UploadExecutor(
            context, chronicleDb, settings.getPropertyTypeIds()
        )

        return runUsageUploadForEligibleServers(
            servers = servers,
            uploadForServer = { server -> executor.uploadForServer(server) },
            recordFailure = { server, e ->
                handleServerUploadFailure(
                    context,
                    UPLOAD_WORKER_DELEGATE_TAG,
                    server,
                    e,
                    LocalUploadModuleFamily.USAGE_LIFECYCLE,
                    server.consecutiveFailures,
                ) { failures, errorMsg ->
                    chronicleDb.uploadServerDao().recordUsageUploadFailure(
                        server.id,
                        java.time.OffsetDateTime.now().toString(),
                        errorMsg,
                        failures
                    )
                }
                val today = LocalDate.now().toString()
                chronicleDb.uploadStatsDao().insertDay(UploadStatsEntity(serverId = server.id, date = today))
                chronicleDb.uploadStatsDao().incrementUsageFailureCount(server.id, today, 1)
            },
            afterUploads = {
                val queue = chronicleDb.queueEntryData()
                val minCursor = chronicleDb.uploadServerDao().getMinUploadQueueCursor()
                if (minCursor != null && minCursor.lastUploadedTimestamp > 0) {
                    queue.deleteEntriesBeforeOrAt(minCursor.lastUploadedTimestamp, minCursor.lastUploadedQueueId)
                }
                updateUploadQueueSize(context, queue.getSize())
            },
        )
    }
}

internal fun runUsageUploadForEligibleServers(
    servers: List<com.openlattice.chronicle.storage.UploadServerEntity>,
    uploadForServer: (com.openlattice.chronicle.storage.UploadServerEntity) -> Unit,
    recordFailure: (com.openlattice.chronicle.storage.UploadServerEntity, Exception) -> Unit,
    afterUploads: () -> Unit,
): Int {
    var failureCount = 0
    for (server in servers) {
        try {
            uploadForServer(server)
        } catch (e: Exception) {
            failureCount++
            recordFailure(server, e)
        }
    }
    afterUploads()
    return failureCount
}
