package com.openlattice.chronicle.services.notifications

import android.app.AlarmManager
import android.content.Context
import android.content.Intent
import android.provider.Settings
import androidx.test.core.app.ApplicationProvider
import androidx.work.NetworkType
import androidx.work.WorkManager
import com.openlattice.chronicle.EnrolledWork
import com.openlattice.chronicle.WorkManagerRule
import com.openlattice.chronicle.audit.AuditStores
import com.openlattice.chronicle.collection.state.ResearchPersistenceGate
import com.openlattice.chronicle.collection.state.onPersistenceWorker
import com.openlattice.chronicle.constants.NotificationType
import com.openlattice.chronicle.preferences.EnrollmentSettings
import com.openlattice.chronicle.receivers.lifecycle.StartOnBoot
import com.openlattice.chronicle.receivers.lifecycle.SurveyNotificationsReceiver
import com.openlattice.chronicle.utils.Utils
import java.lang.management.ManagementFactory
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.util.Calendar
import java.util.TimeZone
import java.util.concurrent.TimeUnit
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowAlarmManager

/** After a reboot the reminder sync must wait for network, not fail into 15 minutes of backoff. */
@RunWith(RobolectricTestRunner::class)
class ReminderWorkConstraintTest {
    @get:Rule val workManagerRule = WorkManagerRule()
    private val context: Context = ApplicationProvider.getApplicationContext()

    @Test fun offlineBootRestoresAnAuthorizedAlarmWithoutFetchingTheManifest() {
        // The process start's own initialization (ChronicleApplication) also restores alarms; let it finish first.
        drainAuthorizationWorker()
        onPersistenceWorker {
            AuditStores.install(context, enrolled = true)
            ResearchPersistenceGate.initialize(context)
            val details = NotificationDetails("form", NotificationType.AWARENESS,
                "FREQ=DAILY;BYHOUR=19;BYMINUTE=0;BYSECOND=0", "Check-in", "Tap")
            EnrollmentSettings(context).setMobileReminderRequestCodes(setOf(details.requestCode()))
            val trigger = armReminder(context, details, receiverIntent())!!
            val manager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
            val pending = android.app.PendingIntent.getBroadcast(context, details.requestCode(), receiverIntent(),
                Utils.getPendingIntentMutabilityFlag(android.app.PendingIntent.FLAG_NO_CREATE))
            manager.cancel(requireNotNull(pending))
            assertTrue("armed $trigger, left " + alarms().joinToString { "${it.triggerAtMs}/${it.windowLengthMs}" },
                alarms().isEmpty())
            Settings.Global.putInt(context.contentResolver, Settings.Global.BOOT_COUNT, 9)
            StartOnBoot().runBootTask(context) {}
            // Boot queues the restore on the authorization worker, where every restore runs.
            drainAuthorizationWorker()
            assertEquals(trigger, alarms().single().triggerAtMs)
        }
    }

    private fun drainAuthorizationWorker() {
        val drained = java.util.concurrent.CountDownLatch(1)
        ResearchPersistenceGate.executeAsync { drained.countDown() }
        assertTrue(drained.await(30, TimeUnit.SECONDS))
    }

    @Test fun reminderWorkWaitsForNetwork() {

        EnrolledWork.rescheduleNotifications(context)

        val info = WorkManager.getInstance(context).getWorkInfosForUniqueWork("notifications").get(5, TimeUnit.SECONDS).single()
        assertEquals(NetworkType.CONNECTED, info.constraints.requiredNetworkType)
    }

    /**
     * An occurrence whose time has passed is replaced by the next one: under "remind until every day is answered"
     * the next reminder covers a lost or late one, so nothing waits for an alarm that may never come.
     */
    @Test fun anOverdueOccurrenceIsReplacedByTheNextOne() {
        val details = reminder()
        for (overdue in listOf(10_000L, TimeUnit.HOURS.toMillis(3))) {
            plant(details, System.currentTimeMillis() - overdue)
            val expected = nextLocal(ZoneId.systemDefault(), 19)
            assertArmedAt(expected, armReminder(context, details, receiverIntent()))
        }
    }

    /** The start instant matches these rules; returning it would re-fire the alarm immediately. */
    @Test fun nextOccurrenceIsStrictlyAfterTheRequestedInstant() {
        val now = System.currentTimeMillis()
        assertTrue(getNextRecurringDate("FREQ=DAILY", now)!! > now)
        val atSeven = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, 19); set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 40)
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

    /** An occurrence armed in another zone, or before a clock correction, is recomputed. */
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

    /** A refresh after the receiver advanced the occurrence keeps it, never a past trigger. */
    @Test fun refreshAfterTheReceiverAdvancedKeepsTheAdvancedOccurrence() {
        val details = reminder()
        val consumed = System.currentTimeMillis() - 1_000L
        val advanced = armReminder(context, details, receiverIntent(), consumedAtMillis = consumed)!!
        assertTrue(advanced > System.currentTimeMillis())

        assertArmedAt(advanced, armReminder(context, details, receiverIntent()))
    }

    /**
     * Interleaved: a refresh starts before the occurrence fires and waits on the lock while
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
                val threads = ManagementFactory.getThreadMXBean()
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

    /** Once a sync retired the request code, a racing delivery must not re-arm it. */
    @Test fun retiredRequestCodeIsNotReArmed() {
        val details = reminder()
        EnrollmentSettings(context).setMobileReminderRequestCodes(emptySet())

        assertNull(armReminder(context, details, receiverIntent(), consumedAtMillis = System.currentTimeMillis()))
        assertTrue(alarms().isEmpty())
    }

    /**
     * The survey reminder recurs at noon and 19:00 in the study zone. The phone's own zone neither sets the
     * occurrence nor moves it when the participant travels.
     */
    @Test fun surveyReminderRecursInTheStudyZoneWhateverThePhoneZone() {
        TimeZone.setDefault(TimeZone.getTimeZone("Asia/Tokyo"))
        val santiago = ZoneId.of("America/Santiago")
        val details = NotificationDetails("study", NotificationType.AWARENESS, "FREQ=DAILY;BYHOUR=12,19;BYMINUTE=0;BYSECOND=0",
            "Daily survey", "Tap", timeZone = santiago.id, unansweredDays = true).also {
            AuditStores.install(context, enrolled = false)
            EnrollmentSettings(context).setMobileReminderRequestCodes(setOf(it.requestCode()))
        }
        val expected = nextLocal(santiago, 12, 19)

        assertArmedAt(expected, armReminder(context, details, receiverIntent()))
        TimeZone.setDefault(TimeZone.getTimeZone("America/Los_Angeles"))
        assertArmedAt(expected, armReminder(context, details, receiverIntent()))
    }

    private val santiago: ZoneId = ZoneId.of("America/Santiago")
    private val survey = "FREQ=DAILY;BYHOUR=12,19;BYMINUTE=0;BYSECOND=0"

    /** The first 12:00 or 19:00 wall time in [zone] strictly after [after], from java.time alone. */
    private fun oracle(after: Instant, zone: ZoneId = santiago): Instant {
        val day = after.atZone(zone).toLocalDate()
        return (-1L..2L).flatMap { offset -> listOf(12, 19).map { day.plusDays(offset).atTime(it, 0).atZone(zone).toInstant() } }
            .filter { it > after }.minOf { it }
    }

    /**
     * Instants around both 2026 Santiago transitions (April: midnight repeats an hour; September: midnight is skipped):
     * local midnight, exactly 12:00 and 19:00, and one millisecond either side, two days before to two days after.
     */
    private fun transitionInstants(): List<Instant> = listOf(LocalDate.of(2026, 4, 4), LocalDate.of(2026, 9, 5)).flatMap { change ->
        (-2L..2L).flatMap { offset ->
            val day = change.plusDays(offset)
            listOf(LocalTime.MIDNIGHT, LocalTime.of(23, 30), LocalTime.NOON, LocalTime.of(19, 0)).flatMap { time ->
                val at = day.atTime(time).atZone(santiago).toInstant()
                listOf(at.minusMillis(1), at, at.plusMillis(1))
            }
        }
    }

    /** Every transition-adjacent instant, in any phone zone and with the phone clock 3 h off. */
    @Test fun surveyOccurrenceIsTheNextStudyZoneNoonOrSevenAcrossSantiagoTransitions() {
        val details = NotificationDetails("study", NotificationType.AWARENESS, survey, "Daily survey", "Tap",
            timeZone = santiago.id, unansweredDays = true).also {
            AuditStores.install(context, enrolled = false)
            EnrollmentSettings(context).setMobileReminderRequestCodes(setOf(it.requestCode()))
        }
        try {
            for (phone in listOf("America/Santiago", "Asia/Tokyo", "UTC")) {
                TimeZone.setDefault(TimeZone.getTimeZone(phone))
                val instants = transitionInstants().flatMap { base -> listOf(0L, -3L, 3L).map {
                    base.plus(java.time.Duration.ofHours(it))
                } }
                for (now in instants) {
                    reminderClock = { now.toEpochMilli() }
                    val expected = oracle(now).toEpochMilli()
                    assertEquals("$phone $now", expected,
                        getNextRecurringDate(survey, now.toEpochMilli(), TimeZone.getTimeZone(santiago)))
                    assertEquals("$phone $now armed", expected, armReminder(context, details, receiverIntent()))
                }
            }
        } finally { reminderClock = System::currentTimeMillis }
    }

    /**
     * The receiver chain over the September and April transitions: each fired occurrence arms the next one, so the
     * participant gets 12:00 and 19:00 every study day, each once, never shifted by an hour.
     */
    @Test fun firedOccurrencesChainThroughBothTransitionsWithoutSkipOrRepeat() {
        val details = NotificationDetails("study", NotificationType.AWARENESS, survey, "Daily survey", "Tap",
            timeZone = santiago.id, unansweredDays = true).also {
            AuditStores.install(context, enrolled = false)
            EnrollmentSettings(context).setMobileReminderRequestCodes(setOf(it.requestCode()))
        }
        TimeZone.setDefault(TimeZone.getTimeZone("Asia/Tokyo"))
        try {
            for (first in listOf(LocalDate.of(2026, 4, 1), LocalDate.of(2026, 9, 2))) {
                val start = first.atTime(8, 0).atZone(santiago).toInstant()
                reminderClock = { start.toEpochMilli() }
                var armed = checkNotNull(armReminder(context, details, receiverIntent()))
                val fired = mutableListOf<Long>()
                repeat(12) {
                    fired += armed
                    val firedAt = armed
                    reminderClock = { firedAt + 2_000L } // the alarm is delivered two seconds late
                    armed = checkNotNull(armReminder(context, details, receiverIntent(), consumedAtMillis = firedAt))
                }
                val expected = (0L until 6L).flatMap { day -> listOf(12, 19).map {
                    first.plusDays(day).atTime(it, 0).atZone(santiago).toInstant().toEpochMilli() } }
                assertEquals("chain from $first", expected, fired)
            }
        } finally { reminderClock = System::currentTimeMillis }
    }

    /** Below Android 12 exact alarms need no permission, so the reminder is armed exactly. */
    @Config(sdk = [28])
    @Test fun androidNineArmsTheReminderExactly() {
        val details = reminder()
        val armedAt = checkNotNull(armReminder(context, details, receiverIntent()))

        assertEquals(armedAt, alarms().single().triggerAtMs)
        assertEquals(ShadowAlarmManager.WINDOW_EXACT, alarms().single().windowLengthMs)
    }

    private val defaultZone: TimeZone = TimeZone.getDefault()

    @After fun restoreZone() = TimeZone.setDefault(defaultZone)

    private fun reminder(rule: String = "FREQ=DAILY;BYHOUR=19;BYMINUTE=0;BYSECOND=0") =
        NotificationDetails("form", NotificationType.QUESTIONNAIRE, rule, "Check-in", "Tap").also {
            AuditStores.install(context, enrolled = false)
            EnrollmentSettings(context).setMobileReminderRequestCodes(setOf(it.requestCode()))
        }

    private fun receiverIntent() =
        Intent(context, SurveyNotificationsReceiver::class.java).setAction(SURVEY_NOTIFICATION_ACTION)

    private fun plant(details: NotificationDetails, at: Long) {
        val key = details.requestCode().toString()
        context.getSharedPreferences(REMINDER_ALARM_STATE_PREFS, Context.MODE_PRIVATE).edit()
            .putLong(key, at).putString("$key.rule", details.recurrenceRule)
            .putString("$key.zone", TimeZone.getDefault().id).commit()
    }

    /** The first of [hours] (on the hour, in [zone]) after now: an oracle independent of the RRULE iterator. */
    private fun nextLocal(zone: ZoneId, vararg hours: Int): Long {
        val now = Instant.now()
        val today = now.atZone(zone).toLocalDate()
        return listOf(today, today.plusDays(1)).flatMap { day -> hours.map { day.atTime(it, 0).atZone(zone).toInstant() } }
            .filter { it > now }.minOf { it }.toEpochMilli()
    }

    private fun alarms() = reminderAlarms(context)

    private fun assertArmedAt(expected: Long, armed: Long?) {
        assertEquals(expected, armed)
        assertEquals(expected, alarms().single().triggerAtMs)
    }
}
