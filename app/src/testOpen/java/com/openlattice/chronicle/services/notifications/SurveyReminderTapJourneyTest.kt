package com.openlattice.chronicle.services.notifications

import android.app.AlarmManager
import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import androidx.work.ListenableWorker
import com.openlattice.chronicle.EnrollmentTestStores
import com.openlattice.chronicle.ui.awaitDashboardUi
import com.openlattice.chronicle.ANDROID_NS
import com.openlattice.chronicle.mergedMainManifest
import org.w3c.dom.Element
import java.util.concurrent.TimeUnit
import com.openlattice.chronicle.api.ChronicleStudyApi
import com.openlattice.chronicle.audit.AuditStores
import com.openlattice.chronicle.audit.auditWorkerParameters
import com.openlattice.chronicle.collection.CollectionModuleId
import com.openlattice.chronicle.collection.CollectionModuleSetting
import com.openlattice.chronicle.collection.state.ResearchPersistenceGate
import com.openlattice.chronicle.collection.state.onPersistenceWorker
import com.openlattice.chronicle.preferences.EnrollmentSettings
import com.openlattice.chronicle.receivers.lifecycle.SurveyNotificationsReceiver
import com.openlattice.chronicle.storage.ChronicleDb
import com.openlattice.chronicle.storage.UploadServerEntity
import com.openlattice.chronicle.utils.Utils
import java.io.File
import java.time.Instant
import java.util.concurrent.atomic.AtomicInteger
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf

/**
 * Phone half: the pilot survey reminder (12:00 and 19:00 America/Santiago, questionnaires off) fires,
 * and tapping the posted notification's own PendingIntent mints a code over HTTP and opens the survey URL for this
 * participant; once the server reports every day answered, the next occurrence stays silent. With
 * CHRONICLE_ANDROID_SURVEY_DIR set, the captured requests and opened URLs are written there for the web/backend half.
 */
@RunWith(RobolectricTestRunner::class)
class SurveyReminderTapJourneyTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val alarms get() = context.getSystemService(AlarmManager::class.java)
    private val web = MockWebServer()
    private val receiver = SurveyNotificationsReceiver()
    private val mints = AtomicInteger()
    @Volatile private var unanswered = true
    private lateinit var server: UploadServerEntity

    @Before fun setUp() {
        web.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse = when {
                request.method == "GET" && request.path!!.endsWith("/reminders") -> json("""{"participationStatus":"ENROLLED",
                    "forms":[{"formKind":"APP_USAGE","title":"Encuesta diaria","recurrenceRule":"$RULE",
                    "timeZone":"America/Santiago","unansweredDays":$unanswered}]}""")
                request.method == "POST" && request.path!!.endsWith("/form-access-codes") -> json("""{"accessCode":
                    "${code(mints.incrementAndGet())}","expiresAt":"2026-11-06T12:00:00Z","formKind":"APP_USAGE"}""")
                else -> MockResponse().setResponseCode(404)
            }
        }
        web.start()
        onPersistenceWorker {
            AuditStores.install(context, enrolled = true,
                mapOf(CollectionModuleId.QUESTIONNAIRE to CollectionModuleSetting(enabled = false)))
            server = ChronicleDb.getInstance(context).uploadServerDao().getConfiguredServer()!!
            ResearchPersistenceGate.resetAfterLocalStoreRecovery()
            ResearchPersistenceGate.initialize(context)
        }
        // The enrolled server's study API, carried over real HTTP to the local server above: the enrollment's own URL
        // and disclosure stay as enrolled, so nothing here changes what authorizes the survey.
        EnrollmentTestStores.api = Utils.createRetrofitAdapter(web.url("/").toString().removeSuffix("/"))
            .create(ChronicleStudyApi::class.java)
        val manifest = mergedMainManifest()
        for ((tag, name) in listOf("receiver" to SurveyNotificationsReceiver::class.java.name,
                "activity" to ReminderLinkActivity::class.java.name)) {
            val nodes = manifest.getElementsByTagName(tag)
            val declared = (0 until nodes.length).map { nodes.item(it) as Element }
                .single { it.getAttributeNS(ANDROID_NS, "name") == name }
            assertEquals("false", declared.getAttributeNS(ANDROID_NS, "exported"))
        }
        context.registerReceiver(receiver, IntentFilter(SURVEY_NOTIFICATION_ACTION),
            Context.RECEIVER_NOT_EXPORTED)
    }

    @After fun tearDown() {
        context.unregisterReceiver(receiver)
        web.shutdown()
        onPersistenceWorker {
            reminderClock = System::currentTimeMillis
            EnrollmentTestStores.api = null
            ChronicleDb.getInstance(context).close()
            EnrollmentTestStores.db = null
            EnrollmentTestStores.prefs = null
            ResearchPersistenceGate.resetAfterLocalStoreRecovery()
        }
    }

    private fun json(body: String) = MockResponse().setHeader("Content-Type", "application/json").setBody(body)
    private fun code(n: Int) = "C".repeat(39) + n

    private fun sync(now: Instant) = onPersistenceWorker {
        reminderClock = { now.toEpochMilli() }
        assertEquals(ListenableWorker.Result.success(), NotificationsWorker(context, auditWorkerParameters()).doWork())
    }

    private fun armed(): Pair<PendingIntent, Instant> = onPersistenceWorker {
        val requestCode = EnrollmentSettings(context).getMobileReminderRequestCodes().single()
        checkNotNull(PendingIntent.getBroadcast(context, requestCode, reminderAlarmIntent(context),
            PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_IMMUTABLE)) to
            Instant.ofEpochMilli(reminderAlarms(context).single().triggerAtMs)
    }

    /** Android delivers the armed occurrence; returns the reminder it posted, if any, once its successor is armed. */
    private fun fire(): Notification? {
        val (alarm, at) = armed()
        alarms.cancel(alarm)
        reminderClock = { at.toEpochMilli() + 1_000L }
        context.sendBroadcast(shadowOf(alarm).savedIntent)
        val manager = shadowOf(context.getSystemService(NotificationManager::class.java))
        shadowOf(Looper.getMainLooper()).idle()
        shadowOf(checkNotNull(shadowOf(receiver).originalPendingResult)).future.get(10, TimeUnit.SECONDS)
        return manager.allNotifications.singleOrNull { it.channelId == CHANNEL_ID }
    }

    /** Taps the notification's own PendingIntent and returns the URL the browser was asked to open. */
    private fun tap(reminder: Notification): String {
        val controller = Robolectric.buildActivity(ReminderLinkActivity::class.java,
            shadowOf(reminder.contentIntent).savedIntent).create().resume()
        val activity = controller.get()
        try {
            awaitDashboardUi { activity.isFinishing }
            val opened = checkNotNull(shadowOf(activity).nextStartedActivity)
            assertEquals(Intent.ACTION_VIEW, opened.action)
            assertTrue("the opened reminder is cleared", shadowOf(context.getSystemService(NotificationManager::class.java))
                .allNotifications.none { it.channelId == CHANNEL_ID })
            return opened.dataString!!
        } finally { controller.pause().stop().destroy() }
    }

    @Test fun noonAndEveningRemindersOpenThisParticipantsSurveyThenStopOnceAnswered() {
        sync(Instant.parse("2026-10-30T13:00:00Z")) // 10:00 in Santiago (UTC-3)
        val urls = mutableListOf<String>()
        for ((occurrence, next) in listOf("2026-10-30T15:00:00Z" to "2026-10-30T22:00:00Z",
                "2026-10-30T22:00:00Z" to "2026-10-31T15:00:00Z")) {
            assertEquals(Instant.parse(occurrence), armed().second)
            val reminder = checkNotNull(fire()) { "no reminder at $occurrence" }
            assertEquals(Instant.parse(next), armed().second)
            assertEquals("Encuesta diaria", reminder.extras.getString(Notification.EXTRA_TITLE))
            urls += tap(reminder)
        }
        val base = "${server.url}/chronicle/survey?studyId=${server.studyId}&participantId=${server.participantId}"
        assertEquals(listOf("$base#accessCode=${code(1)}", "$base#accessCode=${code(2)}"), urls)

        val requests = generateSequence { web.takeRequest(0, java.util.concurrent.TimeUnit.SECONDS) }.toList()
        val minted = requests.filter { it.path!!.endsWith("/form-access-codes") }
        assertEquals(2, minted.size)
        minted.forEach { request ->
            assertEquals("/chronicle/v4/study/${server.studyId}/participant/${server.participantId}/form-access-codes",
                request.path)
            assertEquals(server.apiKey, request.getHeader("X-Api-Key"))
            assertEquals(server.sourceRandomDeviceId, request.getHeader("X-Chronicle-Random-Device-Id"))
            assertTrue(request.body.readUtf8().contains("\"formKind\":\"APP_USAGE\""))
        }
        System.getenv("CHRONICLE_ANDROID_SURVEY_DIR")?.let(::File)?.let { dir ->
            dir.mkdirs()
            File(dir, "opened-urls.txt").writeText(urls.joinToString("\n"))
        }

        // The participant submitted: the next sync learns every day is answered, and the next noon is silent.
        unanswered = false
        sync(Instant.parse("2026-10-31T13:00:00Z"))
        assertNull(fire())
        assertEquals(2, mints.get())
    }

    private companion object {
        const val RULE = "FREQ=DAILY;BYHOUR=12,19;BYMINUTE=0;BYSECOND=0"
    }
}
