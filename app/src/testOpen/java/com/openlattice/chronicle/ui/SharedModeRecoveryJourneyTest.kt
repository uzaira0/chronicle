package com.openlattice.chronicle.ui

import android.Manifest
import android.app.Activity
import android.app.Application
import android.app.NotificationManager
import android.content.ComponentName
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.provider.Settings
import android.view.View
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.test.core.app.ApplicationProvider
import com.google.android.material.materialswitch.MaterialSwitch
import com.openlattice.chronicle.EnrollmentTestStores
import com.openlattice.chronicle.R
import com.openlattice.chronicle.WorkManagerRule
import com.openlattice.chronicle.audit.AuditStores
import com.openlattice.chronicle.collection.core.ModuleResult
import com.openlattice.chronicle.collection.identification.DeviceUser
import com.openlattice.chronicle.collection.identification.TargetUserRouter
import com.openlattice.chronicle.collection.state.ResearchPersistenceGate
import com.openlattice.chronicle.collection.state.onPersistenceWorker
import com.openlattice.chronicle.preferences.EnrollmentSettings
import com.openlattice.chronicle.preferences.IDENTIFY_USER
import com.openlattice.chronicle.services.notifications.DeviceUnlockMonitoringService
import com.openlattice.chronicle.services.notifications.IDENTIFY_USER_CHANNEL_ID
import com.openlattice.chronicle.storage.ChronicleDb
import com.openlattice.chronicle.utils.Utils
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.android.controller.ActivityController
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowDialog

/**
 * Turning on shared-phone mode from Settings when Android would hide its prompt. Each blocked state
 * (permission denied at the prompt, app notifications off, prompt channel muted) explains itself and opens the exact
 * Android screen; after the participant fixes it there and comes back, possibly rotated, the same switch turns the mode
 * on, starts the unlock monitor, and no study decision changes. Revoking the permission later is shown, not hidden.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [34])
class SharedModeRecoveryJourneyTest {
    @get:Rule val workManagerRule = WorkManagerRule()
    private val app = ApplicationProvider.getApplicationContext<Application>()
    private val notifications = app.getSystemService(NotificationManager::class.java)
    private lateinit var decisions: String

    @Before fun setUp() {
        onPersistenceWorker {
            AuditStores.install(app, enrolled = true)
            ResearchPersistenceGate.initialize(app)
        }
        Utils.createNotificationChannel(app)
        check(EnrollmentTestStores.prefs!!.edit().putBoolean(IDENTIFY_USER, false).commit())
        decisions = onPersistenceWorker { ChronicleDb.getInstance(app).collectionModuleStateDao().getAll().toString() }
        val settingsScreen = ComponentName("com.android.settings", "com.android.settings.Target")
        shadowOf(app.packageManager).addActivityIfNotPresent(settingsScreen)
        for (action in listOf(Settings.ACTION_APP_NOTIFICATION_SETTINGS, Settings.ACTION_CHANNEL_NOTIFICATION_SETTINGS)) {
            shadowOf(app.packageManager).addIntentFilterForActivity(settingsScreen,
                IntentFilter(action).apply { addCategory(Intent.CATEGORY_DEFAULT) })
        }
    }

    @After fun tearDown() = onPersistenceWorker {
        ChronicleDb.getInstance(app).close()
        EnrollmentTestStores.db = null
        EnrollmentTestStores.prefs = null
        ResearchPersistenceGate.resetAfterLocalStoreRecovery()
    }

    /**
     * What the participant changes on the channel's Settings screen. An app cannot raise a channel's importance, and
     * a deleted channel comes back with its old settings, so the channel Android holds is changed in place.
     */
    private fun promptChannelImportance(importance: Int) {
        Utils.createNotificationChannel(app)
        shadowOf(notifications).notificationChannels.single { it.id == IDENTIFY_USER_CHANNEL_ID }.importance = importance
        assertEquals(importance, notifications.getNotificationChannel(IDENTIFY_USER_CHANNEL_ID).importance)
    }

    private fun AppCompatActivity.settings() = supportFragmentManager.findFragmentById(android.R.id.content)!!.requireView()
    private fun AppCompatActivity.switch() = settings().findViewById<MaterialSwitch>(R.id.identifyUserSwitch)
    private fun AppCompatActivity.status() = settings().findViewById<TextView>(R.id.identifyUserStatus).text.toString()

    /** Accepts the latest dialog and returns the one screen it started. */
    private fun ActivityController<AppCompatActivity>.acceptDialog(): Intent? {
        generateSequence { shadowOf(get()).nextStartedActivity }.toList()
        (ShadowDialog.getLatestDialog() as AlertDialog).getButton(AlertDialog.BUTTON_POSITIVE).performClick()
        awaitDashboardUi { true }
        return shadowOf(get()).nextStartedActivity
    }

    private fun ActivityController<AppCompatActivity>.backFromSettings(rotated: Boolean) {
        pause().stop().start().resume()
        if (rotated) recreate()
        awaitDashboardUi { get().switch().isEnabled }
    }

    private fun ActivityController<AppCompatActivity>.turnOnAndConfirm() {
        generateSequence { shadowOf(app).nextStartedService }.toList()
        get().switch().performClick()
        awaitDashboardUi { ShadowDialog.getLatestDialog()?.isShowing == true }
        assertEquals(null, acceptDialog())
        awaitDashboardUi { get().switch().isChecked }
        assertTrue(EnrollmentTestStores.prefs!!.getBoolean(IDENTIFY_USER, false))
        assertEquals(DeviceUnlockMonitoringService::class.java.name, shadowOf(app).nextStartedService?.component?.className)
        assertEquals(app.getString(R.string.identify_user_status_active), get().status())
        assertEquals("consent decisions are untouched", decisions,
            onPersistenceWorker { ChronicleDb.getInstance(app).collectionModuleStateDao().getAll().toString() })
    }

    private fun journey(block: (ActivityController<AppCompatActivity>) -> Unit) = withHostedFragment(SettingsHomeFragment()) {
        awaitDashboardUi { it.get().switch().isEnabled }
        block(it)
    }

    @Test fun aDeniedPromptLeadsToAppNotificationSettingsAndTheSwitchWorksAfterARotatedReturn() {
        shadowOf(app).denyPermissions(Manifest.permission.POST_NOTIFICATIONS)
        journey { controller ->
            controller.get().switch().performClick()
            val request = shadowOf(controller.get()).lastRequestedPermission
            assertEquals(listOf(Manifest.permission.POST_NOTIFICATIONS), request.requestedPermissions.toList())
            controller.get().activityResultRegistry.dispatchResult(request.requestCode, Activity.RESULT_OK, Intent()
                .putExtra(ActivityResultContracts.RequestMultiplePermissions.EXTRA_PERMISSIONS, request.requestedPermissions)
                .putExtra(ActivityResultContracts.RequestMultiplePermissions.EXTRA_PERMISSION_GRANT_RESULTS,
                    intArrayOf(PackageManager.PERMISSION_DENIED)))
            awaitDashboardUi { ShadowDialog.getLatestDialog()?.isShowing == true }
            assertFalse(controller.get().switch().isChecked)

            val opened = controller.acceptDialog()!!
            assertEquals(Settings.ACTION_APP_NOTIFICATION_SETTINGS, opened.action)
            assertEquals(app.packageName, opened.getStringExtra(Settings.EXTRA_APP_PACKAGE))
            shadowOf(app).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
            controller.backFromSettings(rotated = true)
            controller.turnOnAndConfirm()

            // Revoked while away: the mode stays chosen, and Settings says the prompt cannot show.
            shadowOf(app).denyPermissions(Manifest.permission.POST_NOTIFICATIONS)
            controller.backFromSettings(rotated = false)
            awaitDashboardUi { controller.get().status() == app.getString(R.string.identify_user_status_notification_recovery) }
        }
    }

    /** a5-android-enroll-3: denied survey reminders say so, open the app's notification screen, and read granted on return. */
    @Test fun aDeniedSurveyReminderOpensTheAppNotificationScreenAndReadsGrantedOnReturn() {
        onPersistenceWorker { EnrollmentSettings(app).setAwarenessNotificationsEnabled(true) }
        shadowOf(app).denyPermissions(Manifest.permission.POST_NOTIFICATIONS)
        journey { controller ->
            val status = controller.get().settings().findViewById<TextView>(R.id.surveyNotificationStatus)
            awaitDashboardUi {
                status.visibility == View.VISIBLE && status.text.toString() == app.getString(R.string.survey_notifications_denied)
            }
            generateSequence { shadowOf(controller.get()).nextStartedActivity }.toList()
            assertTrue(controller.get().settings().findViewById<View>(R.id.surveyNotificationSettingsButton).performClick())
            val opened = shadowOf(controller.get()).nextStartedActivity
            assertEquals(Settings.ACTION_APP_NOTIFICATION_SETTINGS, opened.action)
            assertEquals(app.packageName, opened.getStringExtra(Settings.EXTRA_APP_PACKAGE))

            shadowOf(app).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
            controller.backFromSettings(rotated = true)
            val returned = controller.get().settings().findViewById<TextView>(R.id.surveyNotificationStatus)
            awaitDashboardUi {
                returned.visibility == View.VISIBLE && returned.text.toString() == app.getString(R.string.survey_notifications_granted)
            }
        }
    }

    @Test fun appNotificationsOffLeadToTheAppScreenAndAMutedPromptChannelToItsOwnScreen() {
        for (muted in listOf(false, true)) {
            check(EnrollmentTestStores.prefs!!.edit().putBoolean(IDENTIFY_USER, false).commit())
            if (muted) {
                promptChannelImportance(NotificationManager.IMPORTANCE_NONE)
            } else {
                shadowOf(notifications).setNotificationsEnabled(false)
            }
            journey { controller ->
                controller.get().switch().performClick()
                awaitDashboardUi { ShadowDialog.getLatestDialog()?.isShowing == true }
                val opened = controller.acceptDialog()!!
                assertEquals(if (muted) Settings.ACTION_CHANNEL_NOTIFICATION_SETTINGS else Settings.ACTION_APP_NOTIFICATION_SETTINGS,
                    opened.action)
                assertEquals(app.packageName, opened.getStringExtra(Settings.EXTRA_APP_PACKAGE))
                if (muted) assertEquals(IDENTIFY_USER_CHANNEL_ID, opened.getStringExtra(Settings.EXTRA_CHANNEL_ID))

                if (muted) {
                    promptChannelImportance(NotificationManager.IMPORTANCE_HIGH)
                } else {
                    shadowOf(notifications).setNotificationsEnabled(true)
                }
                controller.backFromSettings(rotated = false)
                controller.turnOnAndConfirm()
            }
        }
    }
    /**
     * The phone stops being shared: turning the switch off persists it, and the device user goes back to Not set, so
     * later usage is not attributed to whoever answered the prompt last.
     */
    @Test fun turningSharedModeOffClearsTheDeviceUser() {
        check(EnrollmentTestStores.prefs!!.edit().putBoolean(IDENTIFY_USER, true).commit())
        assertTrue(onPersistenceWorker { TargetUserRouter.setTargetUser(app, DeviceUser.STUDY_PARTICIPANT) } is ModuleResult.Ok)
        journey { controller ->
            awaitDashboardUi { controller.get().switch().isChecked }
            controller.get().switch().performClick()
            awaitDashboardUi { !EnrollmentTestStores.prefs!!.getBoolean(IDENTIFY_USER, true) }
            awaitDashboardUi { onPersistenceWorker { EnrollmentSettings(app).getCurrentUser() } == DeviceUser.NOT_SET.stored }
            assertFalse(controller.get().switch().isChecked)
        }
    }
}
