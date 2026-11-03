package com.openlattice.chronicle.services.upload

import com.openlattice.chronicle.TEST_SERVER_HOST
import com.openlattice.chronicle.EnrollmentTestStores
import com.openlattice.chronicle.assumeTestServerHostTrusted
import com.openlattice.chronicle.storage.UploadServerEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import android.app.Application
import androidx.test.core.app.ApplicationProvider
import com.openlattice.chronicle.collection.battery.BatteryUploadWorkerDelegate
import com.openlattice.chronicle.collection.device.ExpansionUploadWorkerDelegate
import com.openlattice.chronicle.collection.state.onPersistenceWorker
import com.openlattice.chronicle.storage.BatterySampleEntry
import com.openlattice.chronicle.storage.ConnectivityStateSampleEntry
import com.openlattice.chronicle.storage.QueueEntry
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.UUID

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class ActiveEnrollmentServerPolicyTest {
    private val studyId = UUID.fromString("11111111-1111-1111-1111-111111111111")
    private val participantId = "participant-a"

    @Test fun identityOnlyEnrollmentCannotBeReauthorizedByResearch() {
        val incomplete = server(apiKey = null)
            .copy(url = EnrollmentTestStores.TEST_SERVER_URL)
        assertNull(resolveServerForIdentity(incomplete, studyId, participantId).server)
    }

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
    fun `missing and blank credentials fail closed`() {
        assertNull(
            completeServerForIdentity(
                server(apiKey = null),
                studyId,
                participantId,
            ),
        )
        assertNull(
            completeServerForIdentity(
                server(sourceRandomDeviceId = ""),
                studyId,
                participantId,
            ),
        )
        assertNull(
            completeServerForIdentity(
                server(apiKey = " "),
                studyId,
                participantId,
            ),
        )
    }

    @Test
    fun `all distributions reject identity-only authentication`() {
        assumeTestServerHostTrusted()
        val identityOnlyServer = server(apiKey = null)

        val resolution = resolveServerForIdentity(
            identityOnlyServer,
            studyId,
            participantId,
        )

        assertNull(resolution.server)
        assertEquals(
            UploadDestinationIssue.DESTINATION_CREDENTIAL_INCOMPLETE,
            resolution.issue,
        )
        assertNull(resolveServerForIdentity(identityOnlyServer, studyId, participantId).server)
    }

    @Test
    fun `every destination rejection has one closed diagnostic category`() {
        assumeTestServerHostTrusted()
        val cases = listOf(
            null to UploadDestinationIssue.DESTINATION_MISSING,
            server(participantId = "participant-b") to
                UploadDestinationIssue.DESTINATION_IDENTITY_MISMATCH,
            server(sourceRandomDeviceId = "") to
                UploadDestinationIssue.DESTINATION_SOURCE_DEVICE_MISSING,
            server(enrollmentSetupComplete = false) to
                UploadDestinationIssue.DESTINATION_SETUP_INCOMPLETE,
            server(enabled = false) to
                UploadDestinationIssue.DESTINATION_DISABLED,
            server(url = "http://$TEST_SERVER_HOST") to
                UploadDestinationIssue.DESTINATION_NONCANONICAL,
            server(apiKey = null) to
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
            "http://$TEST_SERVER_HOST",
            "https://$TEST_SERVER_HOST/path",
            "https://user@$TEST_SERVER_HOST",
            "https://$TEST_SERVER_HOST?token=value",
        ).forEach { url ->
            assertNull(
                completeServerForIdentity(
                    server(url = url, apiKey = "test-api-key"),
                    studyId,
                    participantId,
                ),
            )
        }
    }

    @Test
    fun `every upload family retries and retains old rows without a complete active credential`() = onPersistenceWorker {
        val context = ApplicationProvider.getApplicationContext<Application>()
        val db = EnrollmentTestStores.install(context, enrolled = true)
        try {
            val configured = checkNotNull(db.uploadServerDao().getConfiguredServer())
            db.uploadServerDao().update(configured.copy(apiKey = null))
            val battery = BatterySampleEntry("battery", "2000-01-01T00:00:00Z", "UTC", 50,
                "DISCHARGING", "NONE", 200, 4000, "GOOD")
            val connectivity = ConnectivityStateSampleEntry("connectivity", "2000-01-01T00:00:00Z", "UTC",
                "changed", "WIFI", true, false, true)
            db.batterySampleDao().insertAllUnchecked(listOf(battery))
            db.connectivityStateSampleDao().insertAllUnchecked(listOf(connectivity))
            db.queueEntryData().insertBoundedEntries(listOf(QueueEntry(1, 1, "[]".toByteArray())))

            assertEquals(UploadDestinationIssue.DESTINATION_CREDENTIAL_INCOMPLETE,
                UploadPolicy(context, db).resolveDestination().issue)
            assertTrue(UploadPolicy(context, db).getEligibleServers().isEmpty())
            assertEquals(1, UploadWorkerDelegate(context, db).execute())
            assertEquals(1, BatteryUploadWorkerDelegate(context, db).execute())
            assertEquals(1, ExpansionUploadWorkerDelegate(context, db).execute())
            assertEquals(1, db.queueEntryData().getSize())
            assertEquals(listOf(battery), db.batterySampleDao().getOldest(10))
            assertEquals(listOf(connectivity), db.connectivityStateSampleDao().getOldest(10))
            assertTrue(EnrollmentTestStores.apiRequests.isEmpty())
            val issues = LocalUploadDiagnosticsStore.of(context).recent()
            assertEquals(setOf(LocalUploadModuleFamily.USAGE_LIFECYCLE.name, LocalUploadModuleFamily.BATTERY.name,
                LocalUploadModuleFamily.DEVICE_TELEMETRY.name), issues.map { it.moduleFamily }.toSet())
            assertTrue(issues.all { it.issue == UploadDestinationIssue.DESTINATION_CREDENTIAL_INCOMPLETE.name && it.count == 1 })
        } finally {
            db.close()
            EnrollmentTestStores.db = null
            EnrollmentTestStores.prefs = null
        }
    }

    @Test
    fun `only a complete API key retains its configured destination`() {
        assumeTestServerHostTrusted()
        val apiKeyServer = server(apiKey = "test-api-key")
        val identityOnlyServer = server(apiKey = null)

        assertEquals(
            "https://$TEST_SERVER_HOST",
            completeServerForIdentity(apiKeyServer, studyId, participantId)?.url,
        )
        assertNull(completeServerForIdentity(identityOnlyServer, studyId, participantId))
    }

    @Test
    fun `provisional acknowledgment owner requires the exact immutable issued row`() {
        assumeTestServerHostTrusted()
        val expected = server(
            apiKey = "test-api-key",
            enrollmentSetupComplete = false,
        ).copy(
            reservationNonce = "owner-nonce",
            disclosureVersion = "disclosure-1",
            manifestDigest = "manifest-1",
        )

        assertTrue(isExpectedProvisionalEnrollmentServer(expected.copy(id = 99), expected))
        assertTrue(!isExpectedProvisionalEnrollmentServer(expected.copy(sourceRandomDeviceId = "other"), expected))
        assertTrue(!isExpectedProvisionalEnrollmentServer(expected.copy(apiKey = "other-key"), expected))
        assertTrue(!isExpectedProvisionalEnrollmentServer(expected.copy(reservationNonce = "other"), expected))
    }

    private fun server(
        url: String = "https://$TEST_SERVER_HOST",
        studyId: String = this.studyId.toString(),
        participantId: String = this.participantId,
        sourceRandomDeviceId: String = "device-a",

        apiKey: String? = "test-api-key",
        enabled: Boolean = true,
        enrollmentSetupComplete: Boolean = true,
    ): UploadServerEntity = UploadServerEntity(
        name = "Example study",
        url = url,
        studyId = studyId,
        participantId = participantId,
        sourceRandomDeviceId = sourceRandomDeviceId,
        apiKey = apiKey,
        enabled = enabled,
        enrollmentSetupComplete = enrollmentSetupComplete,
    )
}
