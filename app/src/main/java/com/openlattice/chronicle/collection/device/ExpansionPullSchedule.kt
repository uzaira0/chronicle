package com.openlattice.chronicle.collection.device

import android.content.Context
import com.openlattice.chronicle.collection.CollectionCadenceModules
import com.openlattice.chronicle.collection.CollectionModuleId

/**
 * Per-module collection-interval schedule for the pull-style collection modules whose periodic
 * worker would otherwise sample on every fixed tick (connectivity_state, device_settings,
 * distribution-contributed research modules, health_connect, and battery_telemetry).
 *
 * [CollectionLoopCoordinator] writes each module's resolved `collectionCadence.intervalSeconds`
 * here on every settings sync; the periodic workers ([ExpansionCollectionWorker],
 * `BatteryCollectionWorker`) read it to gate each module's pull so a module samples no more often
 * than the study configured (option B: per-module last-run gate). SharedPreferences-backed, so it
 * survives process death without a Room migration. The immediate "upload now" path is deliberately
 * NOT gated — it always collects fresh (it passes no schedule).
 *
 * The periodic worker tick is the hard floor: an interval shorter than the tick effectively samples
 * once per tick. Longer intervals are honored to within one tick (a small drift tolerance keeps a
 * worker that fires slightly early from pushing an N-tick interval out to N+1 ticks).
 */
public class ExpansionPullSchedule(context: Context) {

    private val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** Records the study-configured collection interval for [moduleId] (from resolved settings). */
    public fun setIntervalSeconds(moduleId: CollectionModuleId, seconds: Long) {
        prefs.edit().putLong(intervalKey(moduleId), seconds).apply()
    }

    /** The configured interval for [moduleId], or [DEFAULT_INTERVAL_SECONDS] until a sync sets one. */
    public fun intervalSeconds(moduleId: CollectionModuleId): Long =
        prefs.getLong(intervalKey(moduleId), DEFAULT_INTERVAL_SECONDS)

    /** Whether [moduleId] is due to sample at [nowMs] given its interval and last successful run. */
    public fun isDue(moduleId: CollectionModuleId, nowMs: Long): Boolean =
        dueByElapsed(lastRunMs(moduleId), intervalSeconds(moduleId), nowMs)

    /** Records a successful sample of [moduleId] at [nowMs], resetting its interval clock. */
    public fun markRan(moduleId: CollectionModuleId, nowMs: Long) {
        prefs.edit().putLong(lastRunKey(moduleId), nowMs).apply()
    }

    /**
     * Atomically claims [moduleId]'s run if due and no other caller holds a live claim. The clock is
     * read under the lock, and the claim is kept apart from the last successful run, so a process
     * killed mid-sample costs at most [CLAIM_ABANDONED_MS], not an interval. Returns the claim time
     * to hand to [completeClaim], or null. Never sample under this lock: the sample takes a
     * persistence lease, and holding the lock across it can deadlock with uploads.
     */
    public fun claimIfDue(moduleId: CollectionModuleId): Long? = synchronized(ExpansionPullSchedule::class.java) {
        val nowMs = System.currentTimeMillis()
        val claimedMs = prefs.getLong(claimKey(moduleId), 0L)
        if (claimedMs > 0L && kotlin.math.abs(nowMs - claimedMs) < CLAIM_ABANDONED_MS) return@synchronized null
        if (!isDue(moduleId, nowMs)) return@synchronized null
        check(prefs.edit().putLong(claimKey(moduleId), nowMs).commit()) { "Unable to claim $moduleId run" }
        nowMs
    }

    /**
     * Ends the claim made at [claimedMs]; only a successful sample advances the last run, and only
     * while the claim is still ours: a claim cleared by erasure or taken over by a newer claimant
     * writes nothing.
     */
    public fun completeClaim(moduleId: CollectionModuleId, claimedMs: Long, succeeded: Boolean) {
        synchronized(ExpansionPullSchedule::class.java) {
            if (prefs.getLong(claimKey(moduleId), 0L) != claimedMs) return
            val editor = prefs.edit().remove(claimKey(moduleId))
            if (succeeded) editor.putLong(lastRunKey(moduleId), claimedMs)
            editor.commit()
        }
    }

    private fun lastRunMs(moduleId: CollectionModuleId): Long? =
        prefs.getLong(lastRunKey(moduleId), 0L).takeIf { it > 0L }

    private fun intervalKey(moduleId: CollectionModuleId) = "interval_${moduleId.id}"
    private fun lastRunKey(moduleId: CollectionModuleId) = "lastrun_${moduleId.id}"
    private fun claimKey(moduleId: CollectionModuleId) = "claim_${moduleId.id}"

    public companion object {
        private const val PREFS = "expansion_pull_schedule"

        /**
         * Erasure's reset, under the monitor [claimIfDue] and [completeClaim] hold, so a claim
         * validated before it cannot commit its last run after it. The caller may hold the
         * persistence write lease: nothing under this monitor ever takes a lease.
         */
        internal fun erase(context: Context): Boolean = synchronized(ExpansionPullSchedule::class.java) {
            context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().clear().commit()
        }

        /** Default interval until the first settings sync populates one — the model default (900s). */
        public const val DEFAULT_INTERVAL_SECONDS: Long = 900L

        /** Drift tolerance so a worker firing slightly early still fires an N-tick interval on tick N. */
        public const val DUE_TOLERANCE_MS: Long = 120_000L

        /** A claim older than this was abandoned (process death mid-sample); the module is claimable again. */
        public const val CLAIM_ABANDONED_MS: Long = 10 * 60_000L

        /**
         * The modules whose per-module interval is enforced by a periodic-worker last-run gate.
         * [CollectionLoopCoordinator] writes their intervals here from the resolved settings.
         * Defined once in chronicle-models; the web study form reads the same list.
         */
        @JvmField
        public val INTERVAL_GATED_MODULES: List<CollectionModuleId> = CollectionCadenceModules.intervalGated

        /**
         * Pure due check: due when never run before, or when the elapsed time since the last
         * successful run is at least the interval (less a small drift tolerance). Extracted so the
         * gate is unit-testable without Android.
         */
        @JvmStatic
        public fun dueByElapsed(lastRunMs: Long?, intervalSeconds: Long, nowMs: Long): Boolean {
            if (lastRunMs == null) return true
            // Further ahead than the drift tolerance: the clock was corrected backwards since.
            if (lastRunMs - nowMs > DUE_TOLERANCE_MS) return true
            val elapsed = nowMs - lastRunMs
            return elapsed >= (intervalSeconds * 1_000L - DUE_TOLERANCE_MS)
        }
    }
}
