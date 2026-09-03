package com.openlattice.chronicle

import android.content.Intent
import androidx.test.core.app.ActivityScenario
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.accessibility.AccessibilityChecks
import androidx.test.espresso.action.ViewActions.click
import androidx.test.espresso.matcher.ViewMatchers.withId
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.openlattice.chronicle.collection.CollectionModuleId
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** Accessibility gates for every participant-facing decision surface in the minimal Play flow. */
@RunWith(AndroidJUnit4::class)
class PlayAccessibilityDecisionTest {

    @Before
    fun setUp() {
        AppTestState.resetPrefs()
        AppTestState.clearMutableTables()
        AppTestState.setUsageStatsAppOp()
        AppTestState.grantPostNotificationsIfPossible()
        AccessibilityChecks.enable().setRunChecksFromRootView(true)
    }

    @After
    fun tearDown() {
        AccessibilityChecks.disable()
    }

    @Test
    fun enrollmentDecisionSurfacePassesAccessibilityChecks() {
        ActivityScenario.launch<Enrollment>(Intent(AppTestState.context, Enrollment::class.java)).use {
            onView(withId(R.id.button)).perform(click())
        }
    }

    @Test
    fun consentAndSettingsSurfacesPassAccessibilityChecks() {
        AppTestState.enrollActiveStudy(
            enabledModules = setOf(
                CollectionModuleId.USAGE_EVENTS,
                CollectionModuleId.USER_IDENTIFICATION,
                CollectionModuleId.BATTERY_TELEMETRY,
            ),
            userIdentificationEnabled = true,
        )
        ActivityScenario.launch<MainActivity>(Intent(AppTestState.context, MainActivity::class.java)).use {
            onView(withId(R.id.nav_data_sharing)).perform(click())
            onView(withId(R.id.nav_settings)).perform(click())
        }
    }

    @Test
    fun unlockIdentificationDecisionSurfacePassesAccessibilityChecks() {
        AppTestState.enrollActiveStudy(
            enabledModules = setOf(CollectionModuleId.USER_IDENTIFICATION),
            userIdentificationEnabled = true,
        )
        ActivityScenario.launch<UserIdentificationActivity>(
            Intent(AppTestState.context, UserIdentificationActivity::class.java),
        ).use {
            onView(withId(R.id.child_user_btn)).perform(click())
        }
    }
}
