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
    private val consentScope: () -> Pair<String, Long>? = { null },
    private val overlapMillis: Long = DEFAULT_HEALTH_BACKFILL_MILLIS,
) {
    private var pendingEndMillis: Long? = null
    private var pendingScope: Pair<String, Long>? = null
    private var pendingCheckpoint: Long? = null

    private var readVersion = 0L
    private var reading = false

    public fun <T> read(nowMillis: Long, readWindow: (startMillis: Long, endMillis: Long) -> List<T>): List<T> {
        val version = synchronized(this) {
            check(pendingEndMillis == null && !reading) { "Previous Health Connect read has not been acknowledged" }
            reading = true
            readVersion
        }
        try {
            val scope = consentScope()
            val previous = checkpoint.read()
            if (previous != null && previous >= nowMillis) return emptyList()
            val startMillis = maxOf(scope?.second ?: Long.MIN_VALUE,
                previous?.minus(overlapMillis) ?: (nowMillis - defaultBackfillMillis))
            if (startMillis >= nowMillis) return emptyList()
            val records = readWindow(startMillis, nowMillis)
            val current = consentScope()
            return synchronized(this) {
                if (version != readVersion || scope != current) emptyList()
                else {
                    pendingScope = scope
                    pendingCheckpoint = previous
                    pendingEndMillis = nowMillis
                    records
                }
            }
        } finally { synchronized(this) { reading = false } }
    }

    /** Persists the pending window after its records have been durably queued. */
    @Synchronized
    public fun acknowledge() {
        val endMillis = pendingEndMillis ?: return
        if (pendingScope != consentScope() || pendingCheckpoint != checkpoint.read()) {
            reject()
            return
        }
        checkpoint.write(endMillis)
        pendingEndMillis = null
        pendingScope = null
        pendingCheckpoint = null
    }

    /** Leaves the durable checkpoint unchanged so a failed persistence attempt can retry. */
    @Synchronized
    public fun reject() {
        readVersion++
        pendingEndMillis = null
        pendingScope = null
        pendingCheckpoint = null
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
