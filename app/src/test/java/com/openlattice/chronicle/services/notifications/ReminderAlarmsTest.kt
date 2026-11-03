package com.openlattice.chronicle.services.notifications

import android.app.AlarmManager
import android.content.Context
import android.app.PendingIntent
import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import androidx.work.impl.WorkManagerImpl
import androidx.work.impl.utils.ForceStopRunnable
import com.openlattice.chronicle.BuildConfig
import com.openlattice.chronicle.ChronicleApplication
import com.openlattice.chronicle.WorkManagerRule
import com.openlattice.chronicle.audit.AuditStores
import com.openlattice.chronicle.collection.state.ResearchPersistenceGate
import com.openlattice.chronicle.collection.state.onPersistenceWorker
import com.openlattice.chronicle.constants.NotificationType
import com.openlattice.chronicle.preferences.EnrollmentSettings
import com.openlattice.chronicle.receivers.lifecycle.SurveyNotificationsReceiver
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowAlarmManager
import org.robolectric.util.ReflectionHelpers
import org.robolectric.util.ReflectionHelpers.ClassParameter

/**
 * The alarms a reminder test can see that it did not arm. A reminder assertion failed in the full gate with two alarms
 * where its test armed one (ReminderWorkConstraintTest.anOverdueOccurrenceIsReplacedByTheNextOne).
 */
@RunWith(RobolectricTestRunner::class)
class ReminderAlarmsTest {
    @get:Rule val workManagerRule = WorkManagerRule()
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val rule = "FREQ=DAILY;BYHOUR=19;BYMINUTE=0;BYSECOND=0"

    private fun alarms() = shadowOf(context.getSystemService(AlarmManager::class.java)).scheduledAlarms

    private fun targets() = alarms().map { alarm -> alarmOperation(alarm)?.let { shadowOf(it).savedIntent.component?.className } }

    private fun receiverIntent() = Intent(context, SurveyNotificationsReceiver::class.java).setAction(SURVEY_NOTIFICATION_ACTION)

    private fun drainAuthorizationWorker() {
        val drained = CountDownLatch(1)
        ResearchPersistenceGate.executeAsync { drained.countDown() }
        assertTrue(drained.await(30, TimeUnit.SECONDS))
    }

    /** WorkManager's initialization runs its force-stop check (ForceStopRunnable) on its own task thread; wait for it. */
    private fun drainWorkManagerInitialization() {
        val drained = CountDownLatch(1)
        WorkManagerImpl.getInstance(context).workTaskExecutor.executeOnTaskThread { drained.countDown() }
        assertTrue(drained.await(30, TimeUnit.SECONDS))
    }

    /** From Android 11 WorkManager detects a force stop from the process exit reasons and arms nothing. */
    @Test fun workManagerArmsNoAlarmAtTheDefaultSdk() {
        drainWorkManagerInitialization()
        assertEquals(emptyList<String?>(), targets())
    }

    /** A surviving sentinel skips arming; a missing sentinel arms WorkManager's alarm, which is not a reminder. */
    @Config(sdk = [28])
    @Test fun workManagersForceStopAlarmBelowAndroid11IsNotAReminder() {
        drainWorkManagerInitialization()
        val probe = ForceStopRunnable(context, WorkManagerImpl.getInstance(context))
        val sentinel = ReflectionHelpers.callStaticMethod<PendingIntent>(ForceStopRunnable::class.java, "getPendingIntent",
            ClassParameter.from(Context::class.java, context), ClassParameter.from(Int::class.java, PendingIntent.FLAG_UPDATE_CURRENT))
        context.getSystemService(AlarmManager::class.java).cancel(sentinel)
        assertFalse(probe.isForceStopped)
        assertEquals(emptyList<String?>(), targets())

        sentinel.cancel()
        assertTrue(probe.isForceStopped)
        assertEquals(listOf(ForceStopRunnable.BroadcastReceiver::class.java.name), targets())
        assertEquals(emptyList<ShadowAlarmManager.ScheduledAlarm>(), reminderAlarms(context))
    }

    /**
     * The failure in the full gate: ReminderWorkConstraintTest.offlineBootRestores… leaves an enrolled store with an
     * armed, cached reminder, and the stores are process statics. The next test's process start (what
     * ChronicleApplication.onCreate queues) restored that reminder into the next test. The application shadow now starts
     * each test from empty stores, so its process start restores nothing.
     */
    @Test fun aPreviousTestsEnrollmentNeverReachesTheNextProcessStart() {
        // Without participant-form reminders (amazon) a restore arms nothing, so there is no leak to reproduce.
        assumeTrue(BuildConfig.ALLOW_PARTICIPANT_FORM_REMINDERS)
        drainAuthorizationWorker()
        val previous = NotificationDetails("form", NotificationType.AWARENESS, rule, "Check-in", "Tap")
        onPersistenceWorker {
            AuditStores.install(context, enrolled = true)
            ResearchPersistenceGate.initialize(context)
            EnrollmentSettings(context).setMobileReminderRequestCodes(setOf(previous.requestCode()))
            checkNotNull(armReminder(context, previous, receiverIntent()))
        }
        // Between tests Robolectric drops the alarms and the app's preferences; the enrollment stores stay.
        fun nextTest() {
            context.getSystemService(AlarmManager::class.java).cancel(checkNotNull(PendingIntent.getBroadcast(context,
                previous.requestCode(), receiverIntent(), PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_IMMUTABLE)))
            context.getSharedPreferences(REMINDER_ALARM_STATE_PREFS, Context.MODE_PRIVATE).edit().clear().commit()
            assertEquals(emptyList<ShadowAlarmManager.ScheduledAlarm>(), reminderAlarms(context))
        }
        nextTest()
        ResearchPersistenceGate.initializeAsync(context)
        drainAuthorizationWorker()
        assertEquals("a bare process start re-arms the previous test's reminder",
            listOf(previous.requestCode()), reminderAlarms(context).map { shadowOf(checkNotNull(alarmOperation(it))).requestCode })

        nextTest()
        (context as ChronicleApplication).onCreate()
        drainAuthorizationWorker()
        assertEquals(emptyList<ShadowAlarmManager.ScheduledAlarm>(), reminderAlarms(context))
    }
}
