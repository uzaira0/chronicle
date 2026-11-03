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
import com.openlattice.chronicle.collection.CollectionModuleId
import com.openlattice.chronicle.collection.lifecycle.LifecycleEventMapper
import com.openlattice.chronicle.collection.state.ResearchPersistenceGate
import com.openlattice.chronicle.collection.state.CollectionPersistenceGuard

class DeviceStateSampler(private val context: Context) {

    fun poll(timestampMillis: Long = System.currentTimeMillis()): List<ExtractedUsageEvent> {
        val origin = ResearchPersistenceGate.captureObservation(
            context, CollectionModuleId.DEVICE_LIFECYCLE)
        val observed = observe(origin, timestampMillis)
        var events = emptyList<ExtractedUsageEvent>()
        origin.persist {
            val dedupe = RoomLifecycleDedupeStore(ChronicleDb.getInstance(context))
            events = observed.filter { dedupe.shouldPersist(it, timestampMillis) }
        }
        return events
    }

    internal fun observe(origin: CollectionPersistenceGuard,
                         timestampMillis: Long = System.currentTimeMillis()): List<ExtractedUsageEvent> {
        if (!origin.isCurrent()) return emptyList()
        val states = listOf(networkState(), powerSaveState()).map { state ->
            DeviceLifecycleEventRecorder.buildEvent(
                activityClass = state.activityClass,
                interactionType = state.interactionType,
                timestampMillis = timestampMillis
            )
        }
        return listOfNotNull(batteryEvent(timestampMillis)) + states
    }

    /** Battery Saver as a sampled state, shared with PowerSaveModeReceiver so one dedupe marker covers both. */
    internal fun powerSaveEvent(timestampMillis: Long = System.currentTimeMillis()): ExtractedUsageEvent =
        powerSaveState().let { DeviceLifecycleEventRecorder.buildEvent(it.activityClass, it.interactionType, timestampMillis) }

    private fun batteryEvent(timestampMillis: Long): ExtractedUsageEvent? {
        val intent = registerSystemBroadcastReceiver(context, null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
            ?: return null
        return LifecycleEventMapper.batteryStateEvent(
            plugged = intent.getIntExtra(BatteryManager.EXTRA_PLUGGED, -1),
            level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1),
            scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, -1),
            timestampMillis = timestampMillis,
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
