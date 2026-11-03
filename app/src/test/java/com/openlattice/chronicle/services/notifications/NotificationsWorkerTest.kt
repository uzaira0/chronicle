package com.openlattice.chronicle.services.notifications

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.work.ListenableWorker
import androidx.work.WorkManager
import com.openlattice.chronicle.BuildConfig
import com.openlattice.chronicle.EnrollmentTestStores
import com.openlattice.chronicle.WorkManagerRule
import com.openlattice.chronicle.api.ChronicleStudyApi
import com.openlattice.chronicle.audit.AuditStores
import com.openlattice.chronicle.audit.auditWorkerParameters
import com.openlattice.chronicle.collection.state.BackgroundPersistenceRule
import com.openlattice.chronicle.collection.state.ResearchErasureFence
import com.openlattice.chronicle.collection.state.ResearchPersistenceGate
import com.openlattice.chronicle.data.ParticipationStatus
import com.openlattice.chronicle.participantaccess.MobileReminderConfiguration
import com.openlattice.chronicle.participantaccess.MobileReminderForm
import com.openlattice.chronicle.participantaccess.ParticipantFormKind
import com.openlattice.chronicle.preferences.EnrollmentSettings
import com.openlattice.chronicle.preferences.IDENTIFY_USER
import com.openlattice.chronicle.services.enrollment.ENROLLMENT_REFRESH_WORK_NAME
import com.openlattice.chronicle.serialization.ChronicleCallException
import com.openlattice.chronicle.services.upload.LocalOperationalIssue
import com.openlattice.chronicle.services.upload.LocalUploadDiagnosticsStore
import com.openlattice.chronicle.services.upload.LocalUploadModuleFamily
import com.openlattice.chronicle.storage.ChronicleDb
import com.openlattice.chronicle.preferences.EncryptedPrefsHelper
import java.io.IOException
import java.lang.reflect.Proxy
import java.time.Instant
import java.time.ZoneId
import java.util.TimeZone
import java.util.UUID
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class NotificationsWorkerTest {
    @get:Rule val backgroundPersistence = BackgroundPersistenceRule()
    @get:Rule val workManagerRule = WorkManagerRule()
    private val context: Context = ApplicationProvider.getApplicationContext()
    private var manifest = MobileReminderConfiguration(ParticipationStatus.ENROLLED, emptyList())
    private val defaultZone: TimeZone = TimeZone.getDefault()
    private var readFailure: Exception? = null
    private var duringRead: () -> Unit = {}
    private var reads = 0
    private var calls = 0
    private lateinit var api: ChronicleStudyApi

    @Before fun setUp() {
        AuditStores.install(context, enrolled = true)
        ResearchPersistenceGate.resetAfterLocalStoreRecovery()
        ResearchPersistenceGate.initialize(context)
        api = Proxy.newProxyInstance(ChronicleStudyApi::class.java.classLoader,
            arrayOf(ChronicleStudyApi::class.java)) { _, method, _ ->
            when (method.name) {
                "getMobileReminderSchedule" -> {
                    reads++
                    readFailure?.let { throw it }
                    duringRead()
                    manifest
                }
                else -> { calls++; error(method.name) }
            }
        } as ChronicleStudyApi
        EnrollmentTestStores.api = api
    }

    @After fun tearDown() {
        TimeZone.setDefault(defaultZone)
        EnrollmentTestStores.api = null
        ChronicleDb.getInstance(context).close()
        EnrollmentTestStores.db = null
        EnrollmentTestStores.prefs = null
        ResearchPersistenceGate.resetAfterLocalStoreRecovery()
    }

    private fun httpFailure(code: Int) = ChronicleCallException("GET", "url", "", code)

    /**
     * The survey reminder is armed at noon or 19:00 in the study zone, not the phone's, and a refetch keeps
     * the armed occurrence.
     */
    @Test fun dailyAppUsageScheduleArmsInTheStudyZoneAndRefetchKeepsTheOccurrence() {
        TimeZone.setDefault(TimeZone.getTimeZone("Asia/Tokyo"))
        val santiago = ZoneId.of("America/Santiago")
        manifest = MobileReminderConfiguration(ParticipationStatus.ENROLLED, listOf(
            MobileReminderForm(ParticipantFormKind.APP_USAGE, null, "Daily survey",
                "FREQ=DAILY;BYHOUR=12,19;BYMINUTE=0;BYSECOND=0", timeZone = santiago.id, unansweredDays = true),
        ))
        val worker = { NotificationsWorker(context, auditWorkerParameters()).doWork() }
        assertEquals(ListenableWorker.Result.success(), worker())
        if (BuildConfig.ALLOW_PARTICIPANT_FORM_REMINDERS) {
            val first = reminderAlarms(context).single().triggerAtMs
            val now = Instant.now()
            val today = now.atZone(santiago).toLocalDate()
            val expected = listOf(today, today.plusDays(1)).flatMap { day -> listOf(12, 19).map { day.atTime(it, 0).atZone(santiago).toInstant() } }
                .filter { it > now }.minOf { it }
            assertEquals(expected.toEpochMilli(), first)
            assertEquals(ListenableWorker.Result.success(), worker())
            assertEquals(first, reminderAlarms(context).single().triggerAtMs)
            assertEquals(2, reads)
        } else {
            assertEquals(0, reminderAlarms(context).size)
            assertEquals(0, reads)
        }
    }

    /**
     * The survey asks who used a shared phone. The server lists it only for a study that collects user identification,
     * and the phone arms it only while its own shared-device switch is on; turned off, the armed survey alarm is cancelled.
     */
    @Test fun surveyReminderIsArmedOnlyForASharedPhoneInAStudyThatIdentifiesUsers() {
        assumeTrue(BuildConfig.ALLOW_PARTICIPANT_FORM_REMINDERS)
        val questionnaire = MobileReminderForm(ParticipantFormKind.QUESTIONNAIRE, UUID.randomUUID(), "Check-in", "FREQ=DAILY;BYHOUR=9")
        val survey = MobileReminderForm(ParticipantFormKind.APP_USAGE, null, "Daily survey",
            "FREQ=DAILY;BYHOUR=12,19;BYMINUTE=0;BYSECOND=0", timeZone = "UTC", unansweredDays = true)
        fun sync(studyIdentifiesUsers: Boolean, shared: Boolean): Int {
            check(EncryptedPrefsHelper.getEncryptedPrefs(context).edit().putBoolean(IDENTIFY_USER, shared).commit())
            manifest = MobileReminderConfiguration(ParticipationStatus.ENROLLED,
                listOfNotNull(questionnaire, survey.takeIf { studyIdentifiesUsers }))
            assertEquals(ListenableWorker.Result.success(), NotificationsWorker(context, auditWorkerParameters()).doWork())
            return reminderAlarms(context).size
        }
        for (study in listOf(false, true)) for (shared in listOf(false, true)) {
            assertEquals("study=$study shared=$shared", if (study && shared) 2 else 1, sync(study, shared))
        }
        // Mid-study: the phone stops being shared, then is shared again.
        assertEquals(1, sync(studyIdentifiesUsers = true, shared = false))
        assertEquals(2, sync(studyIdentifiesUsers = true, shared = true))
    }

    /**
     * The manifest's status carries no capture windows, so the reminder worker never applies it (no
     * status, no settings generation change); it asks the status poll, which applies the status with its windows.
     */
    @Test fun aStatusChangeInTheReminderManifestIsLeftToTheStatusPoll() {
        assumeTrue(BuildConfig.ALLOW_PARTICIPANT_FORM_REMINDERS)
        manifest = MobileReminderConfiguration(ParticipationStatus.PAUSED, emptyList())
        val generation = ResearchErasureFence(context).settingsGeneration()
        assertEquals(ListenableWorker.Result.success(), NotificationsWorker(context, auditWorkerParameters()).doWork())
        assertEquals(ParticipationStatus.ENROLLED, EnrollmentSettings(context).getParticipationStatus())
        assertEquals(generation, ResearchErasureFence(context).settingsGeneration())
        assertEquals(1, WorkManager.getInstance(context).getWorkInfosForUniqueWork(ENROLLMENT_REFRESH_WORK_NAME).get().size)
    }

    /**
     * A schedule whose recurrence this phone cannot resolve (a server-side typo, a rule from a newer dashboard) arms
     * no alarm, and is not retried every 15 minutes as if arming had failed: the run succeeds and waits for a
     * corrected schedule.
     */
    @Test fun anUnresolvableRecurrenceArmsNothingAndDoesNotRetry() {
        assumeTrue(BuildConfig.ALLOW_PARTICIPANT_FORM_REMINDERS)
        manifest = MobileReminderConfiguration(ParticipationStatus.ENROLLED, listOf(
            MobileReminderForm(ParticipantFormKind.APP_USAGE, null, "Daily survey", "FREQ=SOMETIMES;BYHOUR=12",
                timeZone = "America/Santiago", unansweredDays = true),
        ))
        assertEquals(ListenableWorker.Result.success(), NotificationsWorker(context, auditWorkerParameters()).doWork())
        assertEquals(0, reminderAlarms(context).size)
        assertEquals(null, getNextRecurringDate("FREQ=SOMETIMES;BYHOUR=12", System.currentTimeMillis()))
    }

    /**
     * The daily survey alarm is set but its record cannot be saved (storage full): the alarm is taken back so no
     * reminder fires that the phone cannot account for, the study sees that the participant will miss reminders, and
     * the retry after space is freed arms it and closes that episode.
     */
    @Test fun aSurveyReminderThatCannotBeSavedIsDisarmedReportedAndArmedOnTheRetry() {
        assumeTrue(BuildConfig.ALLOW_PARTICIPANT_FORM_REMINDERS)
        manifest = MobileReminderConfiguration(ParticipationStatus.ENROLLED, listOf(
            MobileReminderForm(ParticipantFormKind.APP_USAGE, null, "Daily survey",
                "FREQ=DAILY;BYHOUR=12,19;BYMINUTE=0;BYSECOND=0", timeZone = "America/Santiago", unansweredDays = true),
        ))
        val sql = ChronicleDb.getInstance(context).openHelper.writableDatabase
        sql.execSQL("CREATE TRIGGER disk_full BEFORE INSERT ON lifecycle_state BEGIN SELECT RAISE(ABORT, 'disk full'); END")
        val failures = context.getSharedPreferences("reminder_arm_failures", Context.MODE_PRIVATE)

        assertEquals(ListenableWorker.Result.retry(), NotificationsWorker(context, auditWorkerParameters()).doWork())
        assertEquals(0, reminderAlarms(context).size)
        assertEquals(emptySet<Int>(), EnrollmentSettings(context).getMobileReminderRequestCodes())
        assertTrue(LocalUploadDiagnosticsStore.of(context).recent().any {
            it.moduleFamily == LocalUploadModuleFamily.APP_RUNTIME.name &&
                it.issue == LocalOperationalIssue.COLLECTION_ACCESS_MISSING.name
        })
        assertEquals(1, failures.all.size)

        sql.execSQL("DROP TRIGGER disk_full")
        assertEquals(ListenableWorker.Result.success(), NotificationsWorker(context, auditWorkerParameters()).doWork())
        assertEquals(1, reminderAlarms(context).size)
        assertEquals("the failure episode is closed", 0, failures.all.size)
    }

    /** The GET is the only reminder read: no status falls back to a code-minting call. */
    @Test fun refusedScheduleReadsFailWithoutAnotherCall() {
        assumeTrue(BuildConfig.ALLOW_PARTICIPANT_FORM_REMINDERS)
        for (code in listOf(404, 405, 500, 401, 403, 429)) {
            readFailure = httpFailure(code)
            val readsBefore = reads
            assertEquals("$code", ListenableWorker.Result.failure(), NotificationsWorker(context, auditWorkerParameters()).doWork())
            assertEquals("$code", readsBefore + 1, reads)
            assertEquals("$code", 0, calls)
        }
    }

    private fun assertRetriesWithoutPost(failure: Exception) {
        assumeTrue(BuildConfig.ALLOW_PARTICIPANT_FORM_REMINDERS)
        readFailure = failure
        val readsBefore = reads
        val result = NotificationsWorker(context, auditWorkerParameters()).doWork()
        assertEquals(failure.toString(), 0, calls)
        assertEquals(failure.toString(), readsBefore + 1, reads)
        assertEquals(failure.toString(), ListenableWorker.Result.retry(), result)
    }

    @Test fun transientHttpErrorsRetryWithoutMintingCodes() {
        for (code in listOf(503, 502, 504)) assertRetriesWithoutPost(httpFailure(code))
    }

    @Test fun ioFailureRetriesWithoutMintingCodes() {
        assertRetriesWithoutPost(IOException("connection interrupted"))
    }

    private val dailySurvey = MobileReminderForm(ParticipantFormKind.APP_USAGE, null, "Daily survey",
        "FREQ=DAILY;BYHOUR=12,19;BYMINUTE=0;BYSECOND=0", timeZone = "America/Santiago", unansweredDays = true)

    /**
     * Another worker hits a storage failure while the schedule is being fetched: collection is closed, so the survey
     * reminder is not armed under a retired admission, and the run retries.
     */
    @Test fun aStorageFailureElsewhereDuringTheFetchLeavesTheReminderForTheRetry() {
        assumeTrue(BuildConfig.ALLOW_PARTICIPANT_FORM_REMINDERS)
        manifest = MobileReminderConfiguration(ParticipationStatus.ENROLLED, listOf(dailySurvey))
        duringRead = { ResearchPersistenceGate.closeForPersistenceFailure() }
        assertEquals(ListenableWorker.Result.retry(), NotificationsWorker(context, auditWorkerParameters()).doWork())
        assertEquals(0, reminderAlarms(context).size)
        assertEquals(emptySet<Int>(), EnrollmentSettings(context).getMobileReminderRequestCodes())
    }

    /** The server lists a form kind Android never reminds about: the run fails and arms nothing, the daily survey included. */
    @Test fun aReminderKindAndroidDoesNotShowArmsNothing() {
        assumeTrue(BuildConfig.ALLOW_PARTICIPANT_FORM_REMINDERS)
        for (kind in listOf(ParticipantFormKind.TIME_USE_DIARY, ParticipantFormKind.PORTAL, ParticipantFormKind.ENROLLMENT)) {
            manifest = MobileReminderConfiguration(ParticipationStatus.ENROLLED,
                listOf(dailySurvey, MobileReminderForm(kind, UUID.randomUUID(), "Other", "FREQ=DAILY;BYHOUR=9")))
            assertEquals("$kind", ListenableWorker.Result.failure(), NotificationsWorker(context, auditWorkerParameters()).doWork())
            assertEquals("$kind", 0, reminderAlarms(context).size)
        }
    }
}
