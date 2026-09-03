package com.openlattice.chronicle.collection.device

import android.app.usage.NetworkStats
import android.app.usage.NetworkStatsManager
import android.content.Context
import android.net.ConnectivityManager
import com.openlattice.chronicle.collection.NetworkUsageType

private const val PREFS = "chronicle_app_network_usage"
private const val KEY_LAST_END = "last_end_millis"
private const val KEY_PENDING_END = "pending_end_millis"

/** Default backfill window on first run (avoid querying the device's entire history). */
private const val DEFAULT_BACKFILL_MILLIS = 24L * 60 * 60 * 1000

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
public class AndroidAppNetworkUsageSource(context: Context) : AppNetworkUsageSource {

    private val appContext = context.applicationContext
    private val prefs = appContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private var pendingEndMillis: Long? = null

    @Suppress("DEPRECATION") // ConnectivityManager.TYPE_* are the args NetworkStatsManager.querySummary takes.
    @Synchronized
    override fun read(): List<AppNetworkUsageReading> {
        val nsm = appContext.getSystemService(Context.NETWORK_STATS_SERVICE) as? NetworkStatsManager
            ?: throw IllegalStateException("NetworkStatsManager unavailable")

        val persistedPendingEnd = prefs.getLong(KEY_PENDING_END, -1L).takeIf { it >= 0L }
        val now = pendingEndMillis ?: persistedPendingEnd ?: System.currentTimeMillis().also { candidate ->
            if (!prefs.edit().putLong(KEY_PENDING_END, candidate).commit()) {
                throw IllegalStateException("app network pending-window commit failed")
            }
            pendingEndMillis = candidate
        }
        pendingEndMillis = now
        val start = prefs.getLong(KEY_LAST_END, now - DEFAULT_BACKFILL_MILLIS)
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
        if (!prefs.edit().putLong(KEY_LAST_END, end).remove(KEY_PENDING_END).commit()) {
            throw IllegalStateException("app network checkpoint commit failed")
        }
        pendingEndMillis = null
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
