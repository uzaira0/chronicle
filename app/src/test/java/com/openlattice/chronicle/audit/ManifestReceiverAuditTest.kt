package com.openlattice.chronicle.audit

import com.openlattice.chronicle.BuildConfig
import com.openlattice.chronicle.ANDROID_NS
import com.openlattice.chronicle.mergedMainManifest
import org.w3c.dom.Element
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ManifestReceiverAuditTest {
    @Test fun mergedManifestHasNoRegisteredOnlyBroadcastActions() {
        // https://developer.android.com/reference/android/content/Intent#ACTION_SCREEN_ON
        // Intent also documents SCREEN_OFF, BATTERY_CHANGED, TIME_TICK and CONFIGURATION_CHANGED.
        // https://developer.android.com/reference/android/os/PowerManager#ACTION_POWER_SAVE_MODE_CHANGED
        // https://developer.android.com/develop/background-work/background-tasks/broadcasts#android-7.0
        // USER_PRESENT is not exempt: https://developer.android.com/develop/background-work/background-tasks/broadcasts/broadcast-exceptions
        val registeredOnly = setOf(
            "android.intent.action.SCREEN_ON", "android.intent.action.SCREEN_OFF",
            "android.intent.action.BATTERY_CHANGED", "android.intent.action.TIME_TICK",
            "android.intent.action.CONFIGURATION_CHANGED", "android.os.action.POWER_SAVE_MODE_CHANGED",
            "android.os.action.DEVICE_IDLE_MODE_CHANGED", "android.net.conn.CONNECTIVITY_CHANGE",
            "android.intent.action.USER_PRESENT",
        )
        val receivers = mergedMainManifest().getElementsByTagName("receiver")
        for (i in 0 until receivers.length) {
            val actions = (receivers.item(i) as Element).getElementsByTagName("action")
            for (j in 0 until actions.length) {
                val action = (actions.item(j) as Element).getAttributeNS(ANDROID_NS, "name")
                assertFalse("Manifest receiver cannot receive $action", action in registeredOnly)
            }
        }
    }

    @Test fun mergedManifestHasOnlyUsefulNotificationAndBootReceivers() {
        val receivers=mergedMainManifest().getElementsByTagName("receiver")
        val names=(0 until receivers.length).map{(receivers.item(it) as Element).getAttributeNS(
            ANDROID_NS,"name")}
        assertFalse(names.any{it.endsWith("NotificationPermissionReceiver")||it.endsWith("NotificationPermissionListener")})
        assertTrue(names.any{it.endsWith("StartOnBoot")})
        if(BuildConfig.ALLOW_RESTRICTED_RESEARCH_PERMISSIONS)assertTrue(names.any{it.endsWith("LockedBootReceiver")})
    }

    /**
     * Usage rows name each app by its label; Android 11+ shows another app's label only to a manifest that
     * declares it visible. A launcher query covers every app a participant opens, without QUERY_ALL_PACKAGES, which only
     * the research build declares (https://developer.android.com/training/package-visibility/declaring).
     */
    @Test fun mergedManifestSeesLauncherAppsAndAsksForAllPackagesOnlyInResearch() {
        val manifest = mergedMainManifest()
        val queries = manifest.getElementsByTagName("queries")
        val intents = (0 until queries.length).flatMap { q ->
            val nodes = (queries.item(q) as Element).getElementsByTagName("intent")
            (0 until nodes.length).map { nodes.item(it) as Element }
        }
        fun Element.names(tag: String) = getElementsByTagName(tag).let { nodes ->
            (0 until nodes.length).map { (nodes.item(it) as Element).getAttributeNS(ANDROID_NS, "name") }
        }
        assertTrue(intents.any { "android.intent.action.MAIN" in it.names("action") &&
            "android.intent.category.LAUNCHER" in it.names("category") })
        val queryAll = manifest.documentElement.names("uses-permission").contains("android.permission.QUERY_ALL_PACKAGES")
        if (BuildConfig.DISTRIBUTION_CHANNEL != "RESEARCH") assertFalse(queryAll)
    }
}
