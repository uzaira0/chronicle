package com.openlattice.chronicle.services.sync

import android.content.Context
import androidx.core.content.edit

private const val PREFS_NAME = "chronicle_sync_runtime_config"
private const val KEY_STRATEGY = "strategy"
private const val KEY_INTERVAL_MINUTES = "interval_minutes"
private const val KEY_REQUIRES_BATTERY_NOT_LOW = "requires_battery_not_low"
private const val MIN_PERIODIC_INTERVAL_MINUTES = 15L

data class SyncRuntimeConfig(
    val strategy: ChronicleSyncStrategy = ChronicleSyncStrategy.DEFAULT,
    val intervalMinutes: Long = MIN_PERIODIC_INTERVAL_MINUTES,
    val requiresBatteryNotLow: Boolean = false
) {
    companion object {
        fun load(context: Context): SyncRuntimeConfig {
            val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            val interval = prefs.getLong(KEY_INTERVAL_MINUTES, MIN_PERIODIC_INTERVAL_MINUTES)
                .coerceAtLeast(MIN_PERIODIC_INTERVAL_MINUTES)
            return SyncRuntimeConfig(
                strategy = ChronicleSyncStrategy.fromConfigValue(prefs.getString(KEY_STRATEGY, null)),
                intervalMinutes = interval,
                requiresBatteryNotLow = prefs.getBoolean(KEY_REQUIRES_BATTERY_NOT_LOW, false)
            )
        }

        fun save(
            context: Context,
            strategy: ChronicleSyncStrategy? = null,
            intervalMinutes: Long? = null,
            requiresBatteryNotLow: Boolean? = null
        ): SyncRuntimeConfig {
            val current = load(context)
            val next = current.copy(
                strategy = strategy ?: current.strategy,
                intervalMinutes = (intervalMinutes ?: current.intervalMinutes)
                    .coerceAtLeast(MIN_PERIODIC_INTERVAL_MINUTES),
                requiresBatteryNotLow = requiresBatteryNotLow ?: current.requiresBatteryNotLow
            )
            context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit {
                putString(KEY_STRATEGY, next.strategy.configValue)
                putLong(KEY_INTERVAL_MINUTES, next.intervalMinutes)
                putBoolean(KEY_REQUIRES_BATTERY_NOT_LOW, next.requiresBatteryNotLow)
            }
            return next
        }
    }
}
