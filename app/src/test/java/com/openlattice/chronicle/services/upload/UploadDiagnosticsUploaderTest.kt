package com.openlattice.chronicle.services.upload

import com.openlattice.chronicle.storage.DiagnosticDeliveryState
import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.openlattice.chronicle.EnrollmentTestStores
import com.openlattice.chronicle.android.UploadReceipt
import com.openlattice.chronicle.api.CollectedUploadHeaders
import com.openlattice.chronicle.audit.studyApi
import com.openlattice.chronicle.collection.AndroidUploadDiagnosticEvent
import com.openlattice.chronicle.collection.state.onPersistenceWorker
import com.openlattice.chronicle.storage.ChronicleDb
import com.openlattice.chronicle.storage.UploadDiagnosticEntity
import com.openlattice.chronicle.storage.UploadServerEntity
import java.net.HttpURLConnection
import java.time.LocalDate
import java.time.OffsetDateTime
import java.util.UUID
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowLog
import com.openlattice.chronicle.android.ChronicleSample
import com.openlattice.chronicle.android.fromInteractionType
import com.openlattice.chronicle.api.ChronicleStudyApi
import com.openlattice.chronicle.models.ExtractedUsageEvent
import com.openlattice.chronicle.utils.Utils
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import retrofit2.HttpException
import retrofit2.Response

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class UploadDiagnosticsUploaderTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private lateinit var db: ChronicleDb
    private lateinit var server: UploadServerEntity

    @Before fun setup() {
        db = EnrollmentTestStores.install(context, enrolled = true)
        server = db.uploadServerDao().getConfiguredServer()!!
    }
    @After fun close() { db.close(); EnrollmentTestStores.db = null; EnrollmentTestStores.prefs = null }

    @Test fun noncanonicalConfiguredDestinationNeverReceivesDiagnostics() = onPersistenceWorker {
        rows(1)
        db.uploadServerDao().update(server.copy(url = server.url + "/"))
        EnrollmentTestStores.installApi(studyApi { _, _ -> error("Rejected destination received diagnostics") }).use {
            assertEquals(0, UploadDiagnosticsUploader(context, db).execute())
        }
        assertTrue(EnrollmentTestStores.apiRequests.isEmpty())
    }

    @Test fun exactEnrollmentOwnsRoutingCredentialsAndTheReceiptSettlesEachRow() = onPersistenceWorker {
        val rows = rows(2)
        val calls = mutableListOf<Map<*, *>>()
        EnrollmentTestStores.installApi(studyApi { name, args ->
            assertEquals("uploadAndroidUploadDiagnostics", name)
            assertEquals(UUID.fromString(server.studyId), args!![0])
            assertEquals(server.participantId, args[1])
            calls += args[2] as Map<*, *>
            UploadReceipt(setOf(UUID.fromString(rows.first().id)), setOf(UUID.fromString(rows.last().id)), 0)
        }).use { assertEquals(0, UploadDiagnosticsUploader(context, db).execute()) }
        EnrollmentTestStores.assertApiRequests(EnrollmentTestStores.ApiRequest(server.url), 1)
        assertEquals(CollectedUploadHeaders(server.sourceRandomDeviceId, server.apiKey).toMap(), calls.single())
        assertEquals(DiagnosticDeliveryState.DELIVERED, db.uploadDiagnosticDao().get(rows.first().id)!!.deliveryState)
        assertEquals(DiagnosticDeliveryState.DISCARDED, db.uploadDiagnosticDao().get(rows.last().id)!!.deliveryState)
        assertEquals(rows.size, db.uploadDiagnosticDao().forEnrollment(server.studyId, server.participantId, server.sourceRandomDeviceId,
            "${server.id}:${server.createdAt}").size)
    }

    @Test fun aReceiptThatDoesNotAccountForEveryRowSettlesNothing() = onPersistenceWorker {
        val rows = rows(2)
        assertEquals(rows.map { it.id }.toSet(), LocalUploadDiagnosticsStore.of(context).pending().map { it.id }.toSet())
        EnrollmentTestStores.installApi(studyApi { method, _ ->
            assertEquals("uploadAndroidUploadDiagnostics", method)
            UploadReceipt(setOf(UUID.fromString(rows.first().id), UUID.randomUUID()), emptySet(), 0)
        }).use { assertEquals(1, UploadDiagnosticsUploader(context, db).execute()) }
        EnrollmentTestStores.assertApiRequests(EnrollmentTestStores.ApiRequest(server.url), 1)
        assertTrue(rows.all { db.uploadDiagnosticDao().get(it.id)!!.deliveryState == DiagnosticDeliveryState.PENDING })
    }

    /** At most 500 closed-code rows form one request well under the body budget, so a refusal keeps them all. */
    @Test fun badRequestKeepsEveryRowPendingWithoutRecursiveDiagnostics() = onPersistenceWorker {
        val rows = rows(4)
        var calls = 0
        EnrollmentTestStores.installApi(studyApi { name, args ->
            assertEquals("uploadAndroidUploadDiagnostics", name)
            calls++
            assertEquals(rows.size, decodedUploadBody<List<AndroidUploadDiagnosticEvent>>(args!![3]).size)
            throw httpFailure(HttpURLConnection.HTTP_BAD_REQUEST)
        }).use { assertEquals(1, UploadDiagnosticsUploader(context, db).execute()) }
        assertEquals(1, calls)
        assertTrue(rows.all { db.uploadDiagnosticDao().get(it.id)!!.deliveryState == DiagnosticDeliveryState.PENDING })
        assertEquals(rows.size, db.uploadDiagnosticDao().forEnrollment(server.studyId, server.participantId, server.sourceRandomDeviceId,
            "${server.id}:${server.createdAt}").size)
    }

    /** A merged span left by build 79 cannot be split at a pause; it stays in local quarantine and never blocks the batch. */
    @Test fun aLegacyMergedSpanIsQuarantinedAndTheRestUpload() = onPersistenceWorker {
        val point = rows(1).single()
        val span = point.copy(id = UUID.randomUUID().toString(), count = 2, sealedForUpload = true,
            lastOccurredAt = OffsetDateTime.parse(point.firstOccurredAt).plusMinutes(5).toString())
        db.uploadDiagnosticDao().upsert(span)
        var sent = emptyList<String>()
        EnrollmentTestStores.installApi(studyApi { _, args ->
            sent = decodedUploadBody<List<AndroidUploadDiagnosticEvent>>(args!![3]).map { it.id }
            UploadReceipt(setOf(UUID.fromString(point.id)), emptySet(), 0)
        }).use { assertEquals(0, UploadDiagnosticsUploader(context, db).execute()) }
        assertEquals(listOf(point.id), sent)
        assertEquals(DiagnosticDeliveryState.QUARANTINED, db.uploadDiagnosticDao().get(span.id)!!.deliveryState)
        assertTrue(db.localDataQuarantineDao().get("upload_diagnostics", span.id) != null)
    }

    /** The phone cannot see a pause the server applies; each occurrence keeps its own instant until the server filters it. */
    @Test fun occurrencesAreNeverMergedAcrossInstants() = onPersistenceWorker {
        val store = LocalUploadDiagnosticsStore.of(context)
        val beforePause = OffsetDateTime.parse("2026-10-20T14:00:00Z")
        val duringPause = beforePause.plusHours(2)
        store.recordFailure(LocalUploadModuleFamily.SENSOR, java.net.ConnectException(), occurredAt = beforePause)
        store.recordFailure(LocalUploadModuleFamily.SENSOR, java.net.ConnectException(), occurredAt = duringPause)

        val events = store.toWireEvents(store.pending())

        assertEquals(listOf(beforePause, duringPause), events.map { it.firstOccurredAt })
        assertTrue(events.all { it.count == 1 && it.firstOccurredAt.isEqual(it.lastOccurredAt) })
    }

    /**
     * Real call-adapter failures (400, 403, 500 and a refused connection, whose IOException arrives as a cause) carry
     * an API key, a collected app name and response text. None of them may reach the log (message or rendered
     * throwable), the persisted failure code, the stored diagnostic rows or the diagnostics upload body.
     */
    @Test fun failureSentinelsNeverReachLogsOrDiagnosticWireBytes() = onPersistenceWorker {
        val apiKey = "sentinelOne7c1"
        val collected = "collectedAppSentinel7c2"
        val responseText = "responseBodySentinel7c3"
        val sentinels = listOf(apiKey, collected, responseText)
        val event = ExtractedUsageEvent(eventType = fromInteractionType("Activity Resumed"), appPackageName = collected,
            interactionType = "Activity Resumed", timestamp = OffsetDateTime.parse("2026-10-30T12:00:00Z"),
            timezone = "America/Santiago", user = "", applicationLabel = collected, clientEventId = UUID.randomUUID())
        val body = CollectedUploadBody.encode(mapUsageSamplesForUpload(listOf(event), UUID.fromString(server.studyId),
            server.participantId), ChronicleSample::class.java)!!
        val errors = MockWebServer().use { mock ->
            mock.enqueue(MockResponse().setResponseCode(400).setBody("""{"code":"VALIDATION_FAILED","message":"$responseText $apiKey"}"""))
            mock.enqueue(MockResponse().setResponseCode(403).setBody("$responseText $collected"))
            mock.enqueue(MockResponse().setResponseCode(500).setBody("<html>$responseText</html>"))
            val api = Utils.createRetrofitAdapter(mock.url("/").toString()).create(ChronicleStudyApi::class.java)
            fun fail() = checkNotNull(runCatching { api.uploadAndroidUsageEventData(UUID.fromString(server.studyId),
                server.participantId, CollectedUploadHeaders(server.sourceRandomDeviceId, apiKey).toMap(), body) }
                .exceptionOrNull() as? Exception) { "the upload must fail" }
            val refused = List(3) { fail() }
            mock.shutdown()
            refused + fail()
        }
        val codes = mutableListOf<String>()
        ShadowLog.clear()
        errors.forEach { error ->
            handleServerUploadFailure(context, "UploadWorker", error, LocalUploadModuleFamily.USAGE_LIFECYCLE, 0) { _, code ->
                codes += code
            }
        }
        val logged = ShadowLog.getLogs().joinToString("\n") { "${it.tag} ${it.msg} ${it.throwable?.stackTraceToString()}" }
        val stored = db.uploadDiagnosticDao().forEnrollment(server.studyId, server.participantId, server.sourceRandomDeviceId,
            "${server.id}:${server.createdAt}")
        assertEquals(listOf(400, 403, 500, null), stored.sortedBy { it.httpStatus ?: Int.MAX_VALUE }.map { it.httpStatus })
        assertTrue("closed failure codes only: $codes", codes.size == errors.size && codes.all { it.matches(Regex("[A-Z_]+")) })
        var wire = ""
        EnrollmentTestStores.installApi(studyApi { _, args ->
            wire = (args!![3] as CollectedUploadBody<*>).json()
            UploadReceipt(stored.mapTo(linkedSetOf()) { UUID.fromString(it.id) }, emptySet(), 0)
        }).use { assertEquals(0, UploadDiagnosticsUploader(context, db).execute()) }
        assertTrue("the diagnostics upload ran", wire.isNotEmpty())
        for (sentinel in sentinels) {
            assertTrue("$sentinel reached the log", sentinel !in logged)
            assertTrue("$sentinel reached a failure code", codes.none { sentinel in it })
            assertTrue("$sentinel reached a stored diagnostic", stored.none { sentinel in it.toString() })
            assertTrue("$sentinel reached the diagnostics wire bytes", sentinel !in wire)
        }
    }

    private fun rows(count: Int): List<UploadDiagnosticEntity> = List(count) {
        val occurredAt = OffsetDateTime.now().toString()
        UploadDiagnosticEntity(UUID.randomUUID().toString(), server.studyId, server.participantId, server.sourceRandomDeviceId,
            "${server.id}:${server.createdAt}", LocalDate.now().toString(), LocalUploadModuleFamily.SENSOR.name,
            UploadFailureIssue.CONNECTION_FAILURE.name, 1, occurredAt, occurredAt, null, null)
    }.also { it.forEach(db.uploadDiagnosticDao()::upsert) }

    private fun httpFailure(status: Int) = HttpException(Response.error<Any>(status, "synthetic rejection".toResponseBody()))
}
