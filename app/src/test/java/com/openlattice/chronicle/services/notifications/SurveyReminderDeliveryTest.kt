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
import com.openlattice.chronicle.BuildConfig
import com.openlattice.chronicle.EnrollmentTestStores
import com.openlattice.chronicle.api.ChronicleStudyApi
import com.openlattice.chronicle.audit.AuditStores
import com.openlattice.chronicle.audit.auditWorkerParameters
import com.openlattice.chronicle.collection.CollectionModuleId
import com.openlattice.chronicle.collection.CollectionModuleSetting
import com.openlattice.chronicle.collection.state.ResearchPersistenceGate
import com.openlattice.chronicle.collection.state.StorageAdmission
import com.openlattice.chronicle.collection.state.onPersistenceWorker
import com.openlattice.chronicle.data.ParticipationStatus
import com.openlattice.chronicle.participantaccess.MobileReminderConfiguration
import com.openlattice.chronicle.participantaccess.MobileReminderForm
import com.openlattice.chronicle.participantaccess.ParticipantFormKind
import com.openlattice.chronicle.preferences.EnrollmentSettings
import com.openlattice.chronicle.preferences.IDENTIFY_USER
import com.openlattice.chronicle.preferences.EncryptedPrefsHelper
import com.openlattice.chronicle.receivers.lifecycle.SurveyNotificationsReceiver
import com.openlattice.chronicle.storage.ChronicleDb
import java.lang.reflect.Proxy
import java.util.UUID
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf

/** An armed reminder alarm, delivered to its receiver, posts the participant's notification. */
@RunWith(RobolectricTestRunner::class)
class SurveyReminderDeliveryTest {
    private val context: Context = ApplicationProvider.getApplicationContext()

    @After fun tearDown() = onPersistenceWorker {
        StorageAdmission.clear(context)
        reminderClock = System::currentTimeMillis
        EnrollmentTestStores.api = null
        ChronicleDb.getInstance(context).close()
        EnrollmentTestStores.db = null
        EnrollmentTestStores.prefs = null
        ResearchPersistenceGate.resetAfterLocalStoreRecovery()
    }

    // Flavors without participant-form reminders (Amazon) arm and post nothing.
    @Test fun appUsageReminderPostsWhileAnEndedDayIsUnanswered() =
        assertEquals("Daily survey".takeIf { BuildConfig.ALLOW_PARTICIPANT_FORM_REMINDERS },
            deliverWithQuestionnairesOff(ParticipantFormKind.APP_USAGE, null))

    /** Reminders repeat only until every ended day is answered; the alarm keeps recurring for the next day. */
    @Test fun appUsageReminderStaysSilentOnceEveryDayIsAnswered() {
        assertEquals(null, deliverWithQuestionnairesOff(ParticipantFormKind.APP_USAGE, null, unansweredDays = false))
        if (BuildConfig.ALLOW_PARTICIPANT_FORM_REMINDERS) {
            assertTrue(reminderAlarms(context).single().triggerAtMs > armedAt)
        }
    }

    /** A reminder writes no research data; a full disk does not silence it. */
    @Test fun appUsageReminderPostsWhileResearchStorageIsRefused() =
        assertEquals("Daily survey".takeIf { BuildConfig.ALLOW_PARTICIPANT_FORM_REMINDERS },
            deliverWithQuestionnairesOff(ParticipantFormKind.APP_USAGE, null, storageWhenFired = false))

    /** The survey asks who used a shared phone: an alarm armed before the phone stopped being shared posts nothing. */
    @Test fun appUsageReminderStaysSilentOnceThePhoneIsNoLongerShared() =
        assertEquals(null, deliverWithQuestionnairesOff(ParticipantFormKind.APP_USAGE, null, sharedWhenFired = false))

    @Test fun questionnaireReminderStaysSilentWhileTheModuleIsOff() =
        assertEquals(null, deliverWithQuestionnairesOff(ParticipantFormKind.QUESTIONNAIRE, UUID.randomUUID()))

    /** Arms one 19:00 reminder in the pilot setup (questionnaire module off), fires it, returns the posted title. */
    private var armedAt = 0L

    private fun deliverWithQuestionnairesOff(kind: ParticipantFormKind, resourceId: UUID?, unansweredDays: Boolean = true,
                                             sharedWhenFired: Boolean = true, storageWhenFired: Boolean = true): String? {
        val schedule = MobileReminderConfiguration(ParticipationStatus.ENROLLED, listOf(
            MobileReminderForm(kind, resourceId, "Daily survey",
                "FREQ=DAILY;BYHOUR=19;BYMINUTE=0;BYSECOND=0", unansweredDays = unansweredDays),
        ))
        val alarm = onPersistenceWorker {
            AuditStores.install(context, enrolled = true,
                mapOf(CollectionModuleId.QUESTIONNAIRE to CollectionModuleSetting(enabled = false)))
            ResearchPersistenceGate.initialize(context)
            EnrollmentTestStores.api = Proxy.newProxyInstance(ChronicleStudyApi::class.java.classLoader,
                arrayOf(ChronicleStudyApi::class.java)) { _, method, _ ->
                check(method.name == "getMobileReminderSchedule") { method.name }
                schedule
            } as ChronicleStudyApi
            assertEquals(ListenableWorker.Result.success(), NotificationsWorker(context, auditWorkerParameters()).doWork())
            EnrollmentSettings(context).getMobileReminderRequestCodes().singleOrNull()?.let {
                PendingIntent.getBroadcast(context, it, reminderAlarmIntent(context),
                    PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_IMMUTABLE)
            }
        }
        if (!BuildConfig.ALLOW_PARTICIPANT_FORM_REMINDERS) return alarm?.let { "armed" }
        if (!sharedWhenFired) onPersistenceWorker {
            check(EncryptedPrefsHelper.getEncryptedPrefs(context).edit().putBoolean(IDENTIFY_USER, false).commit())
        }
        if (!storageWhenFired) {
            onPersistenceWorker { check(!StorageAdmission.allowed(context, 0L)) }
            context.getSystemService(NotificationManager::class.java).cancelAll() // the storage-pause notice
        }
        armedAt = reminderAlarms(context).single().triggerAtMs
        // The alarm fires at its occurrence; Android drops the consumed alarm.
        context.getSystemService(AlarmManager::class.java).cancel(checkNotNull(alarm))
        reminderClock = { armedAt + 1_000L }

        // Robolectric routes broadcasts only to filtered receivers; the manifest entry has no filter.
        context.registerReceiver(SurveyNotificationsReceiver(), IntentFilter(SURVEY_NOTIFICATION_ACTION),
            Context.RECEIVER_NOT_EXPORTED)
        context.sendBroadcast(shadowOf(checkNotNull(alarm) { "reminder not armed" }).savedIntent)
        val notifications = shadowOf(context.getSystemService(NotificationManager::class.java))
        // The receiver admits on a background thread; a silent reminder waits out the whole window.
        val deadline = System.currentTimeMillis() + 8_000
        while (notifications.allNotifications.isEmpty() && System.currentTimeMillis() < deadline) {
            shadowOf(Looper.getMainLooper()).idle()
            Thread.sleep(50)
        }
        return notifications.allNotifications.singleOrNull()?.extras?.getString(Notification.EXTRA_TITLE)
    }
}
