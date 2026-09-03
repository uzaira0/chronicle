package com.openlattice.chronicle.collection.upload

import com.openlattice.chronicle.collection.core.NoOpCollectionLog
import com.openlattice.chronicle.storage.AUTH_MODE_API_KEY
import com.openlattice.chronicle.storage.UploadServerEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Redaction guard for [UploadTelemetryCollectionModule] (Phase 8A — design §1B.3,
 * refactor plan §11.1 steps 16–18, guardrails 8A.1 & 8A.2).
 *
 * Diagnostics for the `upload_telemetry` module carry **operational data only**: queue
 * depths, counts, WorkManager state. They must never contain an `apiKey`, a device
 * secret, a `MOBILE_SIGNING_SECRET`, a raw `participantId`, a study id, a device id, or
 * a server URL.
 *
 * The strategy: build an [UploadServerEntity] whose secret-bearing fields all hold
 * unmistakable sentinel substrings, project it the way the production [RoomUploadStateProbe]
 * does, render the full module diagnostics, and assert no sentinel survives anywhere in
 * the rendered output.
 *
 */
class UploadTelemetryRedactionTest {

    private companion object {
        const val SENTINEL_API_KEY = "SENTINEL-APIKEY-d34db33f"
        const val SENTINEL_PARTICIPANT = "SENTINEL-PARTICIPANT-alice-001"
        const val SENTINEL_DEVICE_ID = "SENTINEL-DEVICEID-uuid-xyz"
        const val SENTINEL_SIGNING_SECRET = "SENTINEL-SIGNING-SECRET"
        const val SENTINEL_STUDY = "SENTINEL-STUDY-uuid-abc"
        const val SENTINEL_URL = "https://SENTINEL-host.example.com/chronicle"
        const val SENTINEL_ERROR =
            "HTTP 401 from $SENTINEL_URL apiKey=$SENTINEL_API_KEY participant=$SENTINEL_PARTICIPANT"

        val SENTINELS = listOf(
            SENTINEL_API_KEY, SENTINEL_PARTICIPANT, SENTINEL_DEVICE_ID,
            SENTINEL_SIGNING_SECRET, SENTINEL_STUDY, SENTINEL_URL, SENTINEL_ERROR,
            "MOBILE_SIGNING_SECRET",
        )
    }

    /** A server entity whose every secret-bearing field carries a sentinel substring. */
    private fun secretLadenServer() = UploadServerEntity(
        id = 7L,
        name = "BCM Research",
        url = SENTINEL_URL,
        studyId = SENTINEL_STUDY,
        participantId = SENTINEL_PARTICIPANT,
        sourceDeviceId = SENTINEL_DEVICE_ID,
        authMode = AUTH_MODE_API_KEY,
        apiKey = SENTINEL_API_KEY,
        mobileSigningSecretOverride = SENTINEL_SIGNING_SECRET,
        enabled = true,
        lastUploadTime = "2026-05-20T10:00:00Z",
        lastUploadError = SENTINEL_ERROR,
        consecutiveFailures = 4,
        lastSensorUploadTime = "2026-05-20T10:05:00Z",
        lastSensorUploadError = SENTINEL_ERROR,
        sensorConsecutiveFailures = 2,
    )

    /**
     * Projects an [UploadServerEntity] to [UploadServerTelemetry] exactly as the
     * production [RoomUploadStateProbe.servers] does — kept in sync with that mapping so
     * this test guards the real projection's redaction contract.
     */
    private fun project(e: UploadServerEntity) = UploadServerTelemetry(
        serverId = e.id,
        enabled = e.enabled,
        consecutiveFailures = e.consecutiveFailures,
        sensorConsecutiveFailures = e.sensorConsecutiveFailures,
        lastUsageUploadTime = e.lastUploadTime,
        lastSensorUploadTime = e.lastSensorUploadTime,
        hasUsageUploadError = e.lastUploadError != null,
        hasSensorUploadError = e.lastSensorUploadError != null,
    )

    @Test
    fun serverTelemetryProjectionDropsEverySecretBearingField() {
        val telemetry = project(secretLadenServer())
        val rendered = telemetry.toString()
        for (sentinel in SENTINELS) {
            assertFalse(
                "UploadServerTelemetry must not carry sentinel: $sentinel",
                rendered.contains(sentinel),
            )
        }
        // It still carries the operational data it is supposed to.
        assertTrue(telemetry.hasUsageUploadError)
        assertTrue(telemetry.hasSensorUploadError)
        assertEquals(7L, telemetry.serverId)
        assertEquals(4, telemetry.consecutiveFailures)
    }

    @Test
    fun renderedDiagnosticsContainNoSecretFromASecretLadenServer() {
        val state = FakeUploadStateProbe(
            servers = mutableListOf(project(secretLadenServer())),
            usageDepth = 3,
            sensorDepth = 9,
            statsRows = 1,
        )
        val work = FakeUploadWorkProbe(
            periodic = CombinedUploadWorkStatus("combined_upload", "ENQUEUED", 2, true),
        )
        val module = UploadTelemetryCollectionModule(state, work, NoOpCollectionLog)

        val diagnostics = module.diagnostics()
        val snapshot = module.snapshot()
        // The full rendered surface of everything diagnostics expose.
        val renderedSurface = buildString {
            append(diagnostics.toString())
            append('\n')
            append(diagnostics.lastResult)
            append('\n')
            append(diagnostics.lastError)
            append('\n')
            diagnostics.notTracked.forEach { append(it).append('\n') }
            append(snapshot.toString())
        }

        for (sentinel in SENTINELS) {
            assertFalse(
                "rendered upload-telemetry diagnostics must not contain sentinel: $sentinel\n" +
                    "rendered surface was:\n$renderedSurface",
                renderedSurface.contains(sentinel),
            )
        }
        // The error is still *signalled* — as a count, not as text.
        assertTrue(diagnostics.lastError!!.contains("upload errors:"))
    }

    @Test
    fun diagnosticsExposeNoRawParticipantReference() {
        val state = FakeUploadStateProbe(servers = mutableListOf(project(secretLadenServer())))
        val diagnostics = UploadTelemetryCollectionModule(
            state, FakeUploadWorkProbe(), NoOpCollectionLog,
        ).diagnostics()
        // The module deliberately derives no participant reference at all.
        assertEquals(null, diagnostics.redactedParticipantRef)
    }
}
