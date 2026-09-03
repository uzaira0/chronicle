package com.openlattice.chronicle.collection.device

private const val DEFAULT_HEALTH_BACKFILL_MILLIS = 24L * 60 * 60 * 1000

/** Durable timestamp used to resume Health Connect reads without skipping failed windows. */
public interface HealthMetricCheckpoint {
    public fun read(): Long?
    public fun write(endMillis: Long)
}

/**
 * Advances the Health Connect checkpoint only after the complete read window succeeds.
 * A thrown read or checkpoint write leaves the prior checkpoint intact for a later retry.
 */
public class HealthMetricReadCoordinator(
    private val checkpoint: HealthMetricCheckpoint,
    private val defaultBackfillMillis: Long = DEFAULT_HEALTH_BACKFILL_MILLIS,
) {
    private var pendingEndMillis: Long? = null

    @Synchronized
    public fun <T> read(nowMillis: Long, readWindow: (startMillis: Long, endMillis: Long) -> List<T>): List<T> {
        check(pendingEndMillis == null) { "Previous Health Connect read has not been acknowledged" }
        val startMillis = checkpoint.read() ?: (nowMillis - defaultBackfillMillis)
        if (startMillis >= nowMillis) return emptyList()

        val records = readWindow(startMillis, nowMillis)
        pendingEndMillis = nowMillis
        return records
    }

    /** Persists the pending window after its records have been durably queued. */
    @Synchronized
    public fun acknowledge() {
        val endMillis = pendingEndMillis ?: return
        checkpoint.write(endMillis)
        pendingEndMillis = null
    }

    /** Leaves the durable checkpoint unchanged so a failed persistence attempt can retry. */
    @Synchronized
    public fun reject() {
        pendingEndMillis = null
    }
}

/** One page returned by a Health Connect record read. */
public data class HealthMetricPage<T>(
    val records: List<T>,
    val nextPageToken: String?,
)

/** Reads every page and rejects a repeated token instead of looping forever. */
public suspend fun <T> readAllHealthMetricPages(
    readPage: suspend (pageToken: String?) -> HealthMetricPage<T>,
): List<T> {
    val records = mutableListOf<T>()
    val consumedTokens = mutableSetOf<String>()
    var pageToken: String? = null
    do {
        val page = readPage(pageToken)
        records += page.records
        pageToken = page.nextPageToken
        check(pageToken == null || consumedTokens.add(pageToken)) {
            "Health Connect returned a repeated page token"
        }
    } while (pageToken != null)
    return records
}
