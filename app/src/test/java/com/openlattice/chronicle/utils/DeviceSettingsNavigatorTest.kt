package com.openlattice.chronicle.utils

import android.app.Application
import android.content.ActivityNotFoundException
import android.content.ComponentName
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.pm.PackageManager
import android.provider.Settings
import androidx.test.core.app.ApplicationProvider
import com.openlattice.chronicle.ANDROID_NS
import com.openlattice.chronicle.mergedMainManifest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.w3c.dom.Element

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [34])
class DeviceSettingsNavigatorTest {
    private val app: Context = ApplicationProvider.getApplicationContext()
    private val vendor = ComponentName("com.miui.powerkeeper", "com.miui.powerkeeper.ui.HiddenAppsConfigActivity")

    @Test fun anExplicitMissingActivityFallsBackInsteadOfResolvingItself() {
        val intent = Intent().setComponent(vendor)
        assertFalse(DeviceSettingsNavigator.canOpen(app, intent))
        assertEquals(Settings.ACTION_SETTINGS, DeviceSettingsNavigator.resolvedIntent(app, intent).action)
    }

    @Test fun theMergedManifestMakesEverySupportedOemSettingsPackageVisible() {
        val queries = mergedMainManifest().getElementsByTagName("queries")
        val packages = (0 until queries.length).flatMap { index ->
            val nodes = (queries.item(index) as Element).getElementsByTagName("package")
            (0 until nodes.length).map { (nodes.item(it) as Element).getAttributeNS(ANDROID_NS, "name") }
        }.toSet()
        assertTrue(packages.toString(), packages.containsAll(setOf("com.miui.securitycenter", "com.miui.powerkeeper",
            "com.huawei.systemmanager", "com.coloros.safecenter", "com.oplus.battery", "com.vivo.permissionmanager",
            "com.samsung.android.lool")))
    }

    @Test fun aVendorLaunchRefusalStillOpensGeneralSettings() {
        shadowOf(app.packageManager).addActivityIfNotPresent(vendor)
        for (failure in listOf(ActivityNotFoundException("removed after resolve"), SecurityException("not exported"))) {
            val attempted = mutableListOf<Intent>()
            val context = object : ContextWrapper(app) {
                override fun startActivity(intent: Intent) {
                    attempted += intent
                    if (intent.component == vendor) throw failure
                }
            }
            assertTrue(DeviceSettingsNavigator.open(context, Intent().setComponent(vendor)))
            assertEquals(listOf(vendor, null), attempted.map { it.component })
            assertEquals(Settings.ACTION_SETTINGS, attempted.last().action)
            assertTrue(attempted.all { it.flags and Intent.FLAG_ACTIVITY_NEW_TASK != 0 })
        }
    }

    @Test fun anUnavailableFallbackReturnsFalseWithoutCrashing() {
        val context = object : ContextWrapper(app) {
            override fun startActivity(intent: Intent) = throw ActivityNotFoundException("no settings")
        }
        assertFalse(DeviceSettingsNavigator.open(context, Intent().setComponent(vendor)))
    }

    @Test fun anUnavailablePackageQueryStillReachesTheFallback() {
        var opened: Intent? = null
        val context = object : ContextWrapper(app) {
            override fun getPackageManager(): PackageManager = throw SecurityException("package query refused")
            override fun startActivity(intent: Intent) { opened = intent }
        }
        assertFalse(DeviceSettingsNavigator.canOpen(context, Intent().setComponent(vendor)))
        assertTrue(DeviceSettingsNavigator.open(context, Intent().setComponent(vendor)))
        assertEquals(Settings.ACTION_SETTINGS, opened?.action)
    }
}
