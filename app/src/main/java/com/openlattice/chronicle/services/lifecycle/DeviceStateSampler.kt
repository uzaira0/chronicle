package com.openlattice.chronicle.services.lifecycle

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.BatteryManager
import android.os.PowerManager
import androidx.core.content.edit
import com.openlattice.chronicle.models.ExtractedUsageEvent

private const val PREFS_NAME = "chronicle_device_state"
private const val KEY_BATTERY_STATE = "battery_state"
private const val KEY_NETWORK_STATE = "network_state"
private const val KEY_POWER_SAVE_STATE = "power_save_state"

class DeviceStateSampler(private val context: Context) {
    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun poll(timestampMillis: Long = System.currentTimeMillis()): List<ExtractedUsageEvent> {
        val events = mutableListOf<ExtractedUsageEvent>()
        addChangedState(events, KEY_BATTERY_STATE, batteryState(), timestampMillis)
        addChangedState(events, KEY_NETWORK_STATE, networkState(), timestampMillis)
        addChangedState(events, KEY_POWER_SAVE_STATE, powerSaveState(), timestampMillis)
        return events
    }

    private fun addChangedState(
        events: MutableList<ExtractedUsageEvent>,
        key: String,
        state: LifecycleState,
        timestampMillis: Long
    ) {
        if (prefs.getString(key, null) == state.dedupeValue) return
        prefs.edit { putString(key, state.dedupeValue) }
        events.add(
            DeviceLifecycleEventRecorder.buildEvent(
                activityClass = state.activityClass,
                interactionType = state.interactionType,
                timestampMillis = timestampMillis
            )
        )
    }

    private fun batteryState(): LifecycleState {
        val intent = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val status = intent?.getIntExtra(BatteryManager.EXTRA_STATUS, -1) ?: -1
        val level = intent?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
        val scale = intent?.getIntExtra(BatteryManager.EXTRA_SCALE, -1) ?: -1
        val percentage = if (level >= 0 && scale > 0) (level * 100 / scale) else -1
        val charging = status == BatteryManager.BATTERY_STATUS_CHARGING ||
            status == BatteryManager.BATTERY_STATUS_FULL
        val interactionType = if (charging) INTERACTION_BATTERY_CHARGING else INTERACTION_BATTERY_DISCHARGING
        val bucket = when {
            percentage < 0 -> "unknown"
            percentage <= 15 -> "critical"
            percentage <= 30 -> "low"
            percentage <= 60 -> "medium"
            else -> "high"
        }
        return LifecycleState(
            interactionType = interactionType,
            activityClass = "battery:$bucket:${if (charging) "charging" else "discharging"}",
            dedupeValue = "$bucket:$charging"
        )
    }

    private fun networkState(): LifecycleState {
        val manager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val network = manager.activeNetwork
        val capabilities = network?.let { manager.getNetworkCapabilities(it) }
        val transport = when {
            capabilities == null -> "none"
            capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "wifi"
            capabilities.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> "cellular"
            capabilities.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> "ethernet"
            capabilities.hasTransport(NetworkCapabilities.TRANSPORT_BLUETOOTH) -> "bluetooth"
            capabilities.hasTransport(NetworkCapabilities.TRANSPORT_VPN) -> "vpn"
            else -> "other"
        }
        val connected = transport != "none"
        return LifecycleState(
            interactionType = if (connected) INTERACTION_NETWORK_CONNECTED else INTERACTION_NETWORK_DISCONNECTED,
            activityClass = "network:$transport",
            dedupeValue = transport
        )
    }

    private fun powerSaveState(): LifecycleState {
        val powerManager = context.getSystemService(Context.POWER_SERVICE) as PowerManager
        val enabled = powerManager.isPowerSaveMode
        return LifecycleState(
            interactionType = if (enabled) INTERACTION_POWER_SAVE_MODE_ON else INTERACTION_POWER_SAVE_MODE_OFF,
            activityClass = "power-save:${if (enabled) "on" else "off"}",
            dedupeValue = enabled.toString()
        )
    }

}

private data class LifecycleState(
    val interactionType: String,
    val activityClass: String,
    val dedupeValue: String
)
