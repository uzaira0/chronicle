package com.openlattice.chronicle.collection.device

import android.app.usage.NetworkStats
import android.app.usage.NetworkStatsManager
import android.content.Context
import android.net.ConnectivityManager
import com.openlattice.chronicle.collection.NetworkUsageType
import com.openlattice.chronicle.storage.ChronicleDb
import java.time.OffsetDateTime

private const val PREFS = "chronicle_app_network_usage"
private const val KEY_LAST_END = "last_end_millis"
private const val KEY_PENDING_END = "pending_end_millis"
private const val KEY_ENROLLMENT = "enrollment_epoch"
private const val KEY_RESET_AT = "reset_at_millis"

/**
 * One per-app, per-network usage bucket produced by an [AppNetworkUsageSource]. Carries no id or
 * sample timestamp — [AppNetworkUsageCollectionModule] adds those. Volume counts only.
 */
public data class AppNetworkUsageReading(
    public val packageName: String,
    public val networkType: NetworkUsageType,
    public val rxBytes: Long,
    public val txBytes: Long,
    public val bucketStartMillis: Long,
    public val bucketEndMillis: Long,
)

/** Dependency-inversion seam for reading per-app network usage. Production impl: [AndroidAppNetworkUsageSource]. */
public fun interface AppNetworkUsageSource {
    /** Reads per-app usage buckets accumulated since the last successful read; empty if none. */
    public fun read(): List<AppNetworkUsageReading>

    /** Called only after every returned bucket has been durably queued. */
    public fun acknowledgeRead() {}

    /** Called when mapping or persistence fails so the same window remains retryable. */
    public fun rejectRead() {}
}

/**
 * Production [AppNetworkUsageSource] over `NetworkStatsManager`. Queries the Wi-Fi and cellular
 * summary for the window since the last successful read (a SharedPreferences checkpoint), sums
 * bytes per app, and resolves each uid to a package name (or a `uid:N` form). Volume counts only —
 * never payloads, destinations, domains, or URLs. Reuses the Usage Access grant the app holds.
 */
public class AndroidAppNetworkUsageSource(
    context: Context,
    private val consentEpoch: () -> Pair<String, Long>? = { null },
) : AppNetworkUsageSource {

    public companion object {
        private val sources = java.util.WeakHashMap<AndroidAppNetworkUsageSource, Unit>()

        public fun clearCheckpoint(context: Context) {
            val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            synchronized(sources) {
                sources.keys.filter { it.prefs == prefs }.forEach { source -> synchronized(source) {
                    source.pendingEndMillis = null
                    source.pendingEnrollment = null
                } }
                check(prefs.edit().clear().putLong(KEY_RESET_AT, System.currentTimeMillis()).commit()) {
                    "app network checkpoint erasure failed"
                }
            }
        }
    }

    private val appContext = context.applicationContext
    private val prefs = appContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private var pendingEndMillis: Long? = null
    private var pendingEnrollment: String? = null

    init { synchronized(sources) { sources[this] = Unit } }

    private fun enrollment(): Pair<String, Long> {
        val server = ChronicleDb.getInstance(appContext).uploadServerDao().getConfiguredServer()
            ?: throw IllegalStateException("app network enrollment unavailable")
        val start = OffsetDateTime.parse(server.createdAt).toInstant().toEpochMilli()
        val consent = consentEpoch()
        return (consent?.first ?: "${server.id}:${server.createdAt}:${server.studyId}:${server.participantId}:${server.sourceDeviceId}") to maxOf(start, consent?.second ?: 0)
    }

    private fun adoptLegacyScope(scope: String) {
        val server = ChronicleDb.getInstance(appContext).uploadServerDao().getConfiguredServer() ?: return
        val legacy = "${server.id}:${server.createdAt}:${server.studyId}:${server.participantId}:${server.sourceDeviceId}"
        val consent = consentEpoch()
        val initial = consent == null || (consent.second == 0L && consent.first.substringAfterLast(':').toLongOrNull() in 0L..1L)
        val recorded = prefs.getString(KEY_ENROLLMENT, null)
        if (initial && (recorded == legacy || (recorded == null && prefs.contains(KEY_LAST_END)))) {
            // Preserve both successful and unacknowledged endpoints for this immutable owner.
            check(prefs.edit().putString(KEY_ENROLLMENT, scope).commit()) {
                "app network enrollment checkpoint commit failed"
            }
        }
    }

    @Suppress("DEPRECATION") // ConnectivityManager.TYPE_* are the args NetworkStatsManager.querySummary takes.
    @Synchronized
    override fun read(): List<AppNetworkUsageReading> {
        val (scope, enrollmentStart) = enrollment()
        adoptLegacyScope(scope)
        if (prefs.getString(KEY_ENROLLMENT, null) != scope) {
            check(prefs.edit().remove(KEY_LAST_END).remove(KEY_PENDING_END).putString(KEY_ENROLLMENT, scope).commit()) {
                "app network enrollment checkpoint commit failed"
            }
            pendingEndMillis = null
            pendingEnrollment = null
        }
        val resetAt = prefs.getLong(KEY_RESET_AT, -1L)
        if (consentEpoch() == null && resetAt >= enrollmentStart && !prefs.contains(KEY_LAST_END)) {
            val baseline = maxOf(System.currentTimeMillis(), enrollmentStart)
            check(prefs.edit().putLong(KEY_LAST_END, baseline).remove(KEY_PENDING_END).commit())
            pendingEndMillis = null
            pendingEnrollment = null
            return emptyList()
        }
        val floor = maxOf(enrollmentStart, resetAt)
        val nsm = appContext.getSystemService(Context.NETWORK_STATS_SERVICE) as? NetworkStatsManager
            ?: throw IllegalStateException("NetworkStatsManager unavailable")

        val persistedPendingEnd = prefs.getLong(KEY_PENDING_END, -1L).takeIf { it >= 0L }
        val now = pendingEndMillis ?: persistedPendingEnd ?: maxOf(System.currentTimeMillis(), floor).also { candidate ->
            if (!prefs.edit().putLong(KEY_PENDING_END, candidate).commit()) {
                throw IllegalStateException("app network pending-window commit failed")
            }
            pendingEndMillis = candidate
        }
        pendingEndMillis = now
        pendingEnrollment = scope
        val start = maxOf(floor, prefs.getLong(KEY_LAST_END, enrollmentStart))
        if (start >= now) return emptyList()

        val out = mutableListOf<AppNetworkUsageReading>()
        out += querySummary(nsm, ConnectivityManager.TYPE_WIFI, NetworkUsageType.WIFI, start, now)
        out += querySummary(nsm, ConnectivityManager.TYPE_MOBILE, NetworkUsageType.CELLULAR, start, now)

        // The module advances this only after every returned row is durably queued.
        pendingEndMillis = now
        return out
    }

    @Synchronized
    override fun acknowledgeRead() {
        val end = pendingEndMillis ?: return
        if (pendingEnrollment != prefs.getString(KEY_ENROLLMENT, null) || pendingEnrollment != enrollment().first) {
            pendingEndMillis = null
            pendingEnrollment = null
            return
        }
        if (!prefs.edit().putLong(KEY_LAST_END, end).remove(KEY_PENDING_END).commit()) {
            throw IllegalStateException("app network checkpoint commit failed")
        }
        pendingEndMillis = null
        pendingEnrollment = null
    }

    @Synchronized
    override fun rejectRead() {
        // Keep the exact pending endpoint, including across process death, so a retry reads the
        // identical window and produces the same deterministic sample ids.
    }

    private fun querySummary(
        nsm: NetworkStatsManager,
        networkType: Int,
        usageType: NetworkUsageType,
        start: Long,
        end: Long,
    ): List<AppNetworkUsageReading> {
        val rxByUid = HashMap<Int, Long>()
        val txByUid = HashMap<Int, Long>()
        val stats: NetworkStats = nsm.querySummary(networkType, null, start, end)
            ?: throw IllegalStateException("querySummary($usageType) returned null")
        stats.use { s ->
            val bucket = NetworkStats.Bucket()
            while (s.hasNextBucket()) {
                s.getNextBucket(bucket)
                rxByUid[bucket.uid] = (rxByUid[bucket.uid] ?: 0L) + bucket.rxBytes
                txByUid[bucket.uid] = (txByUid[bucket.uid] ?: 0L) + bucket.txBytes
            }
        }

        return rxByUid.keys.groupBy(::packageForUid).map { (packageName, uids) ->
            AppNetworkUsageReading(
                packageName = packageName,
                networkType = usageType,
                rxBytes = uids.sumOf { uid -> rxByUid[uid] ?: 0L },
                txBytes = uids.sumOf { uid -> txByUid[uid] ?: 0L },
                bucketStartMillis = start,
                bucketEndMillis = end,
            )
        }
    }

    private fun packageForUid(uid: Int): String {
        val pkgs = runCatching { appContext.packageManager.getPackagesForUid(uid) }.getOrNull()
        return pkgs?.firstOrNull() ?: "uid:$uid"
    }
}
