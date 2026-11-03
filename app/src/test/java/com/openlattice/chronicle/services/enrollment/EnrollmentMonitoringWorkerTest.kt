package com.openlattice.chronicle.services.enrollment

import android.app.AlarmManager
import android.app.Application
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.test.core.app.ApplicationProvider
import androidx.work.ListenableWorker
import com.openlattice.chronicle.EnrollmentTestStores
import com.openlattice.chronicle.R
import com.openlattice.chronicle.WorkManagerRule
import com.openlattice.chronicle.audit.AuditStores
import com.openlattice.chronicle.audit.auditWorkerParameters
import com.openlattice.chronicle.audit.studyApi
import com.openlattice.chronicle.collection.state.BackgroundPersistenceRule
import com.openlattice.chronicle.collection.state.ResearchPersistenceGate
import com.openlattice.chronicle.collection.state.onPersistenceWorker
import com.openlattice.chronicle.data.MobileParticipationStatus
import com.openlattice.chronicle.data.ParticipationStatus
import com.openlattice.chronicle.preferences.EnrollmentSettings
import com.openlattice.chronicle.services.notifications.CHANNEL_ID
import com.openlattice.chronicle.services.notifications.ReminderLinkActivity
import com.openlattice.chronicle.services.notifications.SURVEY_NOTIFICATION_TAG
import com.openlattice.chronicle.services.notifications.reminderAlarmIntent
import com.openlattice.chronicle.services.upload.LocalUploadDiagnosticsStore
import com.openlattice.chronicle.services.upload.LocalUploadModuleFamily
import com.openlattice.chronicle.services.withdrawal.WithdrawalState
import com.openlattice.chronicle.services.withdrawal.WithdrawalStateStore
import com.openlattice.chronicle.storage.ChronicleDb
import com.openlattice.chronicle.storage.QueueEntry
import java.io.IOException
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.util.Collections
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/** The 15-minute participation-status monitor on an enrolled pilot phone. */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class EnrollmentMonitoringWorkerTest {
    @get:Rule val workManagerRule = WorkManagerRule()
    @get:Rule val persistence = BackgroundPersistenceRule()
    private val context = ApplicationProvider.getApplicationContext<Application>()
    private val calls = Collections.synchronizedList(mutableListOf<String>())

    @Before fun enroll() {
        AuditStores.install(context, enrolled = true)
        onPersistenceWorker { ResearchPersistenceGate.initialize(context) }
    }

    @After fun close() {
        EnrollmentTestStores.db?.close()
        EnrollmentTestStores.db = null
        EnrollmentTestStores.prefs = null
        EnrollmentTestStores.api = null
        ResearchPersistenceGate.resetAfterLocalStoreRecovery()
    }

    /** The server's status answer; [answer] may throw to model an unreachable server. */
    private fun server(answer: () -> MobileParticipationStatus) {
        EnrollmentTestStores.api = studyApi { method, _ ->
            calls += method
            check(method == "getDeviceParticipationStatus") { "unexpected API $method" }
            answer()
        }
    }

    private fun status(value: ParticipationStatus) = MobileParticipationStatus(value, OffsetDateTime.now(ZoneOffset.UTC))
    private fun monitor() = onPersistenceWorker { EnrollmentMonitoringWorker(context, auditWorkerParameters()).doWork() }
    private fun participation() = onPersistenceWorker { EnrollmentSettings(context).getParticipationStatus() }

    @Test fun aPauseByTheStudyTeamIsAppliedWithoutTheParticipantOpeningTheApp() {
        server { status(ParticipationStatus.PAUSED) }

        assertEquals(ListenableWorker.Result.success(), monitor())
        assertEquals(ParticipationStatus.PAUSED, participation())
        assertEquals(listOf("getDeviceParticipationStatus"), calls.toList())
    }

    /** No network: the run fails, the phone keeps its last known status, and the study sees why in the diagnostics. */
    @Test fun anUnreachableServerKeepsTheStatusAndRecordsAStudyVisibleFailure() {
        server { throw IOException("no route to host") }

        assertEquals(ListenableWorker.Result.failure(), monitor())
        assertEquals(ParticipationStatus.ENROLLED, participation())
        val recorded = onPersistenceWorker { LocalUploadDiagnosticsStore.of(context).pending() }
        assertTrue(recorded.toString(), recorded.any { it.moduleFamily == LocalUploadModuleFamily.APP_RUNTIME.name })
    }

    /**
     * The study team removed the participant and their data: the phone ends the enrollment and keeps nothing, and the
     * survey reminder still in the tray leaves it and can no longer open the form.
     */
    @Test fun anErasedParticipantEndsTheEnrollmentAndKeepsNothing() {
        onPersistenceWorker { ChronicleDb.getInstance(context).queueEntryData().insertEntry(QueueEntry(1, 1, ByteArray(1))) }
        val open = PendingIntent.getActivity(context, 7, Intent(context, ReminderLinkActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE)
        NotificationManagerCompat.from(context).notify(SURVEY_NOTIFICATION_TAG, 7, NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_notification).setContentIntent(open).build())
        server { status(ParticipationStatus.ERASED) }

        assertEquals(ListenableWorker.Result.success(), monitor())
        assertEquals(WithdrawalState.COMPLETE, onPersistenceWorker { WithdrawalStateStore(context).state() })
        assertNull(onPersistenceWorker { ChronicleDb.getInstance(context).uploadServerDao().getConfiguredServer() })
        assertEquals(0, onPersistenceWorker { ChronicleDb.getInstance(context).queueEntryData().getSize() })
        assertTrue(shadowOf(context.getSystemService(NotificationManager::class.java)).allNotifications.isEmpty())
        assertTrue("the reminder can no longer open the form", shadowOf(open).isCanceled)
    }

    /** Scheduled on a phone that is not (or no longer) enrolled: nothing is asked of any server. */
    @Test fun aPhoneWithoutAnEnrollmentAsksNoServer() {
        EnrollmentTestStores.install(context, enrolled = false)
        server { error("no status request expected") }

        assertEquals(ListenableWorker.Result.success(), monitor())
        assertEquals(emptyList<String>(), calls.toList())
    }

    /** The study team pauses the participant: the armed survey reminder and the one in the tray are both withdrawn. */
    @Test fun aPauseWithdrawsTheArmedAndPostedSurveyReminders() {
        onPersistenceWorker { EnrollmentSettings(context).setMobileReminderRequestCodes(setOf(7)) }
        val alarm = PendingIntent.getBroadcast(context, 7, reminderAlarmIntent(context), PendingIntent.FLAG_IMMUTABLE)
        val alarms = context.getSystemService(AlarmManager::class.java)
        alarms.set(AlarmManager.RTC_WAKEUP, System.currentTimeMillis() + 3_600_000, alarm)
        NotificationManagerCompat.from(context).notify(SURVEY_NOTIFICATION_TAG, 7, NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_notification).build())
        server { status(ParticipationStatus.PAUSED) }

        assertEquals(ListenableWorker.Result.success(), monitor())
        assertTrue("the reminder alarm is cancelled", shadowOf(alarms).scheduledAlarms.isEmpty())
        assertTrue(shadowOf(context.getSystemService(NotificationManager::class.java)).allNotifications.isEmpty())
    }
}
