package com.openlattice.chronicle.services.notifications

import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.os.Looper
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.test.core.app.ApplicationProvider
import com.google.gson.Gson
import com.openlattice.chronicle.R
import com.openlattice.chronicle.api.ChronicleStudyApi
import com.openlattice.chronicle.collection.CollectionModuleId
import com.openlattice.chronicle.collection.state.ResearchPersistenceGate
import com.openlattice.chronicle.constants.NotificationType
import com.openlattice.chronicle.data.ParticipationStatus
import com.openlattice.chronicle.layout.TestStores
import com.openlattice.chronicle.participantaccess.CreateParticipantFormAccessCodeRequest
import com.openlattice.chronicle.participantaccess.MobileReminderConfiguration
import com.openlattice.chronicle.participantaccess.MobileReminderForm
import com.openlattice.chronicle.participantaccess.ParticipantFormAccessCodeResponse
import com.openlattice.chronicle.participantaccess.ParticipantFormKind
import com.openlattice.chronicle.services.upload.UploadWorker
import com.openlattice.chronicle.storage.AUTH_MODE_API_KEY
import com.openlattice.chronicle.storage.ChronicleDb
import com.openlattice.chronicle.utils.Utils
import java.lang.reflect.Proxy
import java.time.OffsetDateTime
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf

/** A reminder tap opens its form only for a participant still looking at it, under the same enrollment. */
@RunWith(RobolectricTestRunner::class)
class ReminderLinkActivityTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private var cacheKey: String? = null
    private val mints = AtomicInteger()
    private val calls = AtomicInteger()

    @Suppress("UNCHECKED_CAST")
    private val apiCache get() = UploadWorker::class.java.getDeclaredField("studyApiCache")
        .apply { isAccessible = true }.get(null) as MutableMap<String, ChronicleStudyApi>

    @After fun tearDown() { cacheKey?.let { apiCache.remove(it) } }

    @Test fun retiredEnrollmentTapClearsTrayAndShowsUnavailableWithoutMinting() {
        val tap = enrolledTap().putExtra(SURVEY_ENROLLMENT_SCOPE, "retired-enrollment:0")
        val activity = Robolectric.buildActivity(ReminderLinkActivity::class.java, tap).create().resume().get()
        awaitFinished(activity)
        assertEquals(0, calls.get())
        assertTrue(notifications().isEmpty())
        assertEquals(context.getString(R.string.reminder_form_unavailable),
            org.robolectric.shadows.ShadowToast.getTextOfLatestToast())
        assertNull(shadowOf(activity).nextStartedActivity)
    }

    /** V-8: a second tap while the first is minting is ignored; the first opens and clears the reminder. */
    @Test fun overlappingTapsMintOnceAndTheResumedOneOpens() {
        val released = CountDownLatch(1)
        val tap = enrolledTap { check(released.await(5, TimeUnit.SECONDS)) }

        val first = Robolectric.buildActivity(ReminderLinkActivity::class.java, tap).create().resume().get()
        val second = Robolectric.buildActivity(ReminderLinkActivity::class.java, tap).create().resume().get()
        assertTrue(second.isFinishing)
        released.countDown()
        awaitFinished(first)

        assertEquals(1, mints.get())
        assertEquals(android.content.Intent.ACTION_VIEW, shadowOf(first).nextStartedActivity?.action)
        assertTrue(notifications().isEmpty())
    }

    /** V-13: Android drops a background launch silently, so a tap left meanwhile keeps its reminder. */
    @Test fun tapLeftBeforeTheLinkArrivedKeepsTheReminder() {
        val activity = Robolectric.buildActivity(ReminderLinkActivity::class.java, enrolledTap()).create().get()
        awaitFinished(activity)

        assertNull(shadowOf(activity).nextStartedActivity)
        assertEquals(1, notifications().size)
    }

    /** V-9: an enrollment withdrawn while the code was fetched never gets its form opened. */
    @Test fun tapWithdrawnMidFetchDoesNotOpenTheForm() {
        val tap = enrolledTap {
            ResearchPersistenceGate::class.java.getDeclaredField("authorization").apply {
                isAccessible = true
                set(ResearchPersistenceGate, type.getDeclaredConstructor().apply { isAccessible = true }.newInstance())
            }
        }
        val activity = Robolectric.buildActivity(ReminderLinkActivity::class.java, tap).create().resume().get()
        awaitFinished(activity)

        assertEquals(1, mints.get())
        assertNull(shadowOf(activity).nextStartedActivity)
    }

    /** V-9: a reminder admitted under another enrollment scope makes no server call and opens nothing. */
    @Test fun tapFromAnotherEnrollmentScopeNeverReachesTheServer() {
        val tap = enrolledTap().putExtra(SURVEY_ENROLLMENT_SCOPE, "retired-enrollment:0")
        val activity = Robolectric.buildActivity(ReminderLinkActivity::class.java, tap).create().resume().get()
        awaitFinished(activity)

        assertEquals(0, calls.get())
        assertNull(shadowOf(activity).nextStartedActivity)
        assertTrue(notifications().isEmpty())
    }

    /** V-9: the mint runs under the captured owner's lease, so a withdrawal cannot overtake it. */
    @Test fun mintRunsUnderTheEnrollmentLease() {
        val leased = java.util.concurrent.atomic.AtomicBoolean()
        val tap = enrolledTap { leased.set(!ResearchPersistenceGate.canStopOnCurrentThread()) }
        val activity = Robolectric.buildActivity(ReminderLinkActivity::class.java, tap).create().resume().get()
        awaitFinished(activity)

        assertEquals(1, mints.get())
        assertTrue(leased.get())
    }

    /** A server trickling its answer is cut off at the tap's deadline, releasing the lease a withdrawal waits on. */
    @Test fun stalledServerIsCutOffAndReleasesTheLease() {
        val stalled = java.net.ServerSocket(0, 50, java.net.InetAddress.getLoopbackAddress())
        val connected = CountDownLatch(1)
        Thread {
            runCatching {
                stalled.accept().use { socket ->
                    val input = socket.getInputStream().bufferedReader()
                    @Suppress("ControlFlowWithEmptyBody")
                    while (!input.readLine().isNullOrEmpty()) {}
                    connected.countDown()
                    val out = socket.getOutputStream()
                    out.write("HTTP/1.1 200 OK\r\nContent-Type: application/json\r\nContent-Length: 4096\r\n\r\n".toByteArray())
                    while (!stalled.isClosed) { out.write(' '.code); out.flush(); Thread.sleep(100) } // inside every read timeout
                }
            }
        }.apply { isDaemon = true }.start()
        try {
            TestStores.install(context, enrolled = true)
            val dao = ChronicleDb.getInstance(context).uploadServerDao()
            dao.update(dao.getConfiguredServer()!!.copy(authMode = AUTH_MODE_API_KEY, apiKey = "device-key",
                url = "http://127.0.0.1:${stalled.localPort}"))
            ResearchPersistenceGate.resetAfterLocalStoreRecovery()
            runOffMain { ResearchPersistenceGate.initialize(context) }
            val owner = checkNotNull(ResearchPersistenceGate.captureOwner(context))
            val details = NotificationDetails(UUID.randomUUID().toString(), NotificationType.QUESTIONNAIRE, "FREQ=DAILY",
                "Check-in", "Tap")
            val tap = java.util.concurrent.FutureTask { runCatching { freshReminderUrlFor(context, owner, details, 1_000) } }
            Thread(tap).start()
            assertTrue(connected.await(10, TimeUnit.SECONDS))
            val writer = java.util.concurrent.FutureTask { ResearchPersistenceGate.enrollmentUpdate(context) {} }
            Thread(writer).start()

            // The cancelled socket read surfaces through the API proxy as an undeclared IOException.
            val failure = tap.get(5, TimeUnit.SECONDS).exceptionOrNull()
            assertTrue(failure.toString(), generateSequence(failure) { it.cause }.any { it is java.io.IOException })
            writer.get(5, TimeUnit.SECONDS)
        } finally {
            stalled.close()
        }
    }

    private fun enrolledTap(onMint: () -> Unit = {}): Intent {
        TestStores.install(context, enrolled = true)
        val dao = ChronicleDb.getInstance(context).uploadServerDao()
        val server = dao.getConfiguredServer()!!.copy(authMode = AUTH_MODE_API_KEY, apiKey = "device-key")
        dao.update(server)
        ResearchPersistenceGate.resetAfterLocalStoreRecovery()
        runOffMain { ResearchPersistenceGate.initialize(context) }
        check(ResearchPersistenceGate.captureOwner(context) != null)
        val questionnaire = UUID.randomUUID()
        cacheKey = Utils.normalizeTrustedServerUrl(server.url) + "|" + Utils.mobileSigningSecretFingerprint(null)
        apiCache[cacheKey!!] = Proxy.newProxyInstance(ChronicleStudyApi::class.java.classLoader,
            arrayOf(ChronicleStudyApi::class.java)) { _, method, args ->
            calls.incrementAndGet()
            when (method.name) {
                "getMobileReminderSchedule" -> MobileReminderConfiguration(ParticipationStatus.ENROLLED, listOf(
                    MobileReminderForm(ParticipantFormKind.QUESTIONNAIRE, questionnaire, "Check-in", "FREQ=DAILY",
                        "", OffsetDateTime.now()),
                ))
                "createParticipantFormAccessCode" -> {
                    mints.incrementAndGet()
                    onMint()
                    val request = args!!.last() as CreateParticipantFormAccessCodeRequest
                    ParticipantFormAccessCodeResponse("f".repeat(40), OffsetDateTime.now().plusDays(7),
                        request.formKind, request.resourceId)
                }
                else -> error("unexpected ${method.name}")
            }
        } as ChronicleStudyApi
        val details = NotificationDetails(questionnaire.toString(), NotificationType.QUESTIONNAIRE, "FREQ=DAILY",
            "Check-in", "Tap")
        NotificationManagerCompat.from(context).notify(SURVEY_NOTIFICATION_TAG, details.requestCode(),
            NotificationCompat.Builder(context, CHANNEL_ID).setSmallIcon(R.drawable.ic_stat_notification).build())
        val scope = ResearchPersistenceGate.observationScope(context, CollectionModuleId.QUESTIONNAIRE)!!.first
        return Intent(context, ReminderLinkActivity::class.java).putExtra(NOTIFICATION_DETAILS, Gson().toJson(details))
            .putExtra(SURVEY_ENROLLMENT_SCOPE, scope)
    }

    private fun notifications() = shadowOf(context.getSystemService(NotificationManager::class.java)).allNotifications

    private fun awaitFinished(activity: ReminderLinkActivity) {
        val deadline = System.currentTimeMillis() + 8_000
        while (!activity.isFinishing && System.currentTimeMillis() < deadline) {
            shadowOf(Looper.getMainLooper()).idle()
            Thread.sleep(25)
        }
        assertTrue(activity.isFinishing)
    }

    private fun <T> runOffMain(action: () -> T): T {
        val task = java.util.concurrent.FutureTask(action)
        Thread(task).start()
        return task.get(30, TimeUnit.SECONDS)
    }
}
