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
import java.util.Locale
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.shadows.ShadowUsageStatsManager

/**
 * The usage timeline uploads every event Android returns (no allowlist), named by the sensor's labels and unnamed types
 * by number, so the policy, Data Safety and Usage Access disclosures name each label and say unnamed types are kept by
 * number, and the participant's data-choice step covers each label in plain words.
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
            "data safety" to checkNotNull(javaClass.getResourceAsStream("/data-safety.md")).bufferedReader().use { it.readText() },
        )
        disclosures.forEach { (where, text) ->
            names.forEach { assertTrue("$where must name $it", it in text) }
            assertTrue("$where must cover unnamed event types", "any other event type by its number" in text)
        }
        // The data-choice step says the same in plain words: every uploaded label belongs to one bullet, and that
        // bullet says it. A label with no bullet (a new Android event type) fails here until the step covers it.
        val bullets = app.resources.getStringArray(R.array.consent_usage_events_collects).toList()
        assertEquals(bullets.toString(), PLAIN_BULLETS.size, bullets.size)
        PLAIN_BULLETS.forEachIndexed { index, phrases ->
            phrases.forEach { assertTrue("usage consent bullet $index must say '$it': ${bullets[index]}", it in bullets[index]) }
        }
        names.forEach { assertTrue("no plain-language usage consent bullet covers $it", it in BULLET_OF_LABEL) }
        bullets.forEach { bullet -> names.forEach { assertFalse("usage consent shows the Android name $it", it in bullet) } }
    }

    /**
     * The Usage Access dialog and screen list the data-choice step's plain bullets word for word, in every language,
     * and show no Android event name. The bullets are repeated in the strings, so this keeps the two from drifting.
     */
    @Test fun usageAccessTextsListThePlainBulletsInEveryLanguage() {
        for (language in listOf("en", "es")) {
            val localized = app.createConfigurationContext(
                Configuration(app.resources.configuration).apply { setLocale(Locale.forLanguageTag(language)) })
            val plain = localized.resources.getStringArray(R.array.consent_usage_events_collects).toList()
            assertEquals(PLAIN_BULLETS.size, plain.size)
            listOf(R.string.usage_access_disclosure_body, R.string.usage_settings_permission).forEach { id ->
                val text = localized.getString(id)
                plain.forEach { assertTrue("$language ${localized.resources.getResourceEntryName(id)} must list '$it'", it in text) }
                BULLET_OF_LABEL.keys.forEach { assertFalse("$language text shows the Android name $it", it in text) }
            }
        }
    }

    private companion object {
        /** What each plain-language bullet of the usage data-choice step must say, in order. */
        val PLAIN_BULLETS = listOf(
            listOf("apps you open", "how long"),
            listOf("screen turns on or off", "locked or unlocked"),
            listOf("starts up", "shuts down"),
            listOf("sent a notification", "never its text"),
            listOf("shortcut", "interact with an app", "screen rotation", "in the background"),
            listOf("How often Android expects", "any other app activity"),
        )

        /** The bullet that covers each label the usage sensor uploads; unnamed types fall under the last bullet. */
        val BULLET_OF_LABEL = mapOf(
            "Activity Resumed" to 0, "Activity Paused" to 0, "Activity Stopped" to 0,
            "Screen Interactive" to 1, "Screen Non-interactive" to 1, "Keyguard Shown" to 1, "Keyguard Hidden" to 1,
            "Device Startup" to 2, "Device Shutdown" to 2,
            "Notification Seen" to 3, "Notification Interruption" to 3,
            "Shortcut Invocation" to 4, "User Interaction" to 4, "Configuration Change" to 4,
            "Foreground Service Start" to 4, "Foreground Service Stop" to 4,
            "Standby Bucket Changed" to 5,
        )
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
