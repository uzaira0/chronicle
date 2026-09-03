package com.openlattice.chronicle

import android.content.Intent
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import androidx.test.core.app.ActivityScenario
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.NoMatchingViewException
import androidx.test.espresso.action.ViewActions.click
import androidx.test.espresso.action.ViewActions.scrollTo
import androidx.test.espresso.assertion.ViewAssertions.matches
import androidx.test.espresso.matcher.ViewMatchers.isDisplayed
import androidx.test.espresso.matcher.ViewMatchers.withId
import androidx.test.espresso.matcher.ViewMatchers.withText
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.openlattice.chronicle.android.AndroidSensorSetting
import com.openlattice.chronicle.android.AndroidSensorType
import com.openlattice.chronicle.collection.CollectionModuleId
import com.openlattice.chronicle.preferences.EnrollmentSettings
import com.openlattice.chronicle.preferences.SensorSettings
import com.openlattice.chronicle.storage.BatterySampleEntry
import com.openlattice.chronicle.storage.QueueEntry
import com.openlattice.chronicle.storage.SensorSampleEntry
import com.openlattice.chronicle.storage.UserQueueEntry
import org.hamcrest.CoreMatchers.containsString
import org.hamcrest.CoreMatchers.not
import org.hamcrest.Matchers.allOf
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.time.Instant

@RunWith(AndroidJUnit4::class)
class MainActivityDecisionTest {

    @Before
    fun setUp() {
        AppTestState.resetPrefs()
        AppTestState.clearMutableTables()
        AppTestState.setUsageStatsAppOp()
        AppTestState.grantPostNotificationsIfPossible()
        AppTestState.allowExactAlarmsIfPossible()

        AppTestState.enrollActiveStudy(
            enabledModules = setOf(
                CollectionModuleId.SENSOR_ACCELEROMETER,
                CollectionModuleId.SENSOR_GYROSCOPE,
            ),
        )
        SensorSettings(AppTestState.context).save(
            AndroidSensorSetting(
                sensors = setOf(AndroidSensorType.accelerometer, AndroidSensorType.gyroscope),
                samplingRateHz = 1,
                dutyCycleActiveSeconds = 10,
                dutyCyclePeriodSeconds = 120,
            )
        )
    }

    @Test
    fun enrolledMainScreenShowsStudyParticipantServerHealthAndUploadDecision() {
        ActivityScenario.launch<MainActivity>(
            Intent(AppTestState.context, MainActivity::class.java)
        ).use { scenario ->
            waitForTextContaining("participant")
            onView(withId(R.id.mainBottomNav)).check(matches(isDisplayed()))
            onView(withId(R.id.overviewStudyId)).check(matches(withText(containsString(AppTestState.STUDY_ID.toString()))))
            onView(withId(R.id.overviewParticipantId)).check(matches(withText(containsString(AppTestState.PARTICIPANT_ID))))
            onView(withId(R.id.overviewServerHealth)).check(matches(withText(containsString("No upload status yet"))))
            onView(withId(R.id.nav_uploads)).perform(click())
            onView(withId(R.id.uploadNowButton)).perform(scrollTo())
            waitForViewDisplayed(R.id.uploadNowButton)
            scenario.onActivity { activity ->
                val button = activity.findViewById<View>(R.id.uploadNowButton)
                assertTrue(button.visibility == View.VISIBLE)
                button.performClick()
            }
        }
    }

    @Test
    fun uploadNowDecisionTemporarilyDisablesTheButtonToPreventDoubleTaps() {
        ActivityScenario.launch<MainActivity>(
            Intent(AppTestState.context, MainActivity::class.java)
        ).use { scenario ->
            waitForTextContaining("participant")
            onView(withId(R.id.nav_uploads)).perform(click())
            onView(withId(R.id.uploadNowButton)).perform(scrollTo())
            waitForViewDisplayed(R.id.uploadNowButton)
            scenario.onActivity { activity ->
                val button = activity.findViewById<View>(R.id.uploadNowButton)
                button.performClick()
                assertFalse(button.isEnabled)
            }
        }
    }

    @Test
    fun pendingUploadCountExcludesLocalContextAndPurgedRestrictedSensorRows() {
        AppTestState.clearMutableTables()
        AppTestState.enrollActiveStudy()
        val db = AppTestState.db()
        val now = Instant.now()
        db.queueEntryData().insertEntries(
            listOf(
                QueueEntry(100L, 1L, byteArrayOf(1)),
                QueueEntry(200L, 2L, byteArrayOf(2)),
            )
        )
        db.sensorSampleDao().insertAll(
            (1..3).map {
                SensorSampleEntry(
                    id = "sensor-$it",
                    sensorType = "ACCELEROMETER",
                    timestamp = now.plusSeconds(it.toLong()).toString(),
                    timezone = "UTC",
                    x = it.toFloat(),
                    y = null,
                    z = null,
                    w = null,
                    accuracy = null,
                )
            }
        )
        db.batterySampleDao().insertAll(
            (1..4).map {
                BatterySampleEntry(
                    id = "battery-$it",
                    timestamp = now.plusSeconds(60L + it).toString(),
                    timezone = "UTC",
                    levelPercent = 80,
                    chargingState = "DISCHARGING",
                    plugType = "UNPLUGGED",
                    temperatureDeciC = 250,
                    voltageMillivolts = 4000,
                    health = "GOOD",
                )
            }
        )
        db.userQueueEntryData().insertEntries(listOf(UserQueueEntry(300L, "participant")))

        ActivityScenario.launch<MainActivity>(
            Intent(AppTestState.context, MainActivity::class.java)
        ).use {
            onView(withId(R.id.nav_uploads)).perform(click())
            waitForViewTextContaining(R.id.uploadsRemaining, "6")
            waitForViewTextContaining(R.id.uploadsBreakdown, "Usage/lifecycle 2")
            waitForViewTextContaining(R.id.uploadsBreakdown, "Battery 4")
            onView(withId(R.id.uploadsBreakdown)).check(matches(withText(not(containsString("Sensors")))))
            waitForViewTextContaining(
                R.id.uploadsBreakdown,
                "Local participant labels 1 (context only; not an upload queue)",
            )
        }
    }

    @Test
    fun playDataSharingHidesTheEntirePolicyDisabledPhysicalSensorSection() {
        ActivityScenario.launch<MainActivity>(
            Intent(AppTestState.context, MainActivity::class.java)
        ).use { scenario ->
            waitForTextContaining("participant")
            onView(withId(R.id.nav_data_sharing)).perform(click())

            scenario.onActivity { activity ->
                val pageTitle = activity.findViewById<TextView>(R.id.dataSharingPageTitle)
                val title = activity.findViewById<TextView>(R.id.sensorsSectionTitle)
                val summary = activity.findViewById<View>(R.id.sensorsSummary)
                val list = activity.findViewById<LinearLayout>(R.id.sensorToggleList)
                assertTrue("The Data Sharing page title must remain visible.", pageTitle.visibility == View.VISIBLE)
                assertTrue("The page title identity must not be confused with Sensors.", pageTitle.text == "Data Sharing")
                assertTrue("The hidden sensor heading must be the Sensors label.", title.text == "Sensors")
                assertTrue("The minimal Play release must hide the sensor heading.", title.visibility == View.GONE)
                assertTrue("The minimal Play release must hide the sensor summary.", summary.visibility == View.GONE)
                assertTrue("The minimal Play release must hide physical-sensor controls.", list.visibility == View.GONE)
                assertTrue("The minimal Play release must not create physical-sensor rows.", list.childCount == 0)
            }
        }
    }

    @Test
    fun backDecisionLeavesMainActivityFinishingInsteadOfNavigatingBackThroughEnrollment() {
        ActivityScenario.launch<MainActivity>(
            Intent(AppTestState.context, MainActivity::class.java)
        ).use { scenario ->
            scenario.onActivity { activity ->
                activity.onBackPressedDispatcher.onBackPressed()
                assertTrue("back handler should consume navigation by sending user home", !activity.isFinishing)
            }
        }
    }

    @Test
    fun cancellingKeepActivePromptsPersistsTheParticipantDecision() {
        val enrollmentSettings = EnrollmentSettings(AppTestState.context)
        enrollmentSettings.toggleBatteryOptimizationDialog(true)
        enrollmentSettings.toggleHibernationExemptionDialog(true)

        ActivityScenario.launch<MainActivity>(
            Intent(AppTestState.context, MainActivity::class.java)
        ).use { scenario ->
            scenario.onActivity { activity ->
                BatteryOptimizationExemptionDialog().show(
                    activity.supportFragmentManager,
                    "battery-decision-test",
                )
            }
            onView(withText(android.R.string.cancel)).perform(click())
            assertFalse(enrollmentSettings.isBatteryOptimizationDialogEnabled())

            scenario.onActivity { activity ->
                AppHibernationExemptionDialog().show(
                    activity.supportFragmentManager,
                    "hibernation-decision-test",
                )
            }
            onView(withText(android.R.string.cancel)).perform(click())
            assertFalse(enrollmentSettings.isHibernationExemptionDialogEnabled())
        }
    }

    private fun waitForTextContaining(text: String) {
        repeat(30) {
            try {
                onView(withText(containsString(text))).check(matches(isDisplayed()))
                return
            } catch (_: AssertionError) {
                Thread.sleep(100)
            } catch (_: NoMatchingViewException) {
                Thread.sleep(100)
            }
        }
        onView(withText(containsString(text))).check(matches(isDisplayed()))
    }

    private fun waitForViewText(viewId: Int, text: String) {
        repeat(30) {
            try {
                onView(allOf(withId(viewId), withText(text))).check(matches(isDisplayed()))
                return
            } catch (_: AssertionError) {
                Thread.sleep(100)
            } catch (_: NoMatchingViewException) {
                Thread.sleep(100)
            }
        }
        onView(allOf(withId(viewId), withText(text))).check(matches(isDisplayed()))
    }

    private fun waitForViewTextContaining(viewId: Int, text: String) {
        repeat(30) {
            try {
                onView(allOf(withId(viewId), withText(containsString(text)))).check(matches(isDisplayed()))
                return
            } catch (_: AssertionError) {
                Thread.sleep(100)
            } catch (_: NoMatchingViewException) {
                Thread.sleep(100)
            }
        }
        onView(allOf(withId(viewId), withText(containsString(text)))).check(matches(isDisplayed()))
    }

    private fun waitForViewDisplayed(viewId: Int) {
        repeat(30) {
            try {
                onView(withId(viewId)).check(matches(isDisplayed()))
                return
            } catch (_: AssertionError) {
                Thread.sleep(100)
            } catch (_: NoMatchingViewException) {
                Thread.sleep(100)
            }
        }
        onView(withId(viewId)).check(matches(isDisplayed()))
    }
}
