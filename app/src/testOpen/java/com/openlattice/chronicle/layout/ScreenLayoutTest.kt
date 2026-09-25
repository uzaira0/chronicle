package com.openlattice.chronicle.layout

import android.app.Activity
import android.app.AppOpsManager
import android.content.Context
import android.content.Intent
import android.graphics.Rect
import android.os.Build
import android.os.Looper
import android.view.View
import androidx.core.graphics.Insets
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import com.github.takahirom.roborazzi.RoborazziOptions
import com.github.takahirom.roborazzi.captureRoboImage
import com.openlattice.chronicle.Enrollment
import com.openlattice.chronicle.LocalStoreRecoveryActivity
import com.openlattice.chronicle.MainActivity
import com.openlattice.chronicle.PermissionActivity
import com.openlattice.chronicle.R
import com.openlattice.chronicle.StudyDisclosureActivity
import com.openlattice.chronicle.UserIdentificationActivity
import com.openlattice.chronicle.collection.CollectionModuleId
import com.openlattice.chronicle.collection.state.CollectionOrientationActivity
import com.openlattice.chronicle.collection.state.ConsentPlan
import com.openlattice.chronicle.services.notifications.NotificationPermissionActivity
import com.openlattice.chronicle.storage.LocalStoreRecoveryReason
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.ParameterizedRobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Every participant-facing screen, on the device and text extremes Android allows: small and
 * large screens, landscape, font scale up to 2.0, long (Spanish, en-XA pseudo) and RTL text,
 * dark theme, with real status/navigation bar and camera-cutout insets. A screen passes only
 * when no text is cut off, no view spills out of its parent, nothing interactive sits under a
 * system bar, and Google's Accessibility Test Framework reports no errors.
 *
 * `testOpenDebugUnitTest` runs the checks. `recordRoborazziOpenDebug` / `verifyRoborazziOpenDebug`
 * record / compare the reference screenshots in src/testOpen/screenshots (SDK 36, [GOLDEN] set).
 */
@RunWith(ParameterizedRobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [28, 36])
class ScreenLayoutTest(
    private val screen: Screen,
    private val device: Device,
    private val locale: String,
) {
    enum class Screen(val enrolled: Boolean = true) {
        OVERVIEW, UPLOADS, DATA_SHARING, SETTINGS,
        ENROLLMENT(enrolled = false), ENROLLMENT_DONE,
        STUDY_DISCLOSURE(enrolled = false), CONSENT(enrolled = false),
        USAGE_PERMISSION, NOTIFICATION_PERMISSION, USER_IDENTIFICATION,
        LOCAL_STORE_RECOVERY(enrolled = false),
    }

    /** [bars] = status/nav/cutout insets in dp (left, top, right, bottom). */
    enum class Device(val qualifiers: String, val fontScale: Float, val bars: IntArray) {
        PHONE("w411dp-h891dp-port-xxhdpi", 1.0f, intArrayOf(0, 24, 0, 48)),
        SMALL_HUGE_FONT("w320dp-h568dp-port-xhdpi", 2.0f, intArrayOf(0, 24, 0, 48)),
        LANDSCAPE_LARGE_FONT("w891dp-h411dp-land-xxhdpi", 1.3f, intArrayOf(32, 24, 48, 0)),
        TABLET("sw800dp-w800dp-h1280dp-port-xhdpi", 1.3f, intArrayOf(0, 24, 0, 48)),
        PHONE_DARK("w411dp-h891dp-port-night-xxhdpi", 1.0f, intArrayOf(0, 24, 0, 48)),
    }

    @Test
    fun screenFitsEveryDevice() {
        // Below Android 13 there is no runtime notification permission; the screen closes itself.
        assumeTrue(screen != Screen.NOTIFICATION_PERMISSION || Build.VERSION.SDK_INT >= 33)
        val context = ApplicationProvider.getApplicationContext<Context>()
        RuntimeEnvironment.setQualifiers("$locale-${device.qualifiers}")
        RuntimeEnvironment.setFontScale(device.fontScale)
        TestStores.install(context, screen.enrolled)
        if (screen == Screen.USAGE_PERMISSION) denyUsageAccess(context)
        if (screen == Screen.NOTIFICATION_PERMISSION) {
            shadowOf(context as android.app.Application).denyPermissions(android.Manifest.permission.POST_NOTIFICATIONS)
        }

        ActivityScenario.launch<Activity>(intent(context)).use { scenario ->
            settle()
            var issues = emptyList<String>()
            scenario.onActivity { activity ->
                val root = activity.window.decorView
                // Before Android 15 the window keeps content clear of the bars; from 15 the app must.
                val bars = if (Build.VERSION.SDK_INT >= 35) applySystemBars(root) else Rect()
                issues = LayoutChecks.run(root, bars) + LayoutChecks.accessibility(root)
                if (Build.VERSION.SDK_INT == 36 && GOLDEN(screen, device, locale)) {
                    root.captureRoboImage(
                        "src/testOpen/screenshots/${screen.name.lowercase()}_${device.name.lowercase()}_$locale.png",
                        roborazziOptions = RoborazziOptions(
                            compareOptions = RoborazziOptions.CompareOptions(changeThreshold = 0.01f),
                        ),
                    )
                }
            }
            assertTrue(
                "$screen on $device [$locale] SDK ${Build.VERSION.SDK_INT}:\n  " + issues.joinToString("\n  "),
                issues.isEmpty(),
            )
        }
    }

    private fun intent(context: Context): Intent = when (screen) {
        Screen.OVERVIEW -> tab(context, R.id.nav_overview)
        Screen.UPLOADS -> tab(context, R.id.nav_uploads)
        Screen.DATA_SHARING -> tab(context, R.id.nav_data_sharing)
        Screen.SETTINGS -> tab(context, R.id.nav_settings)
        Screen.ENROLLMENT, Screen.ENROLLMENT_DONE -> Intent(context, Enrollment::class.java)
        // Field names are StudyDisclosureActivity's private extras; the body is a realistic
        // multi-paragraph disclosure so wrapping and scrolling are exercised.
        Screen.STUDY_DISCLOSURE -> Intent(context, StudyDisclosureActivity::class.java)
            .putExtra("study_disclosure_title", context.getString(R.string.title_study_information))
            .putExtra("study_disclosure_body", List(6) { context.getString(R.string.enrollment_done) }.joinToString("\n\n"))
            .putExtra("study_privacy_url", "https://chronicle.example.org/privacy")
            .putExtra("study_consent_url", "https://chronicle.example.org/consent")
        Screen.CONSENT -> CollectionOrientationActivity.intent(context, CHILE_PLAN)
        Screen.USAGE_PERMISSION -> Intent(context, PermissionActivity::class.java)
        Screen.NOTIFICATION_PERMISSION -> Intent(context, NotificationPermissionActivity::class.java)
        Screen.USER_IDENTIFICATION -> Intent(context, UserIdentificationActivity::class.java)
        Screen.LOCAL_STORE_RECOVERY ->
            LocalStoreRecoveryActivity.intent(context, LocalStoreRecoveryReason.DATABASE_OPEN_FAILED)
    }

    private fun tab(context: Context, id: Int) =
        Intent(context, MainActivity::class.java).putExtra(MainActivity.EXTRA_SELECT_TAB, id)

    private fun denyUsageAccess(context: Context) {
        val appOps = context.getSystemService(Context.APP_OPS_SERVICE) as AppOpsManager
        shadowOf(appOps).setMode(
            AppOpsManager.OPSTR_GET_USAGE_STATS,
            context.applicationInfo.uid,
            context.packageName,
            AppOpsManager.MODE_IGNORED,
        )
    }

    /** Dispatches real bar/cutout insets the way the window does on a device, then re-lays out. */
    private fun applySystemBars(root: View): Rect {
        val density = root.resources.displayMetrics.density
        val (l, t, r, b) = device.bars.map { (it * density).toInt() }
        val insets = WindowInsetsCompat.Builder()
            .setInsets(WindowInsetsCompat.Type.statusBars(), Insets.of(0, t, 0, 0))
            .setInsets(WindowInsetsCompat.Type.navigationBars(), Insets.of(0, 0, r, b))
            .setInsets(WindowInsetsCompat.Type.displayCutout(), Insets.of(l, 0, 0, 0))
            .build()
        ViewCompat.dispatchApplyWindowInsets(root, insets)
        root.measure(
            View.MeasureSpec.makeMeasureSpec(root.width, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(root.height, View.MeasureSpec.EXACTLY),
        )
        root.layout(root.left, root.top, root.right, root.bottom)
        return Rect(l, t, r, b)
    }

    /** Lets coroutine/IO-backed screens fill their text before measuring. */
    private fun settle() {
        repeat(20) {
            shadowOf(Looper.getMainLooper()).idle()
            Thread.sleep(25)
        }
        shadowOf(Looper.getMainLooper()).idle()
    }

    companion object {
        private val CHILE_PLAN = ConsentPlan(
            required = listOf(
                CollectionModuleId.USAGE_EVENTS,
                CollectionModuleId.DEVICE_LIFECYCLE,
                CollectionModuleId.UPLOAD_TELEMETRY,
            ),
            optional = listOf(
                CollectionModuleId.USER_IDENTIFICATION,
                CollectionModuleId.IN_APP_ACTIVITY_CLASS,
                CollectionModuleId.BATTERY_TELEMETRY,
                CollectionModuleId.CONNECTIVITY_STATE,
                CollectionModuleId.DEVICE_SETTINGS,
            ),
        )

        /** en = source text; es = Chile; en-rXA = ~40% longer accented pseudo; ar-rXB = RTL pseudo. */
        private val LOCALES = listOf("en", "es", "en-rXA", "ar-rXB-ldrtl")

        /** Reference screenshots: every screen, but not every combination. */
        private val GOLDEN = { _: Screen, device: Device, locale: String ->
            when (device) {
                Device.PHONE -> locale == "en" || locale == "ar-rXB-ldrtl"
                Device.SMALL_HUGE_FONT, Device.LANDSCAPE_LARGE_FONT, Device.TABLET -> locale == "es"
                Device.PHONE_DARK -> locale == "en"
            }
        }

        @JvmStatic
        @ParameterizedRobolectricTestRunner.Parameters(name = "{0}-{1}-{2}")
        fun parameters(): List<Array<Any>> =
            Screen.entries.flatMap { screen ->
                Device.entries.flatMap { device -> LOCALES.map { arrayOf<Any>(screen, device, it) } }
            }
    }
}
