package com.openlattice.chronicle.services.upload

import com.openlattice.chronicle.storage.AUTH_MODE_API_KEY
import com.openlattice.chronicle.storage.AUTH_MODE_DEVICE_ID
import com.openlattice.chronicle.storage.UploadServerEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.util.UUID

class ActiveEnrollmentServerPolicyTest {
    private val studyId = UUID.fromString("11111111-1111-1111-1111-111111111111")
    private val participantId = "participant-a"

    @Test
    fun `missing disabled incomplete and mismatched servers fail closed`() {
        assertNull(completeServerForIdentity(null, studyId, participantId))
        assertNull(completeServerForIdentity(server(enabled = false), studyId, participantId))
        assertNull(
            completeServerForIdentity(
                server(enrollmentSetupComplete = false),
                studyId,
                participantId,
            ),
        )
        assertEquals(
            UploadDestinationIssue.DESTINATION_MISSING,
            resolveServerForIdentity(null, studyId, participantId).issue,
        )
        assertEquals(
            UploadDestinationIssue.DESTINATION_DISABLED,
            resolveServerForIdentity(server(enabled = false), studyId, participantId).issue,
        )
        assertEquals(
            UploadDestinationIssue.DESTINATION_SETUP_INCOMPLETE,
            resolveServerForIdentity(
                server(enrollmentSetupComplete = false),
                studyId,
                participantId,
            ).issue,
        )
        assertEquals(
            UploadDestinationIssue.DESTINATION_IDENTITY_MISMATCH,
            resolveServerForIdentity(server(participantId = "participant-b"), studyId, participantId).issue,
        )
        assertNull(
            completeServerForIdentity(
                server(studyId = "22222222-2222-2222-2222-222222222222"),
                studyId,
                participantId,
            ),
        )
        assertNull(
            completeServerForIdentity(
                server(participantId = "participant-b"),
                studyId,
                participantId,
            ),
        )
    }

    @Test
    fun `credential incomplete and unknown authentication modes fail closed`() {
        assertNull(
            completeServerForIdentity(
                server(authMode = AUTH_MODE_API_KEY, apiKey = null),
                studyId,
                participantId,
            ),
        )
        assertNull(
            completeServerForIdentity(
                server(authMode = AUTH_MODE_DEVICE_ID, sourceDeviceId = ""),
                studyId,
                participantId,
            ),
        )
        assertNull(
            completeServerForIdentity(
                server(authMode = "unsupported"),
                studyId,
                participantId,
            ),
        )
    }

    @Test
    fun `public store policy rejects legacy device identity authentication`() {
        val legacyServer = server(authMode = AUTH_MODE_DEVICE_ID, apiKey = null)

        val resolution = resolveServerForIdentity(
            legacyServer,
            studyId,
            participantId,
            requireApiKey = true,
        )

        assertNull(resolution.server)
        assertEquals(
            UploadDestinationIssue.DESTINATION_CREDENTIAL_INCOMPLETE,
            resolution.issue,
        )
        assertEquals(
            legacyServer,
            resolveServerForIdentity(
                legacyServer,
                studyId,
                participantId,
                requireApiKey = false,
            ).server,
        )
    }

    @Test
    fun `every destination rejection has one closed diagnostic category`() {
        val cases = listOf(
            null to UploadDestinationIssue.DESTINATION_MISSING,
            server(participantId = "participant-b") to
                UploadDestinationIssue.DESTINATION_IDENTITY_MISMATCH,
            server(sourceDeviceId = "") to
                UploadDestinationIssue.DESTINATION_SOURCE_DEVICE_MISSING,
            server(enrollmentSetupComplete = false) to
                UploadDestinationIssue.DESTINATION_SETUP_INCOMPLETE,
            server(enabled = false) to
                UploadDestinationIssue.DESTINATION_DISABLED,
            server(url = "http://research.example.org") to
                UploadDestinationIssue.DESTINATION_NONCANONICAL,
            server(authMode = AUTH_MODE_API_KEY, apiKey = null) to
                UploadDestinationIssue.DESTINATION_CREDENTIAL_INCOMPLETE,
        )

        assertEquals(UploadDestinationIssue.entries.size, cases.size)
        cases.forEach { (candidate, expected) ->
            val resolution = resolveServerForIdentity(candidate, studyId, participantId)
            assertNull(resolution.server)
            assertEquals(expected, resolution.issue)
        }
        assertEquals(UploadDestinationIssue.entries.toSet(), cases.map { it.second }.toSet())
    }

    @Test
    fun `noncanonical or unsafe origins fail closed`() {
        listOf(
            "http://research.example.org",
            "https://research.example.org/path",
            "https://user@research.example.org",
            "https://research.example.org?token=value",
        ).forEach { url ->
            assertNull(
                completeServerForIdentity(
                    server(url = url, authMode = AUTH_MODE_API_KEY, apiKey = "test-api-key"),
                    studyId,
                    participantId,
                ),
            )
        }
    }

    @Test
    fun `every Play upload family rebinds its destination to the active identity`() {
        val uploadFamilies = listOf(
            "src/main/java/com/openlattice/chronicle/services/upload/UploadPolicy.kt",
            "src/main/java/com/openlattice/chronicle/collection/battery/BatteryUploadWorker.kt",
            "src/main/java/com/openlattice/chronicle/collection/device/ExpansionUploadWorker.kt",
        )
        uploadFamilies.forEach { relative ->
            val source = File(relative).readText()
            assertTrue(
                "$relative bypasses the exact active-enrollment destination gate",
                source.contains("exactActiveEnrollmentServerResolution("),
            )
        }
        listOf(
            "src/main/java/com/openlattice/chronicle/services/upload/UploadWorkerDelegate.kt",
            "src/main/java/com/openlattice/chronicle/collection/battery/BatteryUploadWorker.kt",
            "src/main/java/com/openlattice/chronicle/collection/device/ExpansionUploadWorker.kt",
        ).forEach { relative ->
            val source = File(relative).readText()
            val missingDestinationGate = source.indexOf("if (server == null)")
            val durableIssue = source.indexOf("LocalUploadDiagnosticsStore", missingDestinationGate)
            val retrySignal = source.indexOf("return 1", startIndex = missingDestinationGate.coerceAtLeast(0))
            assertTrue(
                "$relative silently succeeds when the active enrollment has no destination",
                missingDestinationGate >= 0 && durableIssue > missingDestinationGate &&
                    retrySignal > durableIssue,
            )
        }

        listOf(
            "src/main/java/com/openlattice/chronicle/collection/battery/BatteryUploadWorker.kt" to
                "deleteOlderThan(cutoff)",
            "src/main/java/com/openlattice/chronicle/collection/device/ExpansionUploadWorker.kt" to
                "purgeAll(anyFailClosed)",
        ).forEach { (relative, purgeMarker) ->
            val source = File(relative).readText()
            val missingDestinationGate = source.indexOf("if (server == null)")
            val purge = source.indexOf(purgeMarker)
            assertTrue(
                "$relative may purge retained samples before validating the destination",
                missingDestinationGate >= 0 && purge > missingDestinationGate,
            )
        }
    }

    @Test
    fun `complete API key and exact legacy servers retain their configured destination`() {
        val apiKeyServer = server(authMode = AUTH_MODE_API_KEY, apiKey = "test-api-key")
        val legacyServer = server(authMode = AUTH_MODE_DEVICE_ID)

        assertEquals(
            "https://research.example.org",
            completeServerForIdentity(apiKeyServer, studyId, participantId)?.url,
        )
        assertEquals(
            "https://research.example.org",
            completeServerForIdentity(
                legacyServer,
                studyId,
                participantId,
                requireApiKey = false,
            )?.url,
        )
    }

    @Test
    fun `provisional acknowledgment owner requires the exact immutable issued row`() {
        val expected = server(
            authMode = AUTH_MODE_API_KEY,
            apiKey = "test-api-key",
            enrollmentSetupComplete = false,
        ).copy(
            reservationNonce = "owner-nonce",
            disclosureVersion = "disclosure-1",
            manifestDigest = "manifest-1",
        )

        assertTrue(isExpectedProvisionalEnrollmentServer(expected.copy(id = 99), expected))
        assertTrue(!isExpectedProvisionalEnrollmentServer(expected.copy(sourceDeviceId = "other"), expected))
        assertTrue(!isExpectedProvisionalEnrollmentServer(expected.copy(apiKey = "other-key"), expected))
        assertTrue(!isExpectedProvisionalEnrollmentServer(expected.copy(reservationNonce = "other"), expected))
    }

    private fun server(
        url: String = "https://research.example.org",
        studyId: String = this.studyId.toString(),
        participantId: String = this.participantId,
        sourceDeviceId: String = "device-a",
        authMode: String = AUTH_MODE_API_KEY,
        apiKey: String? = "test-api-key",
        enabled: Boolean = true,
        enrollmentSetupComplete: Boolean = true,
    ): UploadServerEntity = UploadServerEntity(
        name = "Example study",
        url = url,
        studyId = studyId,
        participantId = participantId,
        sourceDeviceId = sourceDeviceId,
        authMode = authMode,
        apiKey = apiKey,
        enabled = enabled,
        enrollmentSetupComplete = enrollmentSetupComplete,
    )
}
