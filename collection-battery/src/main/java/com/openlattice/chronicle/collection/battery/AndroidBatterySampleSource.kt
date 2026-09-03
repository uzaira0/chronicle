package com.openlattice.chronicle.collection.battery

import android.content.Context
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
        val intent: Intent = appContext.registerReceiver(null, batteryChangedFilter)
            ?: return null

        val level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
        val scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, -1)
        if (level < 0 || scale <= 0) {
            return null
        }

        return BatteryReading(
            levelPercent = (level * 100 / scale).coerceIn(0, 100),
            chargingState = chargingStateOf(intent.getIntExtra(BatteryManager.EXTRA_STATUS, -1)),
            plugType = plugTypeOf(intent.getIntExtra(BatteryManager.EXTRA_PLUGGED, -1)),
            temperatureDeciC = intent.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, 0),
            voltageMillivolts = intent.getIntExtra(BatteryManager.EXTRA_VOLTAGE, 0).coerceAtLeast(0),
            health = healthOf(intent.getIntExtra(BatteryManager.EXTRA_HEALTH, -1)),
        )
    }

    private fun chargingStateOf(status: Int): BatteryChargingState = when (status) {
        BatteryManager.BATTERY_STATUS_CHARGING -> BatteryChargingState.CHARGING
        BatteryManager.BATTERY_STATUS_DISCHARGING -> BatteryChargingState.DISCHARGING
        BatteryManager.BATTERY_STATUS_NOT_CHARGING -> BatteryChargingState.NOT_CHARGING
        BatteryManager.BATTERY_STATUS_FULL -> BatteryChargingState.FULL
        else -> BatteryChargingState.UNKNOWN
    }

    private fun plugTypeOf(plugged: Int): BatteryPlugType = when (plugged) {
        0 -> BatteryPlugType.UNPLUGGED
        BatteryManager.BATTERY_PLUGGED_AC -> BatteryPlugType.AC
        BatteryManager.BATTERY_PLUGGED_USB -> BatteryPlugType.USB
        BatteryManager.BATTERY_PLUGGED_WIRELESS -> BatteryPlugType.WIRELESS
        BatteryManager.BATTERY_PLUGGED_DOCK -> BatteryPlugType.DOCK
        else -> BatteryPlugType.UNPLUGGED
    }

    private fun healthOf(health: Int): BatteryHealth = when (health) {
        BatteryManager.BATTERY_HEALTH_GOOD -> BatteryHealth.GOOD
        BatteryManager.BATTERY_HEALTH_OVERHEAT -> BatteryHealth.OVERHEAT
        BatteryManager.BATTERY_HEALTH_DEAD -> BatteryHealth.DEAD
        BatteryManager.BATTERY_HEALTH_OVER_VOLTAGE -> BatteryHealth.OVER_VOLTAGE
        BatteryManager.BATTERY_HEALTH_UNSPECIFIED_FAILURE -> BatteryHealth.UNSPECIFIED_FAILURE
        BatteryManager.BATTERY_HEALTH_COLD -> BatteryHealth.COLD
        else -> BatteryHealth.UNKNOWN
    }
}
