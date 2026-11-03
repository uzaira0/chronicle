package com.openlattice.chronicle.services.notifications

import android.app.AlarmManager
import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.IntentFilter
import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import androidx.work.ListenableWorker
import com.openlattice.chronicle.EnrollmentTestStores
import com.openlattice.chronicle.WorkManagerRule
import com.openlattice.chronicle.api.ChronicleStudyApi
import com.openlattice.chronicle.audit.AuditStores
import com.openlattice.chronicle.audit.auditWorkerParameters
import com.openlattice.chronicle.collection.CollectionModuleId
import com.openlattice.chronicle.collection.CollectionModuleSetting
import com.openlattice.chronicle.collection.state.ResearchPersistenceGate
import com.openlattice.chronicle.collection.state.onPersistenceWorker
import com.openlattice.chronicle.data.ParticipationStatus
import com.openlattice.chronicle.participantaccess.MobileReminderConfiguration
import com.openlattice.chronicle.participantaccess.MobileReminderForm
import com.openlattice.chronicle.participantaccess.ParticipantFormKind
import com.openlattice.chronicle.preferences.EnrollmentSettings
import com.openlattice.chronicle.receivers.lifecycle.SurveyNotificationsReceiver
import com.openlattice.chronicle.storage.ChronicleDb
import com.openlattice.chronicle.utils.Utils
import java.io.IOException
import java.lang.reflect.Proxy
import java.time.LocalTime
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowAlarmManager

/** The armed daily reminder survives a process restart that Android, not the participant, caused. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ReminderProcessRestartTest {
    @get:Rule val workManagerRule = WorkManagerRule()
    private val context: Context = ApplicationProvider.getApplicationContext()

    @After fun tearDown() = onPersistenceWorker {
        reminderClock = System::currentTimeMillis
        ShadowAlarmManager.setCanScheduleExactAlarms(false)
        EnrollmentTestStores.api = null
        ChronicleDb.getInstance(context).close()
        EnrollmentTestStores.db = null
        EnrollmentTestStores.prefs = null
        ResearchPersistenceGate.resetAfterLocalStoreRecovery()
    }

    /**
     * P2: revoking "Alarms & reminders" kills the process and cancels its exact alarms
     * (https://developer.android.com/develop/background-work/services/alarms#using-the-schedule_exact_alarm-permission).
     * The next process start, offline, re-arms the cached occurrence as an inexact alarm.
     */
    @Test fun processStartAfterExactAlarmRevocationRestoresTheCachedReminderInexactly() {
        ShadowAlarmManager.setCanScheduleExactAlarms(true)
        val armed = armDailyReminder()
        assertEquals(ShadowAlarmManager.WINDOW_EXACT, armed.windowLengthMs)

        ShadowAlarmManager.setCanScheduleExactAlarms(false)
        context.getSystemService(AlarmManager::class.java).cancel(armedOperation())
        EnrollmentTestStores.api = studyApi { throw IOException("offline") }
        ResearchPersistenceGate.resetAfterLocalStoreRecovery()
        ResearchPersistenceGate.initializeAsync(context) // ChronicleApplication.onCreate
        drainAuthorizationWorker()

        val restored = reminderAlarms(context)
        assertEquals(1, restored.size)
        assertEquals(armed.triggerAtMs, restored.single().triggerAtMs)
        assertNotEquals(ShadowAlarmManager.WINDOW_EXACT, restored.single().windowLengthMs)
    }

    /**
     * The alarm cold-starts the process and authorization takes longer than the receiver may wait. The occurrence is
     * not posted late: the next one, armed at the next process start, reminds the participant.
     */
    @Test fun aReminderThatFiresBeforeAuthorizationIsNotPostedLateAndTheNextIsArmed() {
        val armed = armDailyReminder()
        val fired = shadowOf(armedOperation()).savedIntent
        context.getSystemService(AlarmManager::class.java).cancel(armedOperation())
        reminderClock = { armed.triggerAtMs + TimeUnit.MINUTES.toMillis(1) }
        EnrollmentTestStores.leaveEnrollmentUnpublished(context)
        ResearchPersistenceGate.resetAfterLocalStoreRecovery()
        val held = CountDownLatch(1)
        val release = CountDownLatch(1)
        ResearchPersistenceGate.executeAsync { held.countDown(); release.await(60, TimeUnit.SECONDS) }
        assertTrue(held.await(10, TimeUnit.SECONDS))
        val receiver = SurveyNotificationsReceiver()
        try {
            context.registerReceiver(receiver, IntentFilter(SURVEY_NOTIFICATION_ACTION), Context.RECEIVER_NOT_EXPORTED)
            context.sendBroadcast(fired)
            shadowOf(Looper.getMainLooper()).idle()
            shadowOf(shadowOf(receiver).originalPendingResult).future.get(30, TimeUnit.SECONDS)
        } finally { release.countDown(); context.unregisterReceiver(receiver) }
        restartProcessOffline()

        assertEquals(emptyList<String>(), postedTitles())
        assertTrue(reminderAlarms(context).single().triggerAtMs > reminderClock())
    }

    /**
     * P2 after the trigger: exact access is revoked before the alarm (Android cancels it), and the app next starts
     * after the trigger. The missed occurrence is not posted late; the next one is armed.
     */
    @Test fun aReminderMissedWhileItsAlarmWasGoneIsNotPostedLateAndTheNextIsArmed() {
        ShadowAlarmManager.setCanScheduleExactAlarms(true)
        val armed = armDailyReminder()
        revokeExactAlarms()
        for (late in listOf(TimeUnit.MINUTES.toMillis(30), TimeUnit.HOURS.toMillis(25))) {
            reminderClock = { armed.triggerAtMs + late }
            restartProcessOffline()

            assertEquals(emptyList<String>(), postedTitles())
            assertTrue(reminderAlarms(context).single().triggerAtMs > reminderClock())
        }
    }

    /** The alarm itself cold-starts the process: its own broadcast posts the occurrence once, and the restore does not. */
    @Test fun anAlarmThatColdStartsTheProcessIsPostedOnceByItsBroadcast() {
        val armed = armDailyReminder()
        val fired = shadowOf(armedOperation()).savedIntent
        context.getSystemService(AlarmManager::class.java).cancel(armedOperation())
        reminderClock = { armed.triggerAtMs + TimeUnit.MINUTES.toMillis(1) }
        restartProcessOffline()
        assertEquals(emptyList<String>(), postedTitles())

        val receiver = SurveyNotificationsReceiver()
        try {
            context.registerReceiver(receiver, IntentFilter(SURVEY_NOTIFICATION_ACTION), Context.RECEIVER_NOT_EXPORTED)
            context.sendBroadcast(fired)
            shadowOf(Looper.getMainLooper()).idle()
            shadowOf(shadowOf(receiver).originalPendingResult).future.get(30, TimeUnit.SECONDS)
        } finally { context.unregisterReceiver(receiver) }
        assertEquals(listOf("Daily survey"), postedTitles())
        assertTrue(reminderAlarms(context).single().triggerAtMs > reminderClock())
    }

    /**
     * The clock is set back across an occurrence already posted: the refresh re-arms it, it fires again, and is not
     * posted again; its successor is still armed.
     */
    @Test fun aRepeatedOccurrenceIsNotPostedAgainButStillArmsItsSuccessor() {
        val armed = armDailyReminder()
        val first = shadowOf(armedOperation()).savedIntent
        fire(first, armed.triggerAtMs + TimeUnit.MINUTES.toMillis(1))
        assertEquals(listOf("Daily survey"), postedTitles())
        context.getSystemService(NotificationManager::class.java).cancelAll()

        reminderClock = { armed.triggerAtMs - TimeUnit.HOURS.toMillis(1) }
        restartProcessOffline()
        assertEquals(armed.triggerAtMs, reminderAlarms(context).single().triggerAtMs)
        fire(shadowOf(armedOperation()).savedIntent, armed.triggerAtMs + TimeUnit.MINUTES.toMillis(1))

        assertEquals(emptyList<String>(), postedTitles())
        assertTrue(reminderAlarms(context).single().triggerAtMs > armed.triggerAtMs)
    }

    /**
     * A required decline is a hard stop: the process start after it re-arms no reminder,
     * though the enrollment stays ENROLLED for a re-acceptance.
     */
    @Test fun aProcessStartAfterARequiredDeclineArmsNoReminder() {
        armDailyReminder()
        context.getSystemService(AlarmManager::class.java).cancel(armedOperation())
        val modules = ChronicleDb.getInstance(context).collectionModuleStateDao()
        val required = modules.getAll().single { it.requiredApplied }
        onPersistenceWorker { modules.upsertAll(listOf(required.copy(decision = "DECLINED"))) }
        restartProcessOffline()
        assertEquals(ParticipationStatus.ENROLLED, EnrollmentSettings(context).getParticipationStatus())
        assertEquals(emptyList<ShadowAlarmManager.ScheduledAlarm>(), reminderAlarms(context))
    }

    /**
     * Android removes the exact alarm and kills the process. The alarm's PendingIntent record can outlive it
     * until system_server collects it, so it stays here.
     */
    private fun revokeExactAlarms() {
        ShadowAlarmManager.setCanScheduleExactAlarms(false)
        context.getSystemService(AlarmManager::class.java).cancel(armedOperation())
    }

    /** The alarm's broadcast arrives at [at]: Android has dropped the consumed alarm. */
    private fun fire(intent: android.content.Intent, at: Long) {
        context.getSystemService(AlarmManager::class.java).cancel(armedOperation())
        reminderClock = { at }
        val receiver = SurveyNotificationsReceiver()
        try {
            context.registerReceiver(receiver, IntentFilter(SURVEY_NOTIFICATION_ACTION), Context.RECEIVER_NOT_EXPORTED)
            context.sendBroadcast(intent)
            shadowOf(Looper.getMainLooper()).idle()
            shadowOf(shadowOf(receiver).originalPendingResult).future.get(30, TimeUnit.SECONDS)
        } finally { context.unregisterReceiver(receiver) }
    }

    private fun restartProcessOffline() {
        EnrollmentTestStores.api = studyApi { throw IOException("offline") }
        ResearchPersistenceGate.resetAfterLocalStoreRecovery()
        ResearchPersistenceGate.initializeAsync(context) // ChronicleApplication.onCreate
        drainAuthorizationWorker()
        drainAuthorizationWorker()
    }

    private val notifications get() = shadowOf(context.getSystemService(NotificationManager::class.java))
    private fun postedTitles() = notifications.allNotifications.map { it.extras.getString(Notification.EXTRA_TITLE) }

    /**
     * Arms the pilot daily survey reminder through the network refresh, about two hours from now, once the
     * process start's own initialization (ChronicleApplication), which also restores alarms, has finished.
     */
    private fun armDailyReminder(): ShadowAlarmManager.ScheduledAlarm {
        drainAuthorizationWorker()
        return onPersistenceWorker {
            AuditStores.install(context, enrolled = true,
                mapOf(CollectionModuleId.QUESTIONNAIRE to CollectionModuleSetting(enabled = false)))
            ResearchPersistenceGate.initialize(context)
            val hour = (LocalTime.now().hour + 2) % 24
            val schedule = MobileReminderConfiguration(ParticipationStatus.ENROLLED, listOf(
                MobileReminderForm(ParticipantFormKind.APP_USAGE, null, "Daily survey",
                    "FREQ=DAILY;BYHOUR=$hour;BYMINUTE=0;BYSECOND=0", unansweredDays = true),
            ))
            EnrollmentTestStores.api = studyApi { schedule }
            assertEquals(ListenableWorker.Result.success(), NotificationsWorker(context, auditWorkerParameters()).doWork())
            reminderAlarms(context).single()
        }
    }

    /** The armed reminder's broadcast: its intent filter matches whatever extras the latest arm wrote. */
    private fun armedOperation(): PendingIntent = requireNotNull(PendingIntent.getBroadcast(context,
        EnrollmentSettings(context).getMobileReminderRequestCodes().single(), reminderAlarmIntent(context),
        Utils.getPendingIntentMutabilityFlag(PendingIntent.FLAG_NO_CREATE)))

    private fun studyApi(answer: () -> Any) = Proxy.newProxyInstance(ChronicleStudyApi::class.java.classLoader,
        arrayOf(ChronicleStudyApi::class.java)) { _, method, _ ->
        check(method.name == "getMobileReminderSchedule") { method.name }
        answer()
    } as ChronicleStudyApi

    private fun drainAuthorizationWorker() {
        val drained = CountDownLatch(1)
        ResearchPersistenceGate.executeAsync { drained.countDown() }
        assertTrue(drained.await(30, TimeUnit.SECONDS))
        shadowOf(Looper.getMainLooper()).idle()
    }
}
