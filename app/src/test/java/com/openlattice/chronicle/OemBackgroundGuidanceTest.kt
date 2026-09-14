package com.openlattice.chronicle

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory

class OemBackgroundGuidanceTest {
    private val manufacturers = listOf(
        "Xiaomi", "Redmi", "POCO", "Huawei", "Honor", "Oppo", "Realme", "OnePlus", "Vivo", "Samsung",
    )

    @Test
    fun knownVendorsHaveExpectedComponentsInPriorityOrder() {
        // Exercise the production selector without invoking Android's stub Intent methods.
        manufacturers.forEach { manufacturer ->
            val expected = when (manufacturer) {
                "Xiaomi", "Redmi", "POCO" -> listOf(
                    "com.miui.securitycenter" to "com.miui.permcenter.autostart.AutoStartManagementActivity",
                    "com.miui.powerkeeper" to "com.miui.powerkeeper.ui.HiddenAppsConfigActivity",
                )
                "Huawei", "Honor" -> listOf(
                    "com.huawei.systemmanager" to "com.huawei.systemmanager.startupmgr.ui.StartupNormalAppListActivity",
                )
                "Oppo", "Realme", "OnePlus" -> listOf(
                    "com.coloros.safecenter" to "com.coloros.safecenter.permission.startup.StartupAppListActivity",
                    "com.oplus.battery" to "com.oplus.powermanager.fuelgaue.PowerAppsBgSettingActivity",
                )
                "Vivo" -> listOf(
                    "com.vivo.permissionmanager" to "com.vivo.permissionmanager.activity.BgStartUpManagerActivity",
                )
                else -> listOf(
                    "com.samsung.android.lool" to "com.samsung.android.sm.ui.battery.BatteryActivity",
                )
            }
            assertTrue(manufacturer, OemBackgroundGuidance.matches(manufacturer))
            assertEquals(manufacturer, expected, OemBackgroundGuidance.preferredComponents(manufacturer))
        }
    }

    @Test
    fun unknownManufacturersHaveNoGuidance() {
        listOf("Google", "Motorola", "", " ", "not-xiaomi").forEach { manufacturer ->
            assertFalse(OemBackgroundGuidance.matches(manufacturer))
            assertNull(OemBackgroundGuidance.vendorKey(manufacturer))
            assertTrue(OemBackgroundGuidance.preferredIntents(manufacturer, "example.app", "Example").isEmpty())
        }
    }

    @Test
    fun matchingIgnoresCaseAndSurroundingWhitespace() {
        listOf("Xiaomi ", "XIAOMI", " xIaOmI\t").forEach { manufacturer ->
            assertTrue(OemBackgroundGuidance.matches(manufacturer))
            assertEquals("xiaomi", OemBackgroundGuidance.vendorKey(manufacturer))
            assertEquals(
                OemBackgroundGuidance.preferredComponents("Xiaomi"),
                OemBackgroundGuidance.preferredComponents(manufacturer),
            )
        }
        assertEquals("huawei", OemBackgroundGuidance.vendorKey(" HONOR "))
        assertEquals("oppo", OemBackgroundGuidance.vendorKey(" ONEPLUS "))
    }

    @Test
    fun everyVendorKeyHasANonemptyStringResource() {
        val strings = DocumentBuilderFactory.newInstance().newDocumentBuilder()
            .parse(File("src/main/res/values/strings.xml")).getElementsByTagName("string")
        val labels = (0 until strings.length).associate { index ->
            val node = strings.item(index)
            node.attributes.getNamedItem("name").nodeValue to node.textContent
        }
        val keys = manufacturers.mapNotNull(OemBackgroundGuidance::vendorKey).toSet()
        assertEquals(setOf("xiaomi", "huawei", "oppo", "vivo", "samsung"), keys)
        keys.forEach { key ->
            assertFalse("Missing label for $key", labels["oem_background_vendor_$key"].isNullOrBlank())
        }
        assertTrue(labels.getValue("oem_background_guidance_body").contains("%1\$s"))
    }
}
