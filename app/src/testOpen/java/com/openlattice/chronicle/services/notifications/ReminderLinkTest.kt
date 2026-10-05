package com.openlattice.chronicle.services.notifications

import android.content.Context
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import com.openlattice.chronicle.api.ChronicleStudyApi
import com.openlattice.chronicle.constants.NotificationType
import com.openlattice.chronicle.data.ParticipationStatus
import com.openlattice.chronicle.layout.TestStores
import com.openlattice.chronicle.participantaccess.CreateParticipantFormAccessCodeRequest
import com.openlattice.chronicle.participantaccess.MobileReminderConfiguration
import com.openlattice.chronicle.participantaccess.ParticipantFormAccessCodeResponse
import com.openlattice.chronicle.participantaccess.MobileReminderForm
import com.openlattice.chronicle.participantaccess.ParticipantFormKind
import com.openlattice.chronicle.serialization.ChronicleCallException
import com.openlattice.chronicle.services.upload.UploadWorker
import com.openlattice.chronicle.storage.AUTH_MODE_API_KEY
import com.openlattice.chronicle.storage.ChronicleDb
import com.openlattice.chronicle.utils.Utils
import java.lang.reflect.Proxy
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneId
import java.util.UUID
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** A reminder tap must open the form with the code the server issues now, not the posted one. */
@RunWith(RobolectricTestRunner::class)
class ReminderLinkTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private var cacheKey: String? = null

    @Suppress("UNCHECKED_CAST")
    private val apiCache get() = UploadWorker::class.java.getDeclaredField("studyApiCache")
        .apply { isAccessible = true }.get(null) as MutableMap<String, ChronicleStudyApi>

    @After fun tearDown() { cacheKey?.let { apiCache.remove(it) } }

    /** V-8/V-10: the tap mints only its own form; the manifest would revoke codes other taps opened. */
    @Test fun tapUsesFreshCodeNotPostedOne() {
        TestStores.install(context, enrolled = true)
        val dao = ChronicleDb.getInstance(context).uploadServerDao()
        val server = dao.getConfiguredServer()!!.copy(authMode = AUTH_MODE_API_KEY, apiKey = "device-key")
        dao.update(server)
        val questionnaire = UUID.randomUUID()
        val fresh = "f".repeat(40)
        cacheKey = Utils.normalizeTrustedServerUrl(server.url) + "|" + Utils.mobileSigningSecretFingerprint(null)
        apiCache[cacheKey!!] = Proxy.newProxyInstance(ChronicleStudyApi::class.java.classLoader,
            arrayOf(ChronicleStudyApi::class.java)) { _, method, args ->
            when (method.name) {
                "getMobileReminderSchedule" -> MobileReminderConfiguration(ParticipationStatus.ENROLLED, listOf(
                    MobileReminderForm(ParticipantFormKind.QUESTIONNAIRE, questionnaire, "Check-in", "FREQ=DAILY",
                        "", OffsetDateTime.now()),
                ))
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

        val url = freshReminderUrl(context, posted)

        assertTrue(url, url.endsWith("#accessCode=$fresh"))
        assertTrue(url, "questionnaireId=$questionnaire" in url)
    }

    @Test fun awarenessReminderUrlKeepsItsScheduledLocalDate() {
        val scheduledAt = 1_750_000_000_000L
        val expectedDate = Instant.ofEpochMilli(scheduledAt).atZone(ZoneId.systemDefault()).toLocalDate().toString()
        val reminder = NotificationDetails(
            id = "study",
            type = NotificationType.AWARENESS,
            recurrenceRule = "FREQ=DAILY",
            title = "Survey",
            message = "Tap",
            serverUrl = "https://localhost",
            accessCode = "a".repeat(40),
            scheduledAtMillis = scheduledAt,
        )

        val url = Utils.createNotificationTargetUrl(reminder, "study-id", "participant-id")

        assertEquals(expectedDate, Uri.parse(url).getQueryParameter("date"))
    }

    /** Fix 1: releases through 2026.10.3 answer the GET with 500 but mint single forms; never the manifest. */
    @Test fun releasedServerWithoutTheScheduleStillMintsOnlyTheTappedForm() {
        TestStores.install(context, enrolled = true)
        val server = ChronicleDb.getInstance(context).uploadServerDao().getConfiguredServer()!!
            .copy(authMode = AUTH_MODE_API_KEY, apiKey = "device-key")
        ChronicleDb.getInstance(context).uploadServerDao().update(server)
        val questionnaire = UUID.randomUUID()
        val fresh = "f".repeat(40)
        cacheKey = Utils.normalizeTrustedServerUrl(server.url) + "|" + Utils.mobileSigningSecretFingerprint(null)
        apiCache[cacheKey!!] = Proxy.newProxyInstance(ChronicleStudyApi::class.java.classLoader,
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

        assertTrue(freshReminderUrl(context, posted).endsWith("#accessCode=$fresh"))
    }

    /** Servers without the GET or the mint answer 404; the manifest fallback still classifies a removed form. */
    @Test fun removedReminderFormIsClassifiedAsUnavailable() {
        TestStores.install(context, enrolled = true)
        val server = ChronicleDb.getInstance(context).uploadServerDao().getConfiguredServer()!!
            .copy(authMode = AUTH_MODE_API_KEY, apiKey = "device-key")
        ChronicleDb.getInstance(context).uploadServerDao().update(server)
        cacheKey = Utils.normalizeTrustedServerUrl(server.url) + "|" + Utils.mobileSigningSecretFingerprint(null)
        apiCache[cacheKey!!] = Proxy.newProxyInstance(ChronicleStudyApi::class.java.classLoader,
            arrayOf(ChronicleStudyApi::class.java)) { _, method, _ ->
            when (method.name) {
                "getMobileReminderSchedule" -> throw ChronicleCallException("GET", "url", "", 500)
                "createParticipantFormAccessCode" -> throw ChronicleCallException("POST", "url", "", 404)
                "getMobileReminderConfiguration" -> MobileReminderConfiguration(ParticipationStatus.ENROLLED, emptyList())
                else -> error("unexpected ${method.name}")
            }
        } as ChronicleStudyApi
        val posted = NotificationDetails(UUID.randomUUID().toString(), NotificationType.QUESTIONNAIRE, "FREQ=DAILY",
            "Removed form", "Tap", serverUrl = server.url, accessCode = "s".repeat(40))

        assertThrows(ReminderFormUnavailableException::class.java) { freshReminderUrl(context, posted) }
    }
}
