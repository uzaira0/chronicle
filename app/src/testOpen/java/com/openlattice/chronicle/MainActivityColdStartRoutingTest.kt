package com.openlattice.chronicle

import android.content.Context
import android.app.usage.UsageStatsManager
import android.content.SharedPreferences
import android.net.ConnectivityManager
import android.os.Bundle
import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import com.openlattice.chronicle.audit.AuditStores
import com.openlattice.chronicle.collection.CollectionModuleId
import com.openlattice.chronicle.collection.state.CollectionLoopCoordinator
import com.openlattice.chronicle.collection.state.PendingAcknowledgmentSnapshot
import com.openlattice.chronicle.preferences.STUDY_ID
import com.openlattice.chronicle.preferences.EnrollmentSettings
import com.openlattice.chronicle.ui.DataSharingFragment
import com.openlattice.chronicle.ui.DataSharingRefreshAuditTest
import com.openlattice.chronicle.ui.OverviewFragment
import com.openlattice.chronicle.ui.SettingsHomeFragment
import kotlinx.coroutines.asCoroutineDispatcher
import org.robolectric.android.controller.ActivityController
import org.robolectric.android.util.concurrent.PausedExecutorService
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements
import org.robolectric.shadows.ShadowAlarmManager

/**
 * After an OEM process kill the gate has not yet published its snapshot, so a MAIN-thread
 * isEnrolled() says false. MainActivity must route from the authoritative off-main read and open
 * the dashboard, not the enrollment screen.
 */
@Suppress("DEPRECATION")
@RunWith(RobolectricTestRunner::class)
class MainActivityColdStartRoutingTest {
    private val context: Context = ApplicationProvider.getApplicationContext()

    @Test fun enrollmentFixturePublishesIdentityAfterItsDatabaseScope() {
        val db = EnrollmentTestStores.install(context)
        val prefs = checkNotNull(EnrollmentTestStores.prefs)
        val observed = mutableListOf<Boolean>()
        val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
            if (key == STUDY_ID) {
                observed += db.uploadServerDao().getConfiguredServer()?.studyId == prefs.getString(STUDY_ID, null) &&
                    db.collectionModuleStateDao().get(CollectionModuleId.USAGE_EVENTS.id)?.serverEnabled == true
            }
        }
        prefs.registerOnSharedPreferenceChangeListener(listener)
        try {
            AuditStores.enroll(context, db, prefs, emptyMap())
            assertEquals("An enrollment identity cannot be visible before its database scope", listOf(true), observed)
        } finally {
            prefs.unregisterOnSharedPreferenceChangeListener(listener)
            db.close()
        }
    }

    @Test fun enrolledParticipantBeforeHydrationLandsOnDashboard() {
        AuditStores.install(context, enrolled = true)
        // Cold process: no authorization snapshot published yet.
        EnrollmentTestStores.leaveEnrollmentUnpublished(context)
        assertFalse("precondition: MAIN snapshot not hydrated", EnrollmentSettings(context).isEnrolled())

        val activity = Robolectric.buildActivity(MainActivity::class.java).setup().get()
        repeat(40) {
            shadowOf(Looper.getMainLooper()).idle()
            Thread.sleep(25)
        }
        shadowOf(Looper.getMainLooper()).idle()

        val started = generateSequence { shadowOf(activity).nextStartedActivity }.toList()
        assertFalse("must not start Enrollment", started.any { it.component?.className == Enrollment::class.java.name })
        assertTrue(activity.supportFragmentManager.findFragmentById(R.id.mainFragmentContainer) != null)
    }

    private fun settle() {
        repeat(40) {
            shadowOf(Looper.getMainLooper()).idle()
            Thread.sleep(25)
        }
        shadowOf(Looper.getMainLooper()).idle()
    }

    @Test fun destroyedDuringEnrollmentReadDoesNotLaunchEnrollment() {
        AuditStores.install(context, enrolled = true)
        val controller = Robolectric.buildActivity(MainActivity::class.java).create()
        controller.destroy()
        settle()
        val started = generateSequence { shadowOf(controller.get()).nextStartedActivity }.toList()
        assertFalse(started.any { it.component?.className == Enrollment::class.java.name })
    }

    @Test fun readFinishingAfterStopWaitsForStartThenShowsDashboard() {
        AuditStores.install(context, enrolled = true)
        val controller = Robolectric.buildActivity(MainActivity::class.java).create().start().resume()
        controller.pause().saveInstanceState(Bundle()).stop()
        settle()
        controller.start().resume()
        settle()
        assertTrue(controller.get().supportFragmentManager.findFragmentById(R.id.mainFragmentContainer) != null)
    }

    /** AND-V01: a pending decision found after the participant left must not commit a tab after onSaveInstanceState. */
    @Test @Config(shadows = [HeldPendingDecisionLookup::class, HeldAccessLookup::class])
    fun pendingDecisionFoundAfterStopOpensDataSharingOnlyAfterRestart() = followUpLookupWaitsForRestart(
        FollowUpLookups.pending, PendingAcknowledgmentSnapshot(setOf(CollectionModuleId.BATTERY_TELEMETRY), "battery:1"))

    /** AND-V01: the same for an accepted module found missing its OS access (Usage Access) after the participant left. */
    @Test @Config(shadows = [HeldPendingDecisionLookup::class, HeldAccessLookup::class])
    fun missingAccessFoundAfterStopOpensDataSharingOnlyAfterRestart() {
        FollowUpLookups.pending.result = PendingAcknowledgmentSnapshot(emptySet(), "")
        followUpLookupWaitsForRestart(FollowUpLookups.access, true)
    }

    private fun <T> followUpLookupWaitsForRestart(held: FollowUpLookups.Gate<T>, found: T) {
        AuditStores.install(context, enrolled = true)
        EnrollmentSettings(context).toggleBatteryOptimizationDialog(false)
        EnrollmentSettings(context).toggleHibernationExemptionDialog(false)
        val executor = PausedExecutorService()
        val dispatcher = executor.asCoroutineDispatcher()
        val controller = ActivityController.of(MainActivity(dispatcher)).setup()
        fun drain() {
            executor.runAll()
            shadowOf(Looper.getMainLooper()).idle()
        }
        try {
            // Each runAll returns only after the IO continuation has posted to MAIN. Stop with the target lookup
            // queued, then deliver that result while stopped; no OS thread-state inference or wall-clock wait.
            repeat(20) {
                if (controller.get().supportFragmentManager.findFragmentById(R.id.mainFragmentContainer) == null) drain()
            }
            assertTrue(controller.get().supportFragmentManager.findFragmentById(R.id.mainFragmentContainer) is OverviewFragment)
            if (held === FollowUpLookups.access) {
                assertTrue(executor.runNext()) // enrolled services
                assertTrue(executor.runNext()) // no pending consent; MAIN queues the access lookup
                shadowOf(Looper.getMainLooper()).idle()
            }
            assertFalse("target lookup has not run", held.entered)
            controller.pause().saveInstanceState(Bundle()).stop()
            held.result = found
            drain()
            assertTrue("target lookup returned", held.entered)
            assertTrue("no transaction while stopped",
                controller.get().supportFragmentManager.findFragmentById(R.id.mainFragmentContainer) is OverviewFragment)
            controller.start().resume()
            drain()
            assertTrue(controller.get().supportFragmentManager.findFragmentById(R.id.mainFragmentContainer) is DataSharingFragment)
        } finally {
            controller.pause().stop().destroy()
            dispatcher.close()
            FollowUpLookups.reset()
        }
    }

    @Test @Config(sdk = [36]) fun resumingBehindAnOpenExemptionDialogDoesNotStackAnother() {
        AuditStores.install(context, enrolled = true)
        shadowOf(context.getSystemService(Context.USAGE_STATS_SERVICE) as UsageStatsManager)
            .setCurrentAppStandbyBucket(UsageStatsManager.STANDBY_BUCKET_RARE)
        val controller = Robolectric.buildActivity(MainActivity::class.java).setup()
        settle()
        // A permission prompt or settings page in front pauses and resumes the dashboard.
        repeat(2) {
            controller.pause()
            controller.resume()
            settle()
        }
        val fragments = controller.get().supportFragmentManager.fragments
        assertEquals(1, fragments.count { it.tag == "batteryExemption" })
    }

    @Test fun restrictedBackgroundDataIsShownToEnrolledParticipants() {
        AuditStores.install(context, enrolled = true)
        EnrollmentSettings(context).toggleBatteryOptimizationDialog(false)
        EnrollmentSettings(context).toggleHibernationExemptionDialog(false)
        val connectivity = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        shadowOf(connectivity).setRestrictBackgroundStatus(ConnectivityManager.RESTRICT_BACKGROUND_STATUS_ENABLED)

        val activity = Robolectric.buildActivity(MainActivity::class.java).setup().get()
        settle()

        assertTrue(activity.supportFragmentManager.findFragmentByTag("backgroundDataRestricted") != null)
    }

    @Test fun exactAlarmAccessIsOptionalAndOfferedOnlyInReminderContext() {
        AuditStores.install(context, enrolled = true)
        ShadowAlarmManager.setCanScheduleExactAlarms(false)
        val settingsActions = setOf(
            android.provider.Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM,
            android.provider.Settings.ACTION_SETTINGS,
        )
        val opened = (1..2).sumOf {
            val activity = Robolectric.buildActivity(MainActivity::class.java).setup().get()
            settle()
            val app = shadowOf(context as android.app.Application)
            // Robolectric resolves no settings page, so the navigator falls back to general Settings.
            (generateSequence { app.nextStartedActivity } + generateSequence { shadowOf(activity).nextStartedActivity })
                .count { it.action in settingsActions }
        }
        assertEquals(0, opened)
        val controller = Robolectric.buildActivity(DataSharingRefreshAuditTest.Host::class.java).create()
        val host = controller.get()
        val fragment = SettingsHomeFragment()
        host.supportFragmentManager.beginTransaction().replace(android.R.id.content, fragment).commitNow()
        controller.start().visible()
        try {
            val expected = android.content.Intent(android.provider.Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM)
                .setData(android.net.Uri.parse("package:${context.packageName}"))
            shadowOf(context.packageManager).addResolveInfoForIntent(expected, android.content.pm.ResolveInfo().apply {
                activityInfo = android.content.pm.ActivityInfo().apply { packageName = "android"; name = "Settings" }
            })
            fragment.requireView().findViewById<android.view.View>(R.id.reminderTimingButton).performClick()
            val dialog = org.robolectric.shadows.ShadowDialog.getLatestDialog() as androidx.appcompat.app.AlertDialog
            assertTrue(dialog.findViewById<android.widget.TextView>(android.R.id.message)!!.text.contains("approximate"))
            dialog.getButton(android.content.DialogInterface.BUTTON_POSITIVE).performClick()
            shadowOf(Looper.getMainLooper()).idle()
            val started = shadowOf(host).nextStartedActivity
            assertEquals(expected.action, started.action)
            assertEquals(expected.data, started.data)
        } finally { controller.stop().destroy() }
    }

    @Test fun recreationWithBundleButNoFragmentSelectsDefaultTab() {
        AuditStores.install(context, enrolled = true)
        val activity = Robolectric.buildActivity(MainActivity::class.java).setup(Bundle()).get()
        settle()
        assertTrue(activity.supportFragmentManager.findFragmentById(R.id.mainFragmentContainer) != null)
    }

    @Test fun rapidTabSelectionKeepsTheLastRequestedPage() {
        AuditStores.install(context, enrolled = true)
        val controller = Robolectric.buildActivity(MainActivity::class.java).setup()
        try {
            settle()
            controller.get().selectTab(R.id.nav_data_sharing)
            controller.get().selectTab(R.id.nav_overview)
            controller.get().supportFragmentManager.executePendingTransactions()
            assertTrue(controller.get().supportFragmentManager.findFragmentById(R.id.mainFragmentContainer)
                is OverviewFragment)
        } finally { controller.pause().stop().destroy() }
    }
}

/** Holds MainActivity's follow-up routing lookups until a test releases them. */
internal object FollowUpLookups {
    class Gate<T> {
        var entered = false
        var result: T? = null
        fun read(): T {
            entered = true
            return checkNotNull(result)
        }
    }

    @Volatile var pending = Gate<PendingAcknowledgmentSnapshot>()
    @Volatile var access = Gate<Boolean>()

    fun reset() {
        pending = Gate()
        access = Gate()
    }
}

@Implements(value = CollectionLoopCoordinator::class, isInAndroidSdk = false)
class HeldPendingDecisionLookup {
    @Implementation fun pendingAcknowledgmentSnapshot(): PendingAcknowledgmentSnapshot = FollowUpLookups.pending.read()
}

@Implements(className = "com.openlattice.chronicle.collection.capability.PermissionStatusKt", isInAndroidSdk = false)
object HeldAccessLookup {
    @JvmStatic @Implementation fun activeModuleAccessMissing(context: Context): Boolean =
        FollowUpLookups.access.read().also { check(context === context.applicationContext) }
}
