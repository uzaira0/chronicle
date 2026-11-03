package com.openlattice.chronicle.ui

import android.os.Bundle
import android.util.Log
import android.view.View
import android.widget.TextView
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import com.google.android.material.button.MaterialButton
import com.openlattice.chronicle.BuildConfig
import com.openlattice.chronicle.R
import com.openlattice.chronicle.services.sync.triggerImmediateChronicleSync
import com.openlattice.chronicle.services.upload.LocalOperationalIssue
import com.openlattice.chronicle.services.upload.LocalUploadIssueBucket
import com.openlattice.chronicle.services.upload.LocalUploadModuleFamily
import com.openlattice.chronicle.services.upload.PendingUploadCounts
import com.openlattice.chronicle.services.upload.UploadDestinationIssue
import com.openlattice.chronicle.services.upload.UploadFailureIssue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class UploadsFragment : Fragment(R.layout.fragment_uploads) {
    private var refreshJob: Job? = null

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        view.findViewById<MaterialButton>(R.id.uploadsDetailsButton).setOnClickListener {
            val details = view.findViewById<View>(R.id.uploadsDetails)
            details.visibility = if (details.visibility == View.VISIBLE) View.GONE else View.VISIBLE
        }
        view.findViewById<MaterialButton>(R.id.uploadNowButton).setOnClickListener { button ->
            val appContext = requireContext().applicationContext
            button.isEnabled = false
            viewLifecycleOwner.lifecycleScope.launch(storageFailureHandler()) {
                val queued = withContext(Dispatchers.IO) {
                    runCatching { triggerImmediateChronicleSync(appContext).result.get() }
                        .onFailure { Log.w("UploadsFragment", "Upload enqueue failed", it) }
                        .isSuccess
                }
                context?.let {
                    Toast.makeText(it, if (queued) R.string.upload_queued else R.string.upload_queue_failed, Toast.LENGTH_SHORT).show()
                }
                delay(5_000L)
                button.isEnabled = true
            }
        }
    }

    override fun onResume() {
        super.onResume()
        refreshJob = viewLifecycleOwner.lifecycleScope.launch(storageFailureHandler()) {
            while (true) {
                val snapshot = DashboardDataRepository.load(requireContext())
                view?.let { bind(it, snapshot) }
                delay(DASHBOARD_REFRESH_MS)
            }
        }
    }

    override fun onPause() {
        refreshJob?.cancel()
        refreshJob = null
        super.onPause()
    }

    private fun bind(view: View, snapshot: DashboardSnapshot) {
        val pending = snapshot.uploads.pending
        val succeeded = snapshot.uploads.succeededToday
        val failed = snapshot.uploads.failedToday
        val successfulUploads = uploadCounts(succeeded.usageAndLifecycle, succeeded.sensorSamples, succeeded.batterySamples)
        val failedUploads = uploadCounts(failed.usageAttempts, failed.sensorAttempts, failed.batteryAttempts)
        view.findViewById<TextView>(R.id.uploadsRemaining).text =
            getString(R.string.uploads_remaining, pending.total)
        view.findViewById<TextView>(R.id.uploadsBreakdown).text =
            renderPendingUploads(
                pending = pending,
                includeRestricted = BuildConfig.ALLOW_RESTRICTED_RESEARCH_PERMISSIONS,
                includeAppNetwork = BuildConfig.HAS_APP_NETWORK_USAGE,
                copy = requireContext().copyResolver(),
            )
        view.findViewById<TextView>(R.id.uploadsToday).text =
            getString(R.string.uploads_today, successfulUploads, failedUploads)
        val serverHistory = snapshot.servers.joinToString("\n\n") { server ->
                renderUploadHistory(
                    server = server,
                    includeRestricted = BuildConfig.ALLOW_RESTRICTED_RESEARCH_PERMISSIONS,
                    copy = requireContext().copyResolver(),
                )
            }
        val localIssues = if (snapshot.localUploadDiagnosticsAvailable) {
            renderLocalUploadIssues(snapshot.localUploadIssues, requireContext().copyResolver())
        } else getString(R.string.upload_diagnostics_unavailable)
        val scheduling = if (snapshot.workSchedulingAvailable) "" else getString(R.string.work_scheduling_unavailable)
        view.findViewById<TextView>(R.id.uploadsHistory).text = listOf(scheduling, serverHistory, localIssues)
            .filter(String::isNotBlank)
            .joinToString("\n\n")
            .ifBlank { getString(R.string.uploads_none_configured) }
    }

    private fun uploadCounts(usage: Int, sensors: Int, battery: Int): String =
        if (BuildConfig.ALLOW_RESTRICTED_RESEARCH_PERMISSIONS) {
            getString(R.string.uploads_counts_research, usage, sensors, battery)
        } else {
            getString(R.string.uploads_counts, usage, battery)
        }
}

internal fun renderPendingUploads(
    pending: PendingUploadCounts,
    includeRestricted: Boolean,
    includeAppNetwork: Boolean,
    copy: CopyResolver,
): String {
    fun s(id: Int, vararg args: Any) = copy(id, args)
    val auxiliary = pending.auxiliary
    val restrictedBreakdown = if (includeRestricted) {
        auxiliary.restricted?.let { restricted ->
            s(
                R.string.uploads_restricted_breakdown,
                restricted.interactionEvents,
                restricted.audioActivity,
                restricted.audioContent,
                restricted.notificationActivity,
                restricted.sleep,
                restricted.activityRecognition,
                restricted.healthMetrics,
            )
        }.orEmpty()
    } else ""
    val sensorPending = if (includeRestricted) s(R.string.uploads_pending_sensors, pending.sensorSamples) else ""
    val appNetworkPending = if (includeAppNetwork) s(R.string.uploads_pending_app_network, auxiliary.appNetworkUsage) else ""
    return s(
        R.string.uploads_breakdown,
        pending.usageAndLifecycle,
        sensorPending,
        pending.batterySamples,
        restrictedBreakdown,
        auxiliary.connectivityState,
        appNetworkPending,
        auxiliary.deviceSettings,
        pending.localParticipantLabels,
    )
}

/**
 * Device-local retrospective upload health. Cumulative counters remain visible after a
 * later successful retry clears the current error state; the bounded daily history adds
 * timing context. This string is rendered only in [UploadsFragment] and is never queued.
 */
private val ENGLISH_UPLOADS: CopyResolver = englishCopy(
    mapOf(
        R.string.uploads_history_no_recent to "No recent uploads",
        R.string.uploads_history to "%s - %s\nRecorded since enrollment\nDelivered items: usage/lifecycle %d, battery %d\n" +
            "Failed attempts: usage/lifecycle %d, battery %d\nRecent daily history\n%s",
        R.string.uploads_history_research to "%s - %s\nRecorded since enrollment\n" +
            "Delivered items: usage/lifecycle %d, sensors %d, battery %d\n" +
            "Failed attempts: usage/lifecycle %d, sensors %d, battery %d\nRecent daily history\n%s",
    ),
)

internal fun renderUploadHistory(
    server: UploadServerSummary,
    includeRestricted: Boolean,
    copy: CopyResolver = ENGLISH_UPLOADS,
): String {
    fun s(id: Int, vararg args: Any) = copy(id, args)
    val recent = server.history.takeIf { it.isNotEmpty() }?.joinToString("\n")
        ?: s(R.string.uploads_history_no_recent)
    return if (includeRestricted) s(
        R.string.uploads_history_research, server.name, server.healthLabel,
        server.usageItemsUploaded, server.sensorItemsUploaded, server.batteryItemsUploaded,
        server.usageFailedAttempts, server.sensorFailedAttempts, server.batteryFailedAttempts, recent,
    ) else s(
        R.string.uploads_history, server.name, server.healthLabel,
        server.usageItemsUploaded, server.batteryItemsUploaded,
        server.usageFailedAttempts, server.batteryFailedAttempts, recent,
    )
}

internal fun renderLocalUploadIssues(
    buckets: List<LocalUploadIssueBucket>,
    copy: CopyResolver,
): String {
    fun s(id: Int, vararg args: Any) = copy(id, args)
    if (buckets.isEmpty()) return ""
    val lines = buckets.joinToString("\n") { bucket ->
        val family = s(LOCAL_UPLOAD_FAMILIES_BY_NAME[bucket.moduleFamily].labelResource())
        val issue = s(LOCAL_UPLOAD_ISSUES_BY_NAME[bucket.issue].labelResource())
        s(R.string.uploads_issue_line, bucket.day, family, issue, bucket.count)
    }
    return s(R.string.uploads_issues_header, lines)
}

private val LOCAL_UPLOAD_FAMILIES_BY_NAME = LocalUploadModuleFamily.entries.associateBy { it.name }

private sealed interface LocalUploadIssueLabel {
    data class Destination(val issue: UploadDestinationIssue) : LocalUploadIssueLabel
    data class Operational(val issue: LocalOperationalIssue) : LocalUploadIssueLabel
    data class Failure(val issue: UploadFailureIssue) : LocalUploadIssueLabel
}

private val LOCAL_UPLOAD_ISSUES_BY_NAME = buildMap<String, LocalUploadIssueLabel> {
    UploadDestinationIssue.entries.forEach { put(it.name, LocalUploadIssueLabel.Destination(it)) }
    LocalOperationalIssue.entries.forEach { put(it.name, LocalUploadIssueLabel.Operational(it)) }
    UploadFailureIssue.entries.forEach { put(it.name, LocalUploadIssueLabel.Failure(it)) }
}

private fun LocalUploadModuleFamily?.labelResource(): Int = when (this) {
    LocalUploadModuleFamily.USAGE_LIFECYCLE -> R.string.uploads_issue_family_usage
    LocalUploadModuleFamily.BATTERY -> R.string.uploads_issue_family_battery
    LocalUploadModuleFamily.DEVICE_TELEMETRY -> R.string.uploads_issue_family_device
    LocalUploadModuleFamily.SENSOR -> R.string.uploads_issue_family_sensor
    LocalUploadModuleFamily.APP_RUNTIME -> R.string.uploads_issue_family_app_runtime
    LocalUploadModuleFamily.INTERACTION -> R.string.uploads_issue_family_interaction
    LocalUploadModuleFamily.AUDIO_ACTIVITY -> R.string.uploads_issue_family_audio_activity
    LocalUploadModuleFamily.AUDIO_CONTENT -> R.string.uploads_issue_family_audio_content
    LocalUploadModuleFamily.NOTIFICATION -> R.string.uploads_issue_family_notification
    LocalUploadModuleFamily.SLEEP -> R.string.uploads_issue_family_sleep
    LocalUploadModuleFamily.ACTIVITY_RECOGNITION -> R.string.uploads_issue_family_activity_recognition
    LocalUploadModuleFamily.HEALTH -> R.string.uploads_issue_family_health
    LocalUploadModuleFamily.CONNECTIVITY -> R.string.uploads_issue_family_connectivity
    LocalUploadModuleFamily.APP_NETWORK -> R.string.uploads_issue_family_app_network
    LocalUploadModuleFamily.DEVICE_SETTINGS -> R.string.uploads_issue_family_device_settings
    LocalUploadModuleFamily.LOCAL_STORE -> R.string.uploads_issue_family_local_store
    null -> R.string.uploads_issue_family_unknown
}

private fun LocalUploadIssueLabel?.labelResource(): Int = when (this) {
    is LocalUploadIssueLabel.Destination -> when (issue) {
        UploadDestinationIssue.DESTINATION_MISSING -> R.string.uploads_issue_destination_missing
        UploadDestinationIssue.DESTINATION_IDENTITY_MISMATCH -> R.string.uploads_issue_identity_mismatch
        UploadDestinationIssue.DESTINATION_SOURCE_DEVICE_MISSING -> R.string.uploads_issue_source_device_missing
        UploadDestinationIssue.DESTINATION_SETUP_INCOMPLETE -> R.string.uploads_issue_setup_incomplete
        UploadDestinationIssue.DESTINATION_DISABLED -> R.string.uploads_issue_disabled
        UploadDestinationIssue.DESTINATION_NONCANONICAL -> R.string.uploads_issue_noncanonical
        UploadDestinationIssue.DESTINATION_CREDENTIAL_INCOMPLETE -> R.string.uploads_issue_credential_incomplete
    }
    is LocalUploadIssueLabel.Operational -> when (issue) {
        LocalOperationalIssue.APP_CRASH -> R.string.uploads_issue_app_crash
        LocalOperationalIssue.APP_CRASH_NATIVE -> R.string.uploads_issue_app_crash_native
        LocalOperationalIssue.APP_ANR -> R.string.uploads_issue_app_anr
        LocalOperationalIssue.SAMPLE_QUARANTINED -> R.string.uploads_issue_sample_quarantined
        LocalOperationalIssue.LOCAL_BUFFER_OVERFLOW -> R.string.uploads_issue_local_buffer_overflow
        LocalOperationalIssue.LOCAL_REQUEUE_OVERFLOW -> R.string.uploads_issue_local_requeue_overflow
        LocalOperationalIssue.LOCAL_WRITE_FAILED -> R.string.uploads_issue_local_write_failed
        LocalOperationalIssue.LOCAL_SHUTDOWN_DROPPED -> R.string.uploads_issue_local_shutdown_dropped
        LocalOperationalIssue.COLLECTION_GATE_DROPPED -> R.string.uploads_issue_collection_gate_dropped
        LocalOperationalIssue.MODULE_POLICY_ERASED -> R.string.uploads_issue_module_policy_erased
        LocalOperationalIssue.DIRECT_BOOT_CAPACITY_DROPPED -> R.string.uploads_issue_direct_boot_capacity_dropped
        LocalOperationalIssue.DIRECT_BOOT_CORRUPT_RECORD -> R.string.uploads_issue_direct_boot_corrupt_record
        LocalOperationalIssue.COLLECTION_PAUSED_STORAGE -> R.string.uploads_issue_collection_paused_storage
        LocalOperationalIssue.COLLECTION_ACCESS_MISSING -> R.string.uploads_issue_collection_access_missing
    }
    is LocalUploadIssueLabel.Failure -> when (issue) {
        UploadFailureIssue.HTTP_SERVER_ERROR -> R.string.uploads_issue_http_server_error
        UploadFailureIssue.HTTP_CLIENT_ERROR -> R.string.uploads_issue_http_client_error
        UploadFailureIssue.TIMEOUT -> R.string.uploads_issue_timeout
        UploadFailureIssue.DNS_FAILURE -> R.string.uploads_issue_dns_failure
        UploadFailureIssue.TLS_FAILURE -> R.string.uploads_issue_tls_failure
        UploadFailureIssue.CONNECTION_FAILURE -> R.string.uploads_issue_connection_failure
        UploadFailureIssue.UPLOAD_FAILURE -> R.string.uploads_issue_upload_failure
    }
    null -> R.string.uploads_issue_unknown
}
