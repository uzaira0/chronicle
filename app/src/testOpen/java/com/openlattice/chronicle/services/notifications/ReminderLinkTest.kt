package com.openlattice.chronicle.services.notifications

import android.content.Context
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import com.openlattice.chronicle.EnrollmentTestStores
import com.openlattice.chronicle.api.ChronicleStudyApi
import com.openlattice.chronicle.audit.AuditStores
import com.openlattice.chronicle.constants.NotificationType
import com.openlattice.chronicle.data.ParticipationStatus
import com.openlattice.chronicle.participantaccess.CreateParticipantFormAccessCodeRequest
import com.openlattice.chronicle.participantaccess.MobileReminderConfiguration
import com.openlattice.chronicle.participantaccess.MobileReminderForm
import com.openlattice.chronicle.participantaccess.ParticipantFormAccessCodeResponse
import com.openlattice.chronicle.participantaccess.ParticipantFormKind
import com.openlattice.chronicle.serialization.ChronicleCallException
import com.openlattice.chronicle.storage.ChronicleDb
import com.openlattice.chronicle.utils.Utils
import java.lang.reflect.Proxy
import java.time.OffsetDateTime
import java.util.UUID
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** A reminder tap must open the form with the code the server issues now, not the posted one. */
@RunWith(RobolectricTestRunner::class)
class ReminderLinkTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    @After fun tearDown() { EnrollmentTestStores.api = null }

    /** The tap mints only its own form; the manifest would revoke codes other taps opened. */
    @Test fun tapUsesFreshCodeNotPostedOne() {
        AuditStores.install(context, enrolled = true)
        val dao = ChronicleDb.getInstance(context).uploadServerDao()
        val server = dao.getConfiguredServer()!!.copy(apiKey = "device-key")
        dao.update(server)
        for (kind in listOf(ParticipantFormKind.QUESTIONNAIRE, ParticipantFormKind.APP_USAGE)) {
            val resource = if (kind == ParticipantFormKind.QUESTIONNAIRE) UUID.randomUUID() else null
            val fresh = "f".repeat(40)
            EnrollmentTestStores.api = Proxy.newProxyInstance(ChronicleStudyApi::class.java.classLoader,
                arrayOf(ChronicleStudyApi::class.java)) { _, method, args ->
                when (method.name) {
                    "getMobileReminderSchedule" -> MobileReminderConfiguration(ParticipationStatus.ENROLLED, listOf(
                        MobileReminderForm(kind, resource, "Check-in", "FREQ=DAILY"),
                    ))
                    "createParticipantFormAccessCode" -> {
                        val request = args!!.last() as CreateParticipantFormAccessCodeRequest
                        check(request.formKind == kind && request.resourceId == resource)
                        ParticipantFormAccessCodeResponse(fresh, OffsetDateTime.now().plusDays(7), kind, resource)
                    }
                    else -> error("unexpected ${method.name}")
                }
            } as ChronicleStudyApi
            val type = if (kind == ParticipantFormKind.QUESTIONNAIRE) NotificationType.QUESTIONNAIRE else NotificationType.AWARENESS
            val posted = NotificationDetails(resource?.toString() ?: server.studyId, type, "FREQ=DAILY",
                "Check-in", "Tap", serverUrl = server.url, accessCode = "s".repeat(40),
                scheduledAtMillis = System.currentTimeMillis())

            val url = Uri.parse(freshReminderUrl(context, posted))

            assertTrue(url.toString(), url.toString().endsWith("#accessCode=$fresh"))
            assertEquals(server.studyId, url.getQueryParameter("studyId"))
            assertEquals(server.participantId, url.getQueryParameter("participantId"))
            if (resource != null) assertEquals(resource.toString(), url.getQueryParameter("questionnaireId"))
            else assertNull(url.getQueryParameter("date"))
        }
    }

    /** The survey page opens on the server's oldest open day: the phone's clock and zone never pick it. */
    @Test fun awarenessReminderUrlNamesNoDay() {
        val reminder = NotificationDetails(
            id = "study",
            type = NotificationType.AWARENESS,
            recurrenceRule = "FREQ=DAILY",
            title = "Survey",
            message = "Tap",
            serverUrl = "https://localhost",
            accessCode = "a".repeat(40),
            scheduledAtMillis = 1_750_000_000_000L,
            timeZone = "America/Santiago",
        )

        val url = Uri.parse(Utils.createNotificationTargetUrl(reminder, "study-id", "participant-id"))

        assertNull(url.getQueryParameter("date"))
        assertEquals("study-id", url.getQueryParameter("studyId"))
    }

    /** A failed schedule read must not mint a form or fall back to a different endpoint. */
    @Test fun failedScheduleDoesNotMintTheTappedForm() {
        AuditStores.install(context, enrolled = true)
        val server = ChronicleDb.getInstance(context).uploadServerDao().getConfiguredServer()!!
            .copy(apiKey = "device-key")
        ChronicleDb.getInstance(context).uploadServerDao().update(server)
        val questionnaire = UUID.randomUUID()
        val fresh = "f".repeat(40)
        EnrollmentTestStores.api = Proxy.newProxyInstance(ChronicleStudyApi::class.java.classLoader,
            arrayOf(ChronicleStudyApi::class.java)) { _, method, args ->
            when (method.name) {
                "getMobileReminderSchedule" -> throw ChronicleCallException("GET", "url", "", 500)
                "createParticipantFormAccessCode" -> {
                    val request = args!!.last() as CreateParticipantFormAccessCodeRequest
                    check(request.formKind == ParticipantFormKind.QUESTIONNAIRE && request.resourceId == questionnaire)
                    ParticipantFormAccessCodeResponse(fresh, OffsetDateTime.now().plusDays(7), request.formKind, request.resourceId)
                }
                else -> error("unexpected ${method.name}")
            }
        } as ChronicleStudyApi
        val posted = NotificationDetails(questionnaire.toString(), NotificationType.QUESTIONNAIRE, "FREQ=DAILY",
            "Check-in", "Tap", serverUrl = server.url, accessCode = "s".repeat(40))

        assertThrows(ChronicleCallException::class.java) { freshReminderUrl(context, posted) }
    }

    /** A current schedule that no longer includes the tapped form rejects the stale tap. */
    @Test fun removedReminderFormIsClassifiedAsUnavailable() {
        AuditStores.install(context, enrolled = true)
        val server = ChronicleDb.getInstance(context).uploadServerDao().getConfiguredServer()!!
            .copy(apiKey = "device-key")
        ChronicleDb.getInstance(context).uploadServerDao().update(server)
        EnrollmentTestStores.api = Proxy.newProxyInstance(ChronicleStudyApi::class.java.classLoader,
            arrayOf(ChronicleStudyApi::class.java)) { _, method, _ ->
            when (method.name) {
                "getMobileReminderSchedule" -> MobileReminderConfiguration(ParticipationStatus.ENROLLED, emptyList())
                else -> error("unexpected ${method.name}")
            }
        } as ChronicleStudyApi
        val posted = NotificationDetails(UUID.randomUUID().toString(), NotificationType.QUESTIONNAIRE, "FREQ=DAILY",
            "Removed form", "Tap", serverUrl = server.url, accessCode = "s".repeat(40))

        assertThrows(ReminderFormUnavailableException::class.java) { freshReminderUrl(context, posted) }
    }
}
