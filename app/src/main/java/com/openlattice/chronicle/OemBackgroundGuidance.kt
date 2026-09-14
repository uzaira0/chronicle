package com.openlattice.chronicle

import android.content.Intent
import java.util.Locale

object OemBackgroundGuidance {
    fun vendorKey(manufacturer: String): String? = when (manufacturer.trim().lowercase(Locale.ROOT)) {
        "xiaomi", "redmi", "poco" -> "xiaomi"
        "huawei", "honor" -> "huawei"
        "oppo", "realme", "oneplus" -> "oppo"
        "vivo" -> "vivo"
        "samsung" -> "samsung"
        else -> null
    }

    fun matches(manufacturer: String): Boolean = vendorKey(manufacturer) != null

    // Keep component selection independent of Android so plain JVM tests can verify order.
    internal fun preferredComponents(manufacturer: String): List<Pair<String, String>> =
        when (vendorKey(manufacturer)) {
            "xiaomi" -> listOf(
                "com.miui.securitycenter" to "com.miui.permcenter.autostart.AutoStartManagementActivity",
                "com.miui.powerkeeper" to "com.miui.powerkeeper.ui.HiddenAppsConfigActivity",
            )
            "huawei" -> listOf(
                "com.huawei.systemmanager" to "com.huawei.systemmanager.startupmgr.ui.StartupNormalAppListActivity",
            )
            "oppo" -> listOf(
                "com.coloros.safecenter" to "com.coloros.safecenter.permission.startup.StartupAppListActivity",
                "com.oplus.battery" to "com.oplus.powermanager.fuelgaue.PowerAppsBgSettingActivity",
            )
            "vivo" -> listOf(
                "com.vivo.permissionmanager" to "com.vivo.permissionmanager.activity.BgStartUpManagerActivity",
            )
            "samsung" -> listOf(
                "com.samsung.android.lool" to "com.samsung.android.sm.ui.battery.BatteryActivity",
            )
            else -> emptyList()
        }

    fun preferredIntents(manufacturer: String, packageName: String, packageLabel: String): List<Intent> =
        preferredComponents(manufacturer).map { (owner, activity) ->
            Intent().setClassName(owner, activity).apply {
                if (owner == "com.miui.powerkeeper") {
                    putExtra("package_name", packageName)
                    putExtra("package_label", packageLabel)
                }
            }
        }
}
