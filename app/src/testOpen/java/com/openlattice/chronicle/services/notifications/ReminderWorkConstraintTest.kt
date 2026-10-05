package com.openlattice.chronicle.services.notifications

import android.app.AlarmManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.test.core.app.ApplicationProvider
import androidx.work.Configuration
import androidx.work.NetworkType
import androidx.work.WorkManager
import com.google.gson.Gson
import com.openlattice.chronicle.api.ChronicleStudyApi
import com.openlattice.chronicle.constants.NotificationType
import com.openlattice.chronicle.data.ParticipationStatus
import com.openlattice.chronicle.layout.TestStores
import com.openlattice.chronicle.participantaccess.MobileReminderConfiguration
import com.openlattice.chronicle.preferences.EnrollmentSettings
import com.openlattice.chronicle.receivers.lifecycle.SurveyNotificationsReceiver
import com.openlattice.chronicle.serialization.ChronicleCallException
import com.openlattice.chronicle.utils.Utils
import java.lang.reflect.Proxy
import java.time.Instant
import java.time.ZoneId
import java.util.TimeZone
import java.util.UUID
import java.util.concurrent.Executor
import java.util.concurrent.TimeUnit
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf

/** After a reboot the reminder sync must wait for network, not fail into 15 minutes of backoff. */
@RunWith(RobolectricTestRunner::class)
class ReminderWorkConstraintTest {
    private val context: Context = ApplicationProvider.getApplicationContext()

    @Test fun reminderWorkWaitsForNetwork() {
        if (!WorkManager.isInitialized()) WorkManager.initialize(context, Configuration.Builder().setExecutor(Executor { }).build())

        scheduleNotificationsWorker(context)

        val info = WorkManager.getInstance(context).getWorkInfosForUniqueWork("notifications").get(5, TimeUnit.SECONDS).single()
        assertEquals(NetworkType.CONNECTED, info.constraints.requiredNetworkType)
    }

    @Test fun periodicRefreshKeepsAnOverdueUndeliveredOccurrenceArmed() {
        val details = reminder()
        plant(details, System.currentTimeMillis() - 10_000L)

        assertNull(armReminder(context, details, receiverIntent()))
        assertTrue(alarms().isEmpty())
    }

    /** A force-stop drops the alarm but not the stored time; it must not block re-arming forever. */
    @Test fun periodicRefreshReplacesAnOccurrenceOverdueBeyondTheGrace() {
        val details = reminder()
        plant(details, System.currentTimeMillis() - REMINDER_OVERDUE_GRACE_MS - 1L)

        assertArmedAt(getNextRecurringDate(details.recurrenceRule, System.currentTimeMillis())!!, armReminder(context, details, receiverIntent()))
    }

    /** V-14: a reboot dropped the overdue alarm, so the grace must not hold back the next occurrence. */
    @Test fun overdueOccurrenceFromAnEarlierBootIsReplaced() {
        val details = reminder()
        Settings.Global.putInt(context.contentResolver, Settings.Global.BOOT_COUNT, 7)
        plant(details, System.currentTimeMillis() - TimeUnit.MINUTES.toMillis(10), boot = 6)

        assertArmedAt(getNextRecurringDate(details.recurrenceRule, System.currentTimeMillis())!!, armReminder(context, details, receiverIntent()))
    }

    /** The start instant matches these rules; returning it would re-fire the alarm immediately. */
    @Test fun nextOccurrenceIsStrictlyAfterTheRequestedInstant() {
        val now = System.currentTimeMillis()
        assertTrue(getNextRecurringDate("FREQ=DAILY", now)!! > now)
        val atSeven = java.util.Calendar.getInstance().apply {
            set(java.util.Calendar.HOUR_OF_DAY, 19); set(java.util.Calendar.MINUTE, 0)
            set(java.util.Calendar.SECOND, 0); set(java.util.Calendar.MILLISECOND, 40)
        }.timeInMillis
        assertTrue(getNextRecurringDate("FREQ=DAILY;BYHOUR=19;BYMINUTE=0;BYSECOND=0", atSeven)!! > atSeven + 1_000)
    }

    /** A sync must not push an armed bare-daily reminder forward; it re-arms the same instant. */
    @Test fun syncKeepsTheArmedOccurrenceOfAnUnchangedRule() {
        val details = reminder("FREQ=DAILY")
        val armedAt = System.currentTimeMillis() + TimeUnit.HOURS.toMillis(23)
        plant(details, armedAt)

        assertArmedAt(armedAt, armReminder(context, details, receiverIntent()))
    }

    /** V-4/V-17/V-32: an occurrence armed in another zone, or before a clock correction, is recomputed. */
    @Test fun refreshRecomputesAnOccurrenceFromAnotherZoneOrBeyondTheNextOne() {
        val details = reminder()
        TimeZone.setDefault(TimeZone.getTimeZone("America/Chicago"))
        val chicagoSeven = getNextRecurringDate(details.recurrenceRule, System.currentTimeMillis())!!
        plant(details, chicagoSeven)
        TimeZone.setDefault(TimeZone.getTimeZone("America/Los_Angeles"))
        val losAngelesSeven = getNextRecurringDate(details.recurrenceRule, System.currentTimeMillis())!!

        assertArmedAt(losAngelesSeven, armReminder(context, details, receiverIntent()))

        plant(details, losAngelesSeven + TimeUnit.DAYS.toMillis(7)) // armed while the clock ran a week fast
        assertArmedAt(losAngelesSeven, armReminder(context, details, receiverIntent()))
    }

    /** V-5/V-15: a refresh after the receiver advanced the occurrence keeps it, never a past trigger. */
    @Test fun refreshAfterTheReceiverAdvancedKeepsTheAdvancedOccurrence() {
        val details = reminder()
        val consumed = System.currentTimeMillis() - 1_000L
        val advanced = armReminder(context, details, receiverIntent(), consumedAtMillis = consumed)!!
        assertTrue(advanced > System.currentTimeMillis())

        assertArmedAt(advanced, armReminder(context, details, receiverIntent()))
    }

    /**
     * V-5/V-15, interleaved: a refresh starts before the occurrence fires and waits on the lock while
     * the receiver consumes it and arms the next. A refresh that read the clock or the stored
     * occurrence before the lock would re-arm the consumed (now past) occurrence: a second reminder.
     */
    @Suppress("DEPRECATION") // Thread.id: ThreadMXBean still keys threads by it.
    @Test fun refreshWaitingOnTheReceiverReadsTheAdvancedOccurrence() {
        val details = reminder()
        val beforeFire = System.currentTimeMillis()
        val consumed = getNextRecurringDate(details.recurrenceRule, beforeFire)!!
        plant(details, consumed)
        var clock = beforeFire
        reminderClock = { clock }
        try {
            var refreshed: Long? = -1L
            val refresh = Thread { refreshed = armReminder(context, details, receiverIntent()) }
            val advanced = synchronized(REMINDER_SCHEDULE_LOCK) {
                refresh.start()
                val threads = java.lang.management.ManagementFactory.getThreadMXBean()
                val deadline = System.currentTimeMillis() + 5_000
                while (threads.getThreadInfo(refresh.id)?.lockOwnerId != Thread.currentThread().id) {
                    check(System.currentTimeMillis() < deadline) { "refresh never waited on the schedule lock" }
                    Thread.sleep(5)
                }
                clock = consumed + 1_000L // the alarm fires
                armReminder(context, details, receiverIntent(), consumedAtMillis = consumed)!!
            }
            refresh.join(5_000)

            assertTrue(advanced > consumed)
            assertArmedAt(advanced, refreshed)
        } finally {
            reminderClock = System::currentTimeMillis
        }
    }

    /** V-6: once a sync retired the request code, a racing delivery must not re-arm it. */
    @Test fun retiredRequestCodeIsNotReArmed() {
        val details = reminder()
        EnrollmentSettings(context).setMobileReminderRequestCodes(emptySet())

        assertNull(armReminder(context, details, receiverIntent(), consumedAtMillis = System.currentTimeMillis()))
        assertTrue(alarms().isEmpty())
    }

    /** V-18: the awareness date is the occurrence's date where it was armed, not where it was tapped. */
    @Test fun awarenessDateIsTheArmingZonesDate() {
        TimeZone.setDefault(TimeZone.getTimeZone("Pacific/Honolulu"))
        val details = reminder().copy(type = NotificationType.AWARENESS).also {
            EnrollmentSettings(context).setMobileReminderRequestCodes(setOf(it.requestCode()))
        }
        val armedAt = armReminder(context, details, receiverIntent())!!
        val honoluluDate = Instant.ofEpochMilli(armedAt).atZone(ZoneId.of("Pacific/Honolulu")).toLocalDate().toString()
        @Suppress("DEPRECATION") val posted = Gson().fromJson(
            shadowOf(alarms().single().operation).savedIntent.getStringExtra(NOTIFICATION_DETAILS),
            NotificationDetails::class.java,
        )
        TimeZone.setDefault(TimeZone.getTimeZone("UTC"))

        val url = Utils.createNotificationTargetUrl(
            posted.copy(serverUrl = "https://localhost", accessCode = "a".repeat(40)), "study-id", "participant-id",
        )

        assertEquals(honoluluDate, Uri.parse(url).getQueryParameter("date"))
    }

    /** V-1: releases through 2026.10.3 answer the GET with 500; the POST must still deliver reminders. */
    @Test fun releasedServerAnswering500FallsBackToThePost() {
        val manifest = MobileReminderConfiguration(ParticipationStatus.ENROLLED, emptyList())
        fun api(getCode: Int) = Proxy.newProxyInstance(ChronicleStudyApi::class.java.classLoader,
            arrayOf(ChronicleStudyApi::class.java)) { _, method, _ ->
            when (method.name) {
                "getMobileReminderSchedule" -> throw ChronicleCallException("GET", "url", "", getCode)
                "getMobileReminderConfiguration" -> manifest
                else -> error(method.name)
            }
        } as ChronicleStudyApi
        val studyId = UUID.randomUUID()

        assertEquals(manifest, fetchReminderSchedule(api(500), studyId, "p", "device", "key"))
        assertThrows(ChronicleCallException::class.java) { fetchReminderSchedule(api(403), studyId, "p", "device", "key") }
    }

    private val defaultZone: TimeZone = TimeZone.getDefault()

    @After fun restoreZone() = TimeZone.setDefault(defaultZone)

    private fun reminder(rule: String = "FREQ=DAILY;BYHOUR=19;BYMINUTE=0;BYSECOND=0") =
        NotificationDetails("form", NotificationType.QUESTIONNAIRE, rule, "Check-in", "Tap").also {
            TestStores.install(context, enrolled = false)
            EnrollmentSettings(context).setMobileReminderRequestCodes(setOf(it.requestCode()))
        }

    private fun receiverIntent() =
        Intent(context, SurveyNotificationsReceiver::class.java).setAction(SURVEY_NOTIFICATION_ACTION)

    private fun plant(details: NotificationDetails, at: Long, boot: Int = currentBoot()) {
        val key = details.requestCode().toString()
        context.getSharedPreferences(REMINDER_ALARM_STATE_PREFS, Context.MODE_PRIVATE).edit()
            .putLong(key, at).putString("$key.rule", details.recurrenceRule)
            .putString("$key.zone", TimeZone.getDefault().id).putInt("$key.boot", boot).commit()
    }

    private fun currentBoot() = Settings.Global.getInt(context.contentResolver, Settings.Global.BOOT_COUNT, -1)

    private fun alarms() = shadowOf(context.getSystemService(AlarmManager::class.java)).scheduledAlarms

    private fun assertArmedAt(expected: Long, armed: Long?) {
        assertEquals(expected, armed)
        assertEquals(expected, alarms().single().triggerAtMs)
    }
}
