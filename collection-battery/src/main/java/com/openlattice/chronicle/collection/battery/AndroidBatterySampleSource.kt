package com.openlattice.chronicle.collection.battery

import android.content.Context
import com.openlattice.chronicle.utils.registerSystemBroadcastReceiver
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import com.openlattice.chronicle.collection.BatteryChargingState
import com.openlattice.chronicle.collection.BatteryHealth
import com.openlattice.chronicle.collection.BatteryPlugType

/**
 * Production [BatterySampleSource] backed by Android's sticky `ACTION_BATTERY_CHANGED`
 * broadcast.
 *
 * `registerReceiver(null, …)` against a sticky broadcast returns the last broadcast
 * `Intent` synchronously, so no `BroadcastReceiver` lifecycle is needed — a poll is a
 * single, immediate read. This source holds only the **application** `Context` (wired
 * by `BatteryTelemetryModuleHolder`); [BatteryTelemetryCollectionModule] itself never
 * holds a `Context` (design §1C / refactor plan §6.1 guardrail 2).
 *
 * Battery telemetry needs no Android permission.
 *
 */
public class AndroidBatterySampleSource(
    private val appContext: Context,
) : BatterySampleSource {

    /** Immutable, reused across every [read] — a sticky-broadcast filter never changes. */
    private val batteryChangedFilter = IntentFilter(Intent.ACTION_BATTERY_CHANGED)

    override fun read(): BatteryReading? {
        val intent: Intent = registerSystemBroadcastReceiver(appContext, null, batteryChangedFilter)
            ?: return null

        val level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
        val scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, -1)
        val voltage = intent.getIntExtra(BatteryManager.EXTRA_VOLTAGE, -1)
        if (level < 0 || scale <= 0 || voltage < 0) return null
        if (!intent.hasExtra(BatteryManager.EXTRA_TEMPERATURE) || !intent.hasExtra(BatteryManager.EXTRA_VOLTAGE)) return null
        val plug = plugTypeOf(intent.getIntExtra(BatteryManager.EXTRA_PLUGGED, -1)) ?: return null

        return BatteryReading(
            levelPercent = (level * 100 / scale).coerceIn(0, 100),
            chargingState = chargingStateOf(intent.getIntExtra(BatteryManager.EXTRA_STATUS, -1)),
            plugType = plug,
            temperatureDeciC = intent.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, 0),
            voltageMillivolts = voltage,
            health = healthOf(intent.getIntExtra(BatteryManager.EXTRA_HEALTH, -1)),
        )
    }
}

/** Android's EXTRA_STATUS code as the exported charging state. */
internal fun chargingStateOf(status: Int): BatteryChargingState = when (status) {
    BatteryManager.BATTERY_STATUS_CHARGING -> BatteryChargingState.CHARGING
    BatteryManager.BATTERY_STATUS_DISCHARGING -> BatteryChargingState.DISCHARGING
    BatteryManager.BATTERY_STATUS_NOT_CHARGING -> BatteryChargingState.NOT_CHARGING
    BatteryManager.BATTERY_STATUS_FULL -> BatteryChargingState.FULL
    else -> BatteryChargingState.UNKNOWN
}

/** Android's EXTRA_PLUGGED code as the exported plug type; null for a code this build does not know. */
internal fun plugTypeOf(plugged: Int): BatteryPlugType? = when (plugged) {
    0 -> BatteryPlugType.UNPLUGGED
    BatteryManager.BATTERY_PLUGGED_AC -> BatteryPlugType.AC
    BatteryManager.BATTERY_PLUGGED_USB -> BatteryPlugType.USB
    BatteryManager.BATTERY_PLUGGED_WIRELESS -> BatteryPlugType.WIRELESS
    BatteryManager.BATTERY_PLUGGED_DOCK -> BatteryPlugType.DOCK
    else -> null
}

/** Android's EXTRA_HEALTH code as the exported battery health. */
internal fun healthOf(health: Int): BatteryHealth = when (health) {
    BatteryManager.BATTERY_HEALTH_GOOD -> BatteryHealth.GOOD
    BatteryManager.BATTERY_HEALTH_OVERHEAT -> BatteryHealth.OVERHEAT
    BatteryManager.BATTERY_HEALTH_DEAD -> BatteryHealth.DEAD
    BatteryManager.BATTERY_HEALTH_OVER_VOLTAGE -> BatteryHealth.OVER_VOLTAGE
    BatteryManager.BATTERY_HEALTH_UNSPECIFIED_FAILURE -> BatteryHealth.UNSPECIFIED_FAILURE
    BatteryManager.BATTERY_HEALTH_COLD -> BatteryHealth.COLD
    else -> BatteryHealth.UNKNOWN
}
