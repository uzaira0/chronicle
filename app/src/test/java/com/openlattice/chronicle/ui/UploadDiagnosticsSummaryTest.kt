package com.openlattice.chronicle.ui

import android.content.Context
import android.content.res.Configuration
import androidx.test.core.app.ApplicationProvider
import com.openlattice.chronicle.R
import com.openlattice.chronicle.services.upload.AuxiliaryPendingUploadCounts
import com.openlattice.chronicle.services.upload.LocalOperationalIssue
import com.openlattice.chronicle.services.upload.LocalUploadIssueBucket
import com.openlattice.chronicle.services.upload.LocalUploadModuleFamily
import com.openlattice.chronicle.services.upload.PendingUploadCounts
import com.openlattice.chronicle.services.upload.RestrictedPendingUploadCounts
import com.openlattice.chronicle.services.upload.UploadDestinationIssue
import com.openlattice.chronicle.services.upload.UploadFailureIssue
import com.openlattice.chronicle.storage.UploadStatsEntity
import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class UploadDiagnosticsSummaryTest {

    @Test
    fun pendingBreakdownPreservesCountsAndDistributionVisibility() {
        val pending = PendingUploadCounts(
            usageAndLifecycle = 1,
            sensorSamples = 2,
            batterySamples = 3,
            localParticipantLabels = 4,
            auxiliary = AuxiliaryPendingUploadCounts(
                connectivityState = 5,
                appNetworkUsage = 6,
                deviceSettings = 7,
                restricted = RestrictedPendingUploadCounts(
                    interactionEvents = 8,
                    audioActivity = 9,
                    audioContent = 10,
                    notificationActivity = 11,
                    sleep = 12,
                    activityRecognition = 13,
                    healthMetrics = 14,
                ),
            ),
        )
        val copy = ApplicationProvider.getApplicationContext<Context>().copyResolver()
        for (restricted in listOf(false, true)) {
            for (appNetwork in listOf(false, true)) {
                val text = renderPendingUploads(pending, restricted, appNetwork, copy)
                assertTrue(text.contains("Usage/lifecycle 1"))
                assertTrue(text.contains("Battery 3"))
                assertTrue(text.contains("Connectivity state 5"))
                assertTrue(text.contains("Device settings 7"))
                assertTrue(text.contains("Local participant labels 4 (context only; not an upload queue)"))
                assertEquals(restricted, text.contains("Sensors 2"))
                assertEquals(restricted, text.contains("Interaction events 8"))
                assertEquals(appNetwork, text.contains("App network usage 6"))
            }
        }
    }

    @Test
    fun recoveredFailureRemainsVisibleInCumulativeAndRecentLocalHistory() {
        val stats = UploadStatsEntity(
            serverId = 1L,
            date = "2026-08-22",
            usageEventsUploaded = 12,
            sensorSamplesUploaded = 9,
            batterySamplesUploaded = 2,
            usageUploadFailures = 1,
            sensorUploadFailures = 4,
        )
        val daily = formatDailyUploadStats(
            stats,
            includeRestricted = false,
        )
        val summary = renderUploadHistory(
            server = UploadServerSummary(
                id = 1L,
                name = "Example study server",
                url = "https://study.example.org",
                enabled = true,
                healthLabel = "Healthy",
                lastSuccess = "Aug 22, 12:00 PM",
                history = listOf(daily),
                usageItemsUploaded = 12,
                usageFailedAttempts = 1,
                batteryItemsUploaded = 2,
            ),
            includeRestricted = false,
        )

        assertTrue(summary.contains("Example study server - Healthy"))
        assertTrue(summary.contains("Delivered items: usage/lifecycle 12, battery 2"))
        assertTrue(summary.contains("Failed attempts: usage/lifecycle 1, battery 0"))
        assertTrue(summary.contains("failures 1/0"))
        assertFalse(summary.contains("sensor"))

        val researchDaily = formatDailyUploadStats(stats, includeRestricted = true)
        assertTrue(researchDaily.contains("9 sensors"))
        assertTrue(researchDaily.contains("failures 1/4/0"))
    }

    @Test
    fun recoveredDestinationFailureRendersOnlyClosedRedactedFields() {
        val rendered = renderLocalUploadIssues(
            listOf(
                LocalUploadIssueBucket(
                    day = "2026-08-22",
                    moduleFamily = LocalUploadModuleFamily.USAGE_LIFECYCLE.name,
                    issue = UploadDestinationIssue.DESTINATION_NONCANONICAL.name,
                    count = 2,
                ),
            ),
            ApplicationProvider.getApplicationContext<Context>().copyResolver(),
        )

        assertTrue(rendered.contains("2026-08-22: usage/lifecycle"))
        assertTrue(rendered.contains("destination address rejected (2)"))
        listOf("participant", "api-key", "https://", "Exception").forEach { forbidden ->
            assertFalse(rendered.contains(forbidden, ignoreCase = true))
        }
    }

    @Test
    fun everyModuleFamilyRendersItsResourceLabel() {
        val labels = mapOf(
            LocalUploadModuleFamily.USAGE_LIFECYCLE to R.string.uploads_issue_family_usage,
            LocalUploadModuleFamily.BATTERY to R.string.uploads_issue_family_battery,
            LocalUploadModuleFamily.DEVICE_TELEMETRY to R.string.uploads_issue_family_device,
            LocalUploadModuleFamily.SENSOR to R.string.uploads_issue_family_sensor,
            LocalUploadModuleFamily.APP_RUNTIME to R.string.uploads_issue_family_app_runtime,
            LocalUploadModuleFamily.INTERACTION to R.string.uploads_issue_family_interaction,
            LocalUploadModuleFamily.AUDIO_ACTIVITY to R.string.uploads_issue_family_audio_activity,
            LocalUploadModuleFamily.AUDIO_CONTENT to R.string.uploads_issue_family_audio_content,
            LocalUploadModuleFamily.NOTIFICATION to R.string.uploads_issue_family_notification,
            LocalUploadModuleFamily.SLEEP to R.string.uploads_issue_family_sleep,
            LocalUploadModuleFamily.ACTIVITY_RECOGNITION to R.string.uploads_issue_family_activity_recognition,
            LocalUploadModuleFamily.HEALTH to R.string.uploads_issue_family_health,
            LocalUploadModuleFamily.CONNECTIVITY to R.string.uploads_issue_family_connectivity,
            LocalUploadModuleFamily.APP_NETWORK to R.string.uploads_issue_family_app_network,
            LocalUploadModuleFamily.DEVICE_SETTINGS to R.string.uploads_issue_family_device_settings,
            LocalUploadModuleFamily.LOCAL_STORE to R.string.uploads_issue_family_local_store,
        )
        assertEquals(LocalUploadModuleFamily.entries.toSet(), labels.keys)
        labels.forEach { (family, resource) ->
            assertDiagnosticLabels(diagnosticBucket.copy(moduleFamily = family.name), resource,
                R.string.uploads_issue_collection_access_missing)
        }
    }

    @Test
    fun everyIssueRendersItsResourceLabel() {
        val labels = mapOf<Enum<*>, Int>(
            UploadDestinationIssue.DESTINATION_MISSING to R.string.uploads_issue_destination_missing,
            UploadDestinationIssue.DESTINATION_IDENTITY_MISMATCH to R.string.uploads_issue_identity_mismatch,
            UploadDestinationIssue.DESTINATION_SOURCE_DEVICE_MISSING to R.string.uploads_issue_source_device_missing,
            UploadDestinationIssue.DESTINATION_SETUP_INCOMPLETE to R.string.uploads_issue_setup_incomplete,
            UploadDestinationIssue.DESTINATION_DISABLED to R.string.uploads_issue_disabled,
            UploadDestinationIssue.DESTINATION_NONCANONICAL to R.string.uploads_issue_noncanonical,
            UploadDestinationIssue.DESTINATION_CREDENTIAL_INCOMPLETE to R.string.uploads_issue_credential_incomplete,
            LocalOperationalIssue.APP_CRASH to R.string.uploads_issue_app_crash,
            LocalOperationalIssue.APP_CRASH_NATIVE to R.string.uploads_issue_app_crash_native,
            LocalOperationalIssue.APP_ANR to R.string.uploads_issue_app_anr,
            LocalOperationalIssue.SAMPLE_QUARANTINED to R.string.uploads_issue_sample_quarantined,
            LocalOperationalIssue.LOCAL_BUFFER_OVERFLOW to R.string.uploads_issue_local_buffer_overflow,
            LocalOperationalIssue.LOCAL_REQUEUE_OVERFLOW to R.string.uploads_issue_local_requeue_overflow,
            LocalOperationalIssue.LOCAL_WRITE_FAILED to R.string.uploads_issue_local_write_failed,
            LocalOperationalIssue.LOCAL_SHUTDOWN_DROPPED to R.string.uploads_issue_local_shutdown_dropped,
            LocalOperationalIssue.COLLECTION_GATE_DROPPED to R.string.uploads_issue_collection_gate_dropped,
            LocalOperationalIssue.MODULE_POLICY_ERASED to R.string.uploads_issue_module_policy_erased,
            LocalOperationalIssue.DIRECT_BOOT_CAPACITY_DROPPED to R.string.uploads_issue_direct_boot_capacity_dropped,
            LocalOperationalIssue.DIRECT_BOOT_CORRUPT_RECORD to R.string.uploads_issue_direct_boot_corrupt_record,
            LocalOperationalIssue.COLLECTION_PAUSED_STORAGE to R.string.uploads_issue_collection_paused_storage,
            LocalOperationalIssue.COLLECTION_ACCESS_MISSING to R.string.uploads_issue_collection_access_missing,
            UploadFailureIssue.HTTP_SERVER_ERROR to R.string.uploads_issue_http_server_error,
            UploadFailureIssue.HTTP_CLIENT_ERROR to R.string.uploads_issue_http_client_error,
            UploadFailureIssue.TIMEOUT to R.string.uploads_issue_timeout,
            UploadFailureIssue.DNS_FAILURE to R.string.uploads_issue_dns_failure,
            UploadFailureIssue.TLS_FAILURE to R.string.uploads_issue_tls_failure,
            UploadFailureIssue.CONNECTION_FAILURE to R.string.uploads_issue_connection_failure,
            UploadFailureIssue.UPLOAD_FAILURE to R.string.uploads_issue_upload_failure,
        )
        assertEquals((UploadDestinationIssue.entries + LocalOperationalIssue.entries + UploadFailureIssue.entries).toSet(),
            labels.keys)
        labels.forEach { (issue, resource) ->
            assertDiagnosticLabels(diagnosticBucket.copy(issue = issue.name), R.string.uploads_issue_family_usage, resource)
        }
    }

    @Test
    fun unrecognizedDiagnosticsRenderUnknownResourcesWithoutExposingStoredValues() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        for (unrecognized in listOf("unrecognized-private-input", "", "usage_lifecycle", " TIMEOUT ")) {
            val bucket = diagnosticBucket.copy(moduleFamily = unrecognized, issue = unrecognized)
            assertDiagnosticLabels(bucket, R.string.uploads_issue_family_unknown, R.string.uploads_issue_unknown)
            val rendered = renderLocalUploadIssues(listOf(bucket), context.copyResolver())
            if (unrecognized.isNotEmpty()) assertFalse(rendered.contains(unrecognized))
        }
    }

    private val diagnosticBucket = LocalUploadIssueBucket(
        day = "2026-10-17",
        moduleFamily = LocalUploadModuleFamily.USAGE_LIFECYCLE.name,
        issue = LocalOperationalIssue.COLLECTION_ACCESS_MISSING.name,
        count = 1,
    )

    private fun assertDiagnosticLabels(bucket: LocalUploadIssueBucket, familyResource: Int, issueResource: Int) {
        val context = ApplicationProvider.getApplicationContext<Context>()
        for (overridesEnabled in listOf(false, true)) {
            val copy: CopyResolver = { id, args ->
                val text = context.getString(id, *args)
                if (overridesEnabled && (id == familyResource || id == issueResource)) "[$text]" else text
            }
            val rendered = renderLocalUploadIssues(listOf(bucket), copy)
            val family = copy(familyResource, emptyArray())
            val issue = copy(issueResource, emptyArray())
            val line = context.getString(R.string.uploads_issue_line, bucket.day, family, issue, bucket.count)
            assertEquals(context.getString(R.string.uploads_issues_header, line), rendered)
            if (familyResource != R.string.uploads_issue_family_unknown && issueResource != R.string.uploads_issue_unknown) {
                assertFalse(rendered.contains("unknown"))
            }
        }
    }

    // Translations are supplied by study teams, never authored here: an untranslated locale must fall
    // back to the complete base variants rather than a partial or mismatched one.
    @Test fun untranslatedLocaleFallsBackToCompleteBaseVariantsAndPreservesTheHealthLabel() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val config = Configuration(context.resources.configuration).apply { setLocale(Locale.forLanguageTag("sv")) }
        val localized = context.createConfigurationContext(config)
        val server = UploadServerSummary(1, "Estudio", "https://localhost", true, "Conexión Saludable", null, emptyList())
        for (restricted in listOf(false, true)) {
            val text = renderUploadHistory(server, restricted, localized.copyResolver())
            assertTrue(text.contains("Estudio - Conexión Saludable"))
            assertTrue(text.contains("Recent daily history"))
            assertTrue(text.contains("No recent uploads"))
            assertEquals(restricted, text.contains("sensors"))
        }
    }
}
