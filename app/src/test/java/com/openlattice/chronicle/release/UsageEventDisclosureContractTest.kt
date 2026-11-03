package com.openlattice.chronicle.release

import android.app.AppOpsManager
import android.app.Application
import android.app.usage.UsageStatsManager
import android.content.Context
import android.content.res.Configuration
import android.os.PersistableBundle
import android.os.UserManager
import androidx.test.core.app.ApplicationProvider
import com.openlattice.chronicle.EnrollmentTestStores
import com.openlattice.chronicle.R
import com.openlattice.chronicle.models.ExtractedUsageEvent
import com.openlattice.chronicle.sensors.UsageEventsChronicleSensor
import java.util.TreeMap
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.shadows.ShadowUsageStatsManager

/**
 * The usage timeline uploads every event Android returns (no allowlist), named by the sensor's labels and unnamed types
 * by number, so every disclosure of the usage module names each label and says unnamed types are kept by number.
 */
@RunWith(RobolectricTestRunner::class)
class UsageEventDisclosureContractTest {
    private val app = ApplicationProvider.getApplicationContext<Application>()

    @After fun tearDown() { EnrollmentTestStores.prefs = null }

    @Test fun everyUsageEventTheSensorUploadsIsDisclosed() {
        EnrollmentTestStores.prefs = app.getSharedPreferences("usage-disclosure-test", Context.MODE_PRIVATE)
        shadowOf(app.getSystemService(AppOpsManager::class.java)).setMode(AppOpsManager.OPSTR_GET_USAGE_STATS,
            app.applicationInfo.uid, app.packageName, AppOpsManager.MODE_ALLOWED)
        shadowOf(app.getSystemService(UserManager::class.java)).setUserUnlocked(true)
        val now = System.currentTimeMillis()
        // Every platform event type through APP_COMPONENT_USED (31), hidden ones such as NOTIFICATION_SEEN (10) included.
        val types = 0..31
        types.forEach { type ->
            shadowOf(app.getSystemService(UsageStatsManager::class.java)).addEvent(ShadowUsageStatsManager.EventBuilder
                .buildEvent().setPackage("example.app").setEventType(type).setTimeStamp(now - 1_000 + type)
                // Payloads some types must carry to cross the platform parcel; the sensor reads none of them.
                .setConfiguration(Configuration()).setShortcutId("shortcut").setExtras(PersistableBundle()).build())
        }
        val uploaded = UsageEventsChronicleSensor(app).poll(now - 2_000, now, TreeMap())!!
            .filterIsInstance<ExtractedUsageEvent>().map { it.interactionType }
        assertEquals("the sensor uploads every event type", types.count(), uploaded.size)
        val names = uploaded.filterNot { it == "None" || it.startsWith("Unknown importance:") }.distinct()
        assertTrue(names.containsAll(listOf("Notification Seen", "Standby Bucket Changed", "Foreground Service Start")))
        val disclosures = mapOf(
            "privacy policy" to app.getString(R.string.platform_privacy_policy_full),
            "usage access disclosure" to app.getString(R.string.usage_access_disclosure_body),
            "usage access screen" to app.getString(R.string.usage_settings_permission),
            "usage consent" to app.resources.getStringArray(R.array.consent_usage_events_collects).joinToString("\n"),
            "data safety" to checkNotNull(javaClass.getResourceAsStream("/data-safety.md")).bufferedReader().use { it.readText() },
        )
        disclosures.forEach { (where, text) ->
            names.forEach { assertTrue("$where must name $it", it in text) }
            assertTrue("$where must cover unnamed event types", "any other event type by its number" in text)
        }
    }

    /**
     * Notification Seen and Interruption upload with usage (standard usage events), so the Play
     * listing names them as collected and excludes only the Notification Activity module's records.
     */
    @Test fun thePlayListingDisclosesNotificationUsageEventsAndExcludesOnlyTheNotificationModule() {
        val listing = checkNotNull(javaClass.getResourceAsStream("/listing.md")).bufferedReader().use { it.readText() }
            .replace(Regex("\\s+"), " ")
        val collected = listing.substringAfter("WHAT A STUDY CAN COLLECT").substringBefore("NOT COLLECTED")
        val excluded = listing.substringAfter("NOT COLLECTED").substringBefore("To withdraw")
        assertTrue(collected, "notification seen and interruption events (which app and when, never notification content)" in collected)
        assertTrue(excluded, "Notification Activity module records" in excluded)
        assertFalse(excluded, Regex("notification activity(?! module records)", RegexOption.IGNORE_CASE).containsMatchIn(excluded))
    }
}
