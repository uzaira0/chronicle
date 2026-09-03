package com.openlattice.chronicle.collection.device

import android.content.Context
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

    private fun lastRunMs(moduleId: CollectionModuleId): Long? =
        prefs.getLong(lastRunKey(moduleId), 0L).takeIf { it > 0L }

    private fun intervalKey(moduleId: CollectionModuleId) = "interval_${moduleId.id}"
    private fun lastRunKey(moduleId: CollectionModuleId) = "lastrun_${moduleId.id}"

    public companion object {
        private const val PREFS = "expansion_pull_schedule"

        /** Default interval until the first settings sync populates one — the model default (900s). */
        public const val DEFAULT_INTERVAL_SECONDS: Long = 900L

        /** Drift tolerance so a worker firing slightly early still fires an N-tick interval on tick N. */
        public const val DUE_TOLERANCE_MS: Long = 120_000L

        /**
         * The modules whose per-module interval is enforced by a periodic-worker last-run gate.
         * [CollectionLoopCoordinator] writes their intervals here from the resolved settings.
         */
        @JvmField
        public val INTERVAL_GATED_MODULES: List<CollectionModuleId> = listOf(
            CollectionModuleId.CONNECTIVITY_STATE,
            CollectionModuleId.DEVICE_SETTINGS,
            CollectionModuleId.APP_NETWORK_USAGE,
            CollectionModuleId.HEALTH_CONNECT,
            CollectionModuleId.BATTERY_TELEMETRY,
        )

        /**
         * Pure due check: due when never run before, or when the elapsed time since the last
         * successful run is at least the interval (less a small drift tolerance). Extracted so the
         * gate is unit-testable without Android.
         */
        @JvmStatic
        public fun dueByElapsed(lastRunMs: Long?, intervalSeconds: Long, nowMs: Long): Boolean {
            if (lastRunMs == null) return true
            val elapsed = nowMs - lastRunMs
            return elapsed >= (intervalSeconds * 1_000L - DUE_TOLERANCE_MS)
        }
    }
}
