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
                val evictCount = lowStorageEvictionCount(context.filesDir.usableSpace, queue.getSize())
                if (evictCount > 0) {
                    val evicted = queue.deleteOldest(evictCount)
                    Log.e(
                        UPLOAD_WORKER_DELEGATE_TAG,
                        "LOW-STORAGE DROP: permanently removed $evicted oldest queued usage row(s) " +
                            "because device storage is below $LOW_STORAGE_BYTES bytes",
                    )
                    try {
                        LocalUploadDiagnosticsStore.of(context).recordOperational(
                            LocalUploadModuleFamily.USAGE_LIFECYCLE,
                            LocalOperationalIssue.USAGE_QUEUE_EVICTED,
                            evicted,
                        )
                    } catch (e: Exception) {
                        Log.w(UPLOAD_WORKER_DELEGATE_TAG, "Failed to record usage eviction diagnostic", e)
                    }
                }
                updateUploadQueueSize(context, queue.getSize())
            },
        )
    }
}

/** Free space below which the usage queue sheds its oldest rows instead of filling the device. */
internal const val LOW_STORAGE_BYTES: Long = 200L * 1024 * 1024

/**
 * The usage queue has no row cap: it keeps everything the server has not received, however long
 * that takes. Only when the device itself runs low on storage does it drop the oldest tenth per
 * run (at least one row), so collection and the rest of the phone keep working.
 */
internal fun lowStorageEvictionCount(usableBytes: Long, queueSize: Int): Int =
    if (usableBytes >= LOW_STORAGE_BYTES || queueSize <= 0) 0 else maxOf(1, queueSize / 10)

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
