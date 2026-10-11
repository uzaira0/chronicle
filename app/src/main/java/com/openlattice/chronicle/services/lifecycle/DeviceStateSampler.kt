package com.openlattice.chronicle.services.lifecycle

import android.content.Context
import com.openlattice.chronicle.utils.registerSystemBroadcastReceiver
import android.content.Intent
import android.content.IntentFilter
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.BatteryManager
import android.os.PowerManager
import com.openlattice.chronicle.models.ExtractedUsageEvent
import com.openlattice.chronicle.storage.ChronicleDb

class DeviceStateSampler(private val context: Context) {

    fun poll(timestampMillis: Long = System.currentTimeMillis()): List<ExtractedUsageEvent> {
        val origin = com.openlattice.chronicle.collection.state.ResearchPersistenceGate.captureObservation(
            context, com.openlattice.chronicle.collection.CollectionModuleId.DEVICE_LIFECYCLE)
        val events = mutableListOf<ExtractedUsageEvent>()
        origin.persist {
        addChangedState(events, batteryState(), timestampMillis)
        addChangedState(events, networkState(), timestampMillis)
        addChangedState(events, powerSaveState(), timestampMillis)
        }
        return events
    }

    private fun addChangedState(
        events: MutableList<ExtractedUsageEvent>,
        state: LifecycleState,
        timestampMillis: Long
    ) {
        val db = ChronicleDb.getInstance(context)
        if (db.lifecycleStateDao().get(checkNotNull(sampledStateKey(state.activityClass)))?.value == state.activityClass) return
        events.add(
            DeviceLifecycleEventRecorder.buildEvent(
                activityClass = state.activityClass,
                interactionType = state.interactionType,
                timestampMillis = timestampMillis
            )
        )
    }

    private fun batteryState(): LifecycleState {
        val intent = registerSystemBroadcastReceiver(context, null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
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
        )
    }

    private fun powerSaveState(): LifecycleState {
        val powerManager = context.getSystemService(Context.POWER_SERVICE) as PowerManager
        val enabled = powerManager.isPowerSaveMode
        return LifecycleState(
            interactionType = if (enabled) INTERACTION_POWER_SAVE_MODE_ON else INTERACTION_POWER_SAVE_MODE_OFF,
            activityClass = "power-save:${if (enabled) "on" else "off"}",
        )
    }

}

private data class LifecycleState(
    val interactionType: String,
    val activityClass: String,
)
