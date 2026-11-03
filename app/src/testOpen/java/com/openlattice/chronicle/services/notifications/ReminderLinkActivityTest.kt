package com.openlattice.chronicle.services.notifications

import android.app.NotificationManager
import android.app.Application
import android.content.Context
import android.content.Intent
import android.os.Looper
import android.os.Bundle
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.test.core.app.ApplicationProvider
import com.google.gson.Gson
import com.openlattice.chronicle.EnrollmentTestStores
import com.openlattice.chronicle.R
import com.openlattice.chronicle.api.ChronicleStudyApi
import com.openlattice.chronicle.audit.AuditStores
import com.openlattice.chronicle.collection.CollectionModuleId
import com.openlattice.chronicle.collection.state.ResearchPersistenceGate
import com.openlattice.chronicle.collection.state.onPersistenceWorker
import com.openlattice.chronicle.services.withdrawal.WithdrawalStateStore
import com.openlattice.chronicle.constants.NotificationType
import com.openlattice.chronicle.data.ParticipationStatus
import com.openlattice.chronicle.participantaccess.CreateParticipantFormAccessCodeRequest
import com.openlattice.chronicle.participantaccess.MobileReminderConfiguration
import com.openlattice.chronicle.participantaccess.MobileReminderForm
import com.openlattice.chronicle.participantaccess.ParticipantFormAccessCodeResponse
import com.openlattice.chronicle.participantaccess.ParticipantFormKind
import com.openlattice.chronicle.storage.ChronicleDb
import com.openlattice.chronicle.utils.Utils
import java.io.IOException
import java.lang.reflect.Proxy
import java.net.InetAddress
import java.net.ServerSocket
import java.time.OffsetDateTime
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.FutureTask
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.Job
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.shadows.ShadowToast

/** A reminder tap opens its form only for a participant still looking at it, under the same enrollment. */
@RunWith(RobolectricTestRunner::class)
class ReminderLinkActivityTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val mints = AtomicInteger()
    private val calls = AtomicInteger()

    @After fun tearDown() { EnrollmentTestStores.api = null }

    @Test fun reminderTapAcknowledgesOpeningBeforeTheNetworkReturns() {
        val entered = CountDownLatch(1)
        val released = CountDownLatch(1)
        val controller = Robolectric.buildActivity(ReminderLinkActivity::class.java,
            enrolledTap { entered.countDown(); check(released.await(5, TimeUnit.SECONDS)) }).setup()
        try {
            assertTrue(entered.await(5, TimeUnit.SECONDS))
            assertFalse(controller.get().isFinishing)
            assertEquals(controller.get().getString(R.string.dashboard_loading), ShadowToast.getTextOfLatestToast())
            assertNull(shadowOf(controller.get()).nextStartedActivity)
        } finally {
            released.countDown()
            awaitFinished(controller.get())
            controller.pause().stop().destroy()
        }
    }

    @Test fun rotatingDuringMintOpensTheSurveyOnceFromTheReplacementActivity() {
        val entered = CountDownLatch(1)
        val released = CountDownLatch(1)
        val tap = enrolledTap { entered.countDown(); check(released.await(5, TimeUnit.SECONDS)) }
        val controller = Robolectric.buildActivity(ReminderLinkActivity::class.java, tap).setup()
        try {
            assertTrue(entered.await(5, TimeUnit.SECONDS))
            controller.recreate()
            val replacement = controller.get()
            assertFalse("The recreated screen must await the in-flight request", replacement.isFinishing)
            released.countDown()
            awaitFinished(replacement)
            assertEquals(1, mints.get())
            // Launches share Robolectric's instrumentation queue across Activity instances.
            assertEquals(Intent.ACTION_VIEW, shadowOf(replacement).nextStartedActivity?.action)
            assertNull(shadowOf(replacement).nextStartedActivity)
            assertTrue(notifications().isEmpty())
        } finally { released.countDown(); controller.pause().stop().destroy() }
    }

    @Test fun processDeathDuringMintRestartsTheFreshCodeRequest() {
        val entered = CountDownLatch(1)
        val released = CountDownLatch(1)
        val tap = enrolledTap { entered.countDown(); check(released.await(5, TimeUnit.SECONDS)) }
        val original = Robolectric.buildActivity(ReminderLinkActivity::class.java, tap).setup()
        assertTrue(entered.await(5, TimeUnit.SECONDS))
        val originalRequest = ViewModelProvider(original.get())[ReminderLinkActivity.LinkRequest::class.java]
            .viewModelScope.coroutineContext[Job]!!
        val saved = Bundle()
        original.pause().saveInstanceState(saved).stop().destroy()
        // Process death ends the old request with its process; this JVM cannot kill that thread, so let it end.
        released.countDown()
        val deadline = System.currentTimeMillis() + 8_000
        while (!originalRequest.isCompleted && System.currentTimeMillis() < deadline) {
            shadowOf(Looper.getMainLooper()).idle()
            Thread.sleep(25)
        }
        assertTrue(originalRequest.isCompleted)
        val replacement = Robolectric.buildActivity(ReminderLinkActivity::class.java, tap)
            .create(saved).start().restoreInstanceState(saved).resume()
        try {
            awaitFinished(replacement.get())
            assertEquals(2, mints.get())
            assertEquals(Intent.ACTION_VIEW, shadowOf(replacement.get()).nextStartedActivity?.action)
            assertNull(shadowOf(replacement.get()).nextStartedActivity)
        } finally { released.countDown(); replacement.pause().stop().destroy() }
    }

    @Test fun rotatedRequestFailureIsVisibleAndKeepsTheReminder() {
        val entered = CountDownLatch(1)
        val released = CountDownLatch(1)
        val tap = enrolledTap {
            entered.countDown()
            check(released.await(5, TimeUnit.SECONDS))
            throw IOException("offline")
        }
        val controller = Robolectric.buildActivity(ReminderLinkActivity::class.java, tap).setup()
        try {
            assertTrue(entered.await(5, TimeUnit.SECONDS))
            controller.recreate()
            assertFalse(controller.get().isFinishing)
            released.countDown()
            awaitFinished(controller.get())
            assertEquals(context.getString(R.string.reminder_link_unavailable), ShadowToast.getTextOfLatestToast())
            assertNull(shadowOf(controller.get()).nextStartedActivity)
            assertEquals(1, notifications().size)
        } finally { released.countDown(); controller.pause().stop().destroy() }
    }

    @Test fun retiredEnrollmentTapClearsTrayAndShowsUnavailableWithoutMinting() {
        val tap = enrolledTap().putExtra(SURVEY_ENROLLMENT_SCOPE, "retired-enrollment:0")
        val activity = Robolectric.buildActivity(ReminderLinkActivity::class.java, tap).create().resume().get()
        awaitFinished(activity)
        assertEquals(0, calls.get())
        assertTrue(notifications().isEmpty())
        assertEquals(context.getString(R.string.reminder_form_unavailable),
            ShadowToast.getTextOfLatestToast())
        assertNull(shadowOf(activity).nextStartedActivity)
    }

    /**
     * The study turned the daily survey off after this reminder was posted: the tap mints no code, the stale reminder
     * leaves the tray, and the participant is told the form is no longer available instead of "try again".
     */
    @Test fun aSurveyTheServerNoLongerListsClearsTheReminderAndSaysItIsUnavailable() {
        val activity = Robolectric.buildActivity(ReminderLinkActivity::class.java, enrolledTap(forms = emptyList()))
            .create().resume().get()
        awaitFinished(activity)

        assertEquals(0, mints.get())
        assertNull(shadowOf(activity).nextStartedActivity)
        assertTrue(notifications().isEmpty())
        assertEquals(context.getString(R.string.reminder_form_unavailable), ShadowToast.getTextOfLatestToast())
    }

    @Test fun missingBrowserKeepsReminderAndExplainsHowToOpenIt() {
        val application = shadowOf(ApplicationProvider.getApplicationContext<Application>())
        application.checkActivities(true)
        val controller = Robolectric.buildActivity(ReminderLinkActivity::class.java, enrolledTap()).create().resume()
        try {
            awaitFinished(controller.get())
            assertEquals(1, mints.get())
            assertEquals(1, notifications().size)
            assertEquals(context.getString(R.string.reminder_browser_unavailable),
                ShadowToast.getTextOfLatestToast())
        } finally {
            application.checkActivities(false)
            controller.pause().destroy()
        }
    }

    /** A newer code revokes the older one, so a double tap must not mint twice and strand the first tab. */
    @Test fun overlappingTapsOnOneReminderMintOnceAndOpenOnce() {
        val entered = CountDownLatch(1)
        val released = CountDownLatch(1)
        val tap = enrolledTap { entered.countDown(); check(released.await(5, TimeUnit.SECONDS)) }

        val first = Robolectric.buildActivity(ReminderLinkActivity::class.java, tap).create().resume().get()
        assertTrue(entered.await(5, TimeUnit.SECONDS))
        val second = Robolectric.buildActivity(ReminderLinkActivity::class.java, tap).create().resume().get()
        released.countDown()
        awaitFinished(first)
        awaitFinished(second)

        assertEquals(1, mints.get())
        assertEquals(Intent.ACTION_VIEW, shadowOf(first).nextStartedActivity?.action)
        assertNull(shadowOf(second).nextStartedActivity)
        assertTrue(notifications().isEmpty())

        // The finished request releases the reminder: a later tap gets its own fresh code.
        val later = Robolectric.buildActivity(ReminderLinkActivity::class.java, tap).create().resume().get()
        awaitFinished(later)
        assertEquals(2, mints.get())
        assertEquals(Intent.ACTION_VIEW, shadowOf(later).nextStartedActivity?.action)
    }

    @Test fun resultWaitsForTheActivityToResume() {
        val entered = CountDownLatch(1)
        val controller = Robolectric.buildActivity(ReminderLinkActivity::class.java,
            enrolledTap { entered.countDown() }).create().start()
        try {
            assertTrue(entered.await(5, TimeUnit.SECONDS))
            shadowOf(Looper.getMainLooper()).idle()
            assertFalse(controller.get().isFinishing)
            assertNull(shadowOf(controller.get()).nextStartedActivity)
            assertEquals(1, notifications().size)
            controller.resume()
            awaitFinished(controller.get())
            assertEquals(Intent.ACTION_VIEW, shadowOf(controller.get()).nextStartedActivity?.action)
        } finally { controller.pause().stop().destroy() }
    }

    /** Withdrawal after minting but before the result resumes never opens the form. */
    @Test fun withdrawalBeforeTapResultResumesDoesNotOpenTheForm() {
        val minted = CountDownLatch(1)
        val tap = enrolledTap { minted.countDown() }
        val controller = Robolectric.buildActivity(ReminderLinkActivity::class.java, tap).create().start()
        try {
            assertTrue(minted.await(5, TimeUnit.SECONDS))
            onPersistenceWorker {
                ResearchPersistenceGate.stop { WithdrawalStateStore(context).beginWithdrawal() }
            }
            controller.resume()
            awaitFinished(controller.get())
            assertEquals(1, mints.get())
            assertNull(shadowOf(controller.get()).nextStartedActivity)
        } finally { controller.pause().stop().destroy() }
    }

    /** The enrollment is not published yet when the tap's wait ends; the reminder stays for a retry. */
    @Test fun unsettledAuthorizationKeepsTheReminderForARetry() {
        val tap = enrolledTap()
        val released = CountDownLatch(1)
        EnrollmentTestStores.leaveEnrollmentUnpublished(context)
        ResearchPersistenceGate.executeAsync { released.await(30, TimeUnit.SECONDS) }
        try {
            val activity = Robolectric.buildActivity(ReminderLinkActivity::class.java, tap).create().resume().get()
            awaitFinished(activity)
            assertEquals(0, calls.get())
            assertNull(shadowOf(activity).nextStartedActivity)
            assertEquals(1, notifications().size)
            assertEquals(context.getString(R.string.reminder_link_unavailable), ShadowToast.getTextOfLatestToast())
        } finally { released.countDown() }
    }

    /** A reminder admitted under another enrollment scope makes no server call and opens nothing. */
    @Test fun tapFromAnotherEnrollmentScopeNeverReachesTheServer() {
        val tap = enrolledTap().putExtra(SURVEY_ENROLLMENT_SCOPE, "retired-enrollment:0")
        val activity = Robolectric.buildActivity(ReminderLinkActivity::class.java, tap).create().resume().get()
        awaitFinished(activity)

        assertEquals(0, calls.get())
        assertNull(shadowOf(activity).nextStartedActivity)
        assertTrue(notifications().isEmpty())
    }

    /** The mint runs under the captured owner's lease, so a withdrawal cannot overtake it. */
    @Test fun mintRunsUnderTheEnrollmentLease() {
        val leased = AtomicBoolean()
        val tap = enrolledTap { leased.set(!ResearchPersistenceGate.canStopOnCurrentThread()) }
        val activity = Robolectric.buildActivity(ReminderLinkActivity::class.java, tap).create().resume().get()
        awaitFinished(activity)

        assertEquals(1, mints.get())
        assertTrue(leased.get())
    }

    /** A server trickling its answer is cut off at the tap's deadline, releasing the lease a withdrawal waits on. */
    @Test fun stalledServerIsCutOffAndReleasesTheLease() {
        val stalled = ServerSocket(0, 50, InetAddress.getLoopbackAddress())
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
            AuditStores.install(context, enrolled = true)
            val dao = ChronicleDb.getInstance(context).uploadServerDao()
            val server = dao.getConfiguredServer()!!.copy(apiKey = "device-key",
                url = "http://127.0.0.1:${stalled.localPort}")
            dao.update(server)
            EnrollmentTestStores.api = Utils.createRetrofitAdapter(server.url)
                .create(ChronicleStudyApi::class.java)
            ResearchPersistenceGate.resetAfterLocalStoreRecovery()
            runOffMain { ResearchPersistenceGate.initialize(context) }
            val owner = checkNotNull(ResearchPersistenceGate.captureOwner(context))
            val details = NotificationDetails(UUID.randomUUID().toString(), NotificationType.QUESTIONNAIRE, "FREQ=DAILY",
                "Check-in", "Tap")
            val tap = FutureTask { runCatching { freshReminderUrlFor(context, owner, details, 1_000) } }
            Thread(tap).start()
            assertTrue(connected.await(10, TimeUnit.SECONDS))
            val writer = FutureTask { ResearchPersistenceGate.enrollmentUpdate(context) {} }
            Thread(writer).start()

            // The cancelled socket read surfaces through the API proxy as an undeclared IOException.
            val failure = tap.get(5, TimeUnit.SECONDS).exceptionOrNull()
            assertTrue(failure.toString(), generateSequence(failure) { it.cause }.any { it is IOException })
            writer.get(5, TimeUnit.SECONDS)
        } finally {
            stalled.close()
        }
    }

    private fun enrolledTap(
        forms: List<MobileReminderForm> = listOf(MobileReminderForm(ParticipantFormKind.APP_USAGE, null, "Check-in", "FREQ=DAILY")),
        onMint: () -> Unit = {},
    ): Intent {
        AuditStores.install(context, enrolled = true)
        val dao = ChronicleDb.getInstance(context).uploadServerDao()
        val server = dao.getConfiguredServer()!!.copy(apiKey = "device-key")
        dao.update(server)
        ResearchPersistenceGate.resetAfterLocalStoreRecovery()
        runOffMain { ResearchPersistenceGate.initialize(context) }
        check(ResearchPersistenceGate.captureOwner(context) != null)
        EnrollmentTestStores.api = Proxy.newProxyInstance(ChronicleStudyApi::class.java.classLoader,
            arrayOf(ChronicleStudyApi::class.java)) { _, method, args ->
            calls.incrementAndGet()
            when (method.name) {
                "getMobileReminderSchedule" -> MobileReminderConfiguration(ParticipationStatus.ENROLLED, forms)
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
        val details = NotificationDetails(server.studyId, NotificationType.AWARENESS, "FREQ=DAILY",
            "Check-in", "Tap")
        NotificationManagerCompat.from(context).notify(SURVEY_NOTIFICATION_TAG, details.requestCode(),
            NotificationCompat.Builder(context, CHANNEL_ID).setSmallIcon(R.drawable.ic_stat_notification).build())
        val scope = ResearchPersistenceGate.observationScope(context, CollectionModuleId.QUESTIONNAIRE)!!.key
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
        val task = FutureTask(action)
        Thread(task).start()
        return task.get(30, TimeUnit.SECONDS)
    }
}
