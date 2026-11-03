package com.openlattice.chronicle.api

import android.app.Application
import com.openlattice.chronicle.EnrollmentRecoveryResult
import com.openlattice.chronicle.collection.CollectionModuleId
import com.openlattice.chronicle.collection.CollectionModuleSetting
import com.openlattice.chronicle.collection.ConsentTrigger
import com.openlattice.chronicle.collection.state.PendingCollectionAckRecord
import com.openlattice.chronicle.collection.state.enrollmentModulePartition
import com.openlattice.chronicle.enrollmentRefusal
import com.openlattice.chronicle.generateProposedEnrollmentApiKey
import com.openlattice.chronicle.preferences.getDevice
import com.openlattice.chronicle.serialization.ChronicleJson
import com.openlattice.chronicle.storage.UploadServerEntity
import com.openlattice.chronicle.utils.Utils
import java.io.File
import java.time.OffsetDateTime
import java.util.UUID
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The production [ChronicleStudyApi] (Retrofit, Moshi, the Chronicle call adapter, the real OkHttp
 * client) against an HTTP server. Header names, paths, bodies and the server's error envelope are written out here as
 * the server defines them (StudyController, ApiError, ChronicleFields, ChronicleHeaders), not taken from the client.
 * With CHRONICLE_ANDROID_ENROLL_DIR set, the captured requests are written there for the server-side replay.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class EnrollmentWireContractTest {
    private lateinit var server: MockWebServer
    private lateinit var api: ChronicleStudyApi
    private val study = UUID.fromString("7a1c4e9e-2f0b-4b8e-9d55-0c1f4a2b3c4d")
    // Spaces, a slash and accents must survive as one path segment.
    private val participant = "Señora P/7 ñ"
    private val code = "A".repeat(64)
    private val digest = "0123456789abcdef".repeat(4)
    private val attempt = "00000000-0000-4000-8000-000000000008"
    private val device = "00000000-0000-4000-8000-000000000007"
    private val proposed = "ck_AAAAAAAA_AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA"

    @Before fun start() {
        server = MockWebServer().apply { start() }
        api = Utils.createRetrofitAdapter(server.url("/").toString()).create(ChronicleStudyApi::class.java)
    }

    @After fun stop() = server.shutdown()

    private fun respond(status: Int, body: String) =
        server.enqueue(MockResponse().setResponseCode(status).setHeader("Content-Type", "application/json").setBody(body))

    /** The server's ApiError envelope (Jackson, NON_NULL). */
    private fun apiError(status: Int, error: String, code: String? = null) = JSONObject()
        .put("status", status).put("error", error).put("message", "refused").put("errorId", UUID.randomUUID().toString())
        .put("timestamp", "2026-10-30T12:00:00-03:00").put("path", "/chronicle/v4/study/{studyId}/participant/{participantId}/enroll")
        .apply { code?.let { put("code", it) } }.toString()

    private fun export(name: String, request: RecordedRequest) {
        val dir = System.getenv("CHRONICLE_ANDROID_ENROLL_DIR")?.let(::File) ?: return
        dir.mkdirs()
        File(dir, "$name.json").writeText(JSONObject()
            .put("method", request.method).put("path", request.path)
            .put("headers", JSONObject(request.headers.names().associateWith { request.headers[it] }))
            .put("body", request.body.readUtf8()).toString(2))
    }

    private fun enroll() = api.enroll(study, participant, device, getDevice(device), code, digest, attempt, proposed)

    private fun encodedPath(suffix: String) =
        "/chronicle/v4/study/$study/participant/Se%C3%B1ora%20P%2F7%20%C3%B1$suffix"

    @Test fun previewSendsTheCodeHeaderAndDecodesTheManifest() {
        val json = javaClass.getResource("/enrollment-preview.json")!!.readText()
        val preview = JSONObject(json)
        preview.getJSONObject("manifest").put("studyId", study.toString()).put("participantId", participant)
            .put("issuedAt", "2026-10-30T12:00:00-03:00").put("expiresAt", "2026-10-30T13:00:00-03:00")
        preview.put("manifestDigest", digest)
        respond(200, preview.toString())

        val decoded = api.getEnrollmentPreview(study, participant, code)

        val request = server.takeRequest()
        assertEquals("GET", request.method)
        assertEquals(encodedPath("/enrollment-preview"), request.path)
        assertEquals(code, request.getHeader("X-Chronicle-Enrollment-Code"))
        assertNull("no credential beyond the invitation code", request.getHeader("X-Api-Key"))
        assertEquals(digest, decoded.manifestDigest)
        assertEquals(study, decoded.manifest.studyId)
        assertEquals(participant, decoded.manifest.participantId)
        assertEquals(OffsetDateTime.parse("2026-10-30T15:00:00Z").toInstant(), decoded.manifest.issuedAt.toInstant())
    }

    @Test fun aPreviewWithAMalformedDigestIsRefusedNotTrusted() {
        val preview = JSONObject(javaClass.getResource("/enrollment-preview.json")!!.readText()).put("manifestDigest", digest.uppercase())
        respond(200, preview.toString())
        assertTrue(runCatching { api.getEnrollmentPreview(study, participant, code) }.isFailure)
    }

    /**
     * One enrollment, then the acknowledgment the app builds for it: the issued-enrollment row as
     * promotePendingEnrollment stores it, PendingCollectionAckRecord.from as reportEnrollmentDecisions calls it, and the report
     * call the retry queue makes. Both requests are exported from this one test, so they name the same device and key.
     */
    @Test fun enrollmentAndItsAcknowledgmentCarryOneDeviceKeyAndTheAuthoritativeEvidence() {
        respond(200, """{"chronicleId":"${UUID.randomUUID()}","enrollmentId":"${UUID.randomUUID()}","apiKey":"$proposed"}""")

        val response = enroll()

        val request = server.takeRequest()
        assertEquals("POST", request.method)
        assertEquals(encodedPath("/enroll"), request.path)
        assertEquals(device, request.getHeader("X-Chronicle-Random-Device-Id"))
        assertEquals(code, request.getHeader("X-Chronicle-Enrollment-Code"))
        assertEquals(digest, request.getHeader("X-Chronicle-Manifest-Digest"))
        assertEquals(attempt, request.getHeader("X-Chronicle-Enrollment-Attempt-Id"))
        assertEquals(proposed, request.getHeader("X-Chronicle-Proposed-Api-Key"))
        val body = JSONObject(request.body.clone().readUtf8())
        assertEquals("com.openlattice.chronicle.sources.AndroidDevice", body.getString("@class"))
        assertEquals(device, body.getString("randomDeviceId"))
        assertTrue(listOf("device", "model", "codename", "brand", "osVersion", "sdkVersion", "product").all(body::has))
        assertEquals("the issued key is the proposed one", proposed, response.apiKey)

        val manifest = checkNotNull(ChronicleJson.moshi.adapter(EnrollmentPreviewResponse::class.java)
            .fromJson(javaClass.getResource("/enrollment-preview.json")!!.readText())).manifest
        val enabled = setOf(CollectionModuleId.USAGE_EVENTS, CollectionModuleId.BATTERY_TELEMETRY,
            CollectionModuleId.IN_APP_ACTIVITY_CLASS, CollectionModuleId.UPLOAD_TELEMETRY)
        val settings = manifest.collectionSettings.copy(modules = CollectionModuleId.entries.associateWith {
            CollectionModuleSetting(enabled = it in enabled, required = it == CollectionModuleId.USAGE_EVENTS)
        })
        // The wizard decides the participant-controlled modules; enrollment adds the disclosed operational modules.
        val partition = enrollmentModulePartition(settings, availableSensorModules = emptySet(),
            accepted = setOf(CollectionModuleId.USAGE_EVENTS, CollectionModuleId.BATTERY_TELEMETRY),
            declined = setOf(CollectionModuleId.IN_APP_ACTIVITY_CLASS))
        val settingsVersion = partition.settingsVersion
        val disclosureVersion = manifest.participantPolicy.version
        val enrolled = UploadServerEntity(name = "Pilot", url = server.url("/").toString(), studyId = study.toString(),
            participantId = participant, sourceRandomDeviceId = device, apiKey = response.apiKey,
            disclosureVersion = disclosureVersion, manifestDigest = digest)
        val acknowledgment = checkNotNull(PendingCollectionAckRecord.from(server = enrolled,
            accepted = partition.accepted, declined = partition.declined, unavailable = partition.unavailable,
            trigger = ConsentTrigger.ENROLLMENT,
            acknowledgedAt = OffsetDateTime.parse("2026-10-30T12:00:00-03:00"), settingsVersion = settingsVersion,
        ).toAcknowledgmentOrNull())
        respond(200, "{}")
        api.reportCollectionAck(UUID.fromString(enrolled.studyId), enrolled.participantId, enrolled.sourceRandomDeviceId,
            enrolled.apiKey, acknowledgment)

        val ack = server.takeRequest()
        assertEquals("POST", ack.method)
        assertEquals(encodedPath("/android/collection-ack"), ack.path)
        assertEquals(device, ack.getHeader("X-Chronicle-Random-Device-Id"))
        assertEquals(proposed, ack.getHeader("X-Api-Key"))
        val sent = JSONObject(ack.body.clone().readUtf8())
        fun ids(key: String) = sent.getJSONArray(key).let { a: JSONArray -> List(a.length()) { a.getString(it) }.toSet() }
        assertEquals(setOf("usage_events", "battery_telemetry", "upload_telemetry"), ids("acknowledgedModules"))
        assertEquals(setOf("in_app_activity_class"), ids("declinedModules"))
        assertEquals("ENROLLMENT", sent.getString("trigger"))
        assertEquals(OffsetDateTime.parse("2026-10-30T15:00:00Z").toInstant(),
            OffsetDateTime.parse(sent.getString("acknowledgedAt")).toInstant())
        // The evidence the backend requires to bind the decision to this enrollment receipt.
        assertEquals(settingsVersion, sent.getInt("settingsVersion"))
        assertEquals(disclosureVersion, sent.getString("disclosureVersion"))
        assertEquals(digest, sent.getString("manifestDigest"))
        assertTrue(sent.getString("appVersion").isNotBlank())
        export("enroll-request", request)
        export("collection-ack-request", ack)
    }

    @Test fun aResponseWithoutTheProposedKeyIsVisibleToTheCaller() {
        respond(200, """{"chronicleId":"${UUID.randomUUID()}"}""")
        assertNull(enroll().apiKey)
        respond(200, "\"${UUID.randomUUID()}\"")
        assertNull("the upstream bare-UUID reply carries no key", enroll().apiKey)
        val differentKey = generateProposedEnrollmentApiKey()
        respond(200, """{"chronicleId":"${UUID.randomUUID()}","apiKey":"$differentKey"}""")
        assertEquals(differentKey, enroll().apiKey)
    }

    /** The server's real error envelopes, decoded through the call adapter, classify as the recovery expects. */
    @Test fun serverRefusalsClassifyFromTheirEnvelope() {
        val cases = listOf(
            Triple(410, apiError(410, "Gone", "ENROLLMENT_CANCELLED"), EnrollmentRecoveryResult.CANCELLED),
            Triple(409, apiError(409, "Conflict", "ENROLLMENT_STUDY_CHANGED"), EnrollmentRecoveryResult.STUDY_CHANGED),
            Triple(409, apiError(409, "Conflict"), null),
            Triple(401, apiError(401, "Unauthorized"), EnrollmentRecoveryResult.TERMINAL_FAILURE),
            Triple(400, apiError(400, "Bad Request"), EnrollmentRecoveryResult.SERVER_INCOMPATIBLE),
            Triple(404, apiError(404, "Not Found"), EnrollmentRecoveryResult.SERVER_INCOMPATIBLE),
            Triple(403, apiError(403, "Forbidden"), EnrollmentRecoveryResult.SERVER_REFUSED),
            Triple(429, apiError(429, "Too Many Requests"), null),
            Triple(503, "<html>maintenance</html>", null),
        )
        for ((status, body, expected) in cases) {
            respond(status, body)
            val error = checkNotNull(runCatching { enroll() }.exceptionOrNull()) { "HTTP $status must fail" }
            assertEquals("HTTP $status $body", expected, enrollmentRefusal(error))
            server.takeRequest()
        }
    }

    @Test fun cancellationOfASentAttemptUsesTheProposedKey() {
        val requestId = UUID.randomUUID().toString()
        respond(200, """{"requestId":"${UUID.randomUUID()}","status":"accepted","deletionJobIds":[],"alreadyWithdrawn":false}""")
        val response = api.withdrawCurrentEnrollment(device, proposed, requestId)
        val request = server.takeRequest()
        assertEquals("DELETE", request.method)
        assertEquals("/chronicle/v4/mobile/enrollments/current", request.path)
        assertEquals(device, request.getHeader("X-Chronicle-Random-Device-Id"))
        assertEquals(proposed, request.getHeader("X-Api-Key"))
        assertEquals(requestId, request.getHeader("X-Chronicle-Withdrawal-Request-Id"))
        assertEquals("accepted", response.status)
    }
}
