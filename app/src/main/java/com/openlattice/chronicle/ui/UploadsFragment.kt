package com.openlattice.chronicle.ui

import android.os.Bundle
import android.view.View
import android.widget.TextView
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import com.google.android.material.button.MaterialButton
import com.openlattice.chronicle.BuildConfig
import com.openlattice.chronicle.R
import com.openlattice.chronicle.padForSystemBars
import com.openlattice.chronicle.services.sync.triggerImmediateChronicleSync
import com.openlattice.chronicle.services.upload.LocalUploadIssueBucket
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

class UploadsFragment : Fragment(R.layout.fragment_uploads) {
    private var refreshJob: Job? = null

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        view.findViewById<View>(R.id.uploadsContent).padForSystemBars()
        view.findViewById<MaterialButton>(R.id.uploadNowButton).setOnClickListener { button ->
            triggerImmediateChronicleSync(requireContext().applicationContext)
            Toast.makeText(requireContext(), R.string.upload_queued, Toast.LENGTH_SHORT).show()
            button.isEnabled = false
            viewLifecycleOwner.lifecycleScope.launch {
                delay(5_000L)
                button.isEnabled = true
            }
        }
    }

    override fun onResume() {
        super.onResume()
        refreshJob = viewLifecycleOwner.lifecycleScope.launch {
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
        val auxiliary = pending.auxiliary
        val restrictedBreakdown = if (BuildConfig.ALLOW_RESTRICTED_RESEARCH_PERMISSIONS) {
            auxiliary.restricted?.let { restricted ->
                getString(
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
        } else {
            ""
        }
        val succeeded = snapshot.uploads.succeededToday
        val failed = snapshot.uploads.failedToday
        val sensorPending = if (BuildConfig.ALLOW_RESTRICTED_RESEARCH_PERMISSIONS) {
            getString(R.string.uploads_pending_sensors, pending.sensorSamples)
        } else {
            ""
        }
        val appNetworkPending = if (BuildConfig.HAS_APP_NETWORK_USAGE) {
            getString(R.string.uploads_pending_app_network, auxiliary.appNetworkUsage)
        } else {
            ""
        }
        val successfulUploads = if (BuildConfig.ALLOW_RESTRICTED_RESEARCH_PERMISSIONS) {
            getString(R.string.uploads_counts_research, succeeded.usageAndLifecycle, succeeded.sensorSamples, succeeded.batterySamples)
        } else {
            getString(R.string.uploads_counts, succeeded.usageAndLifecycle, succeeded.batterySamples)
        }
        val failedUploads = if (BuildConfig.ALLOW_RESTRICTED_RESEARCH_PERMISSIONS) {
            getString(R.string.uploads_counts_research, failed.usageAttempts, failed.sensorAttempts, failed.batteryAttempts)
        } else {
            getString(R.string.uploads_counts, failed.usageAttempts, failed.batteryAttempts)
        }
        view.findViewById<TextView>(R.id.uploadsRemaining).text =
            getString(R.string.uploads_remaining, pending.total)
        view.findViewById<TextView>(R.id.uploadsBreakdown).text =
            getString(
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
        view.findViewById<TextView>(R.id.uploadsToday).text =
            getString(R.string.uploads_today, successfulUploads, failedUploads)
        val serverHistory = snapshot.servers.joinToString("\n\n") { server ->
                renderUploadHistory(
                    server = server,
                    includeRestricted = BuildConfig.ALLOW_RESTRICTED_RESEARCH_PERMISSIONS,
                    copy = requireContext().copyResolver(),
                )
            }
        val localIssues = renderLocalUploadIssues(snapshot.localUploadIssues, requireContext().copyResolver())
        view.findViewById<TextView>(R.id.uploadsHistory).text = listOf(serverHistory, localIssues)
            .filter(String::isNotBlank)
            .joinToString("\n\n")
            .ifBlank { getString(R.string.uploads_none_configured) }
    }
}

/**
 * Device-local retrospective upload health. Cumulative counters remain visible after a
 * later successful retry clears the current error state; the bounded daily history adds
 * timing context. This string is rendered only in [UploadsFragment] and is never queued.
 */
private val ENGLISH_UPLOADS: CopyResolver = englishCopy(
    mapOf(
        R.string.uploads_history_no_recent to "No recent uploads",
        R.string.uploads_history_sensors_delivered to ", sensors %d",
        R.string.uploads_history_sensors_failed to ", sensors %d",
        R.string.uploads_history to "%s - %s\nRecorded since enrollment\nDelivered items: usage/lifecycle %d%s, battery %d\n" +
            "Failed attempts: usage/lifecycle %d%s, battery %d\nRecent daily history\n%s",
        R.string.uploads_issue_family_usage to "usage/lifecycle",
        R.string.uploads_issue_family_battery to "battery",
        R.string.uploads_issue_family_device to "device telemetry",
        R.string.uploads_issue_family_unknown to "unknown module",
        R.string.uploads_issue_destination_missing to "destination missing",
        R.string.uploads_issue_identity_mismatch to "destination identity mismatch",
        R.string.uploads_issue_source_device_missing to "source device missing",
        R.string.uploads_issue_setup_incomplete to "enrollment setup incomplete",
        R.string.uploads_issue_disabled to "destination disabled",
        R.string.uploads_issue_noncanonical to "destination address rejected",
        R.string.uploads_issue_credential_incomplete to "destination credential incomplete",
        R.string.uploads_issue_unknown to "unknown destination issue",
        R.string.uploads_issue_line to "%s: %s — %s (%d)",
        R.string.uploads_issues_header to "Local destination checks (last 7 days)\n%s",
    ),
)

internal fun renderUploadHistory(
    server: UploadServerSummary,
    includeRestricted: Boolean,
    copy: CopyResolver = ENGLISH_UPLOADS,
): String {
    fun s(id: Int, vararg args: Any) = copy(id, args)
    val sensorDelivered = if (includeRestricted) s(R.string.uploads_history_sensors_delivered, server.sensorItemsUploaded) else ""
    val sensorFailures = if (includeRestricted) s(R.string.uploads_history_sensors_failed, server.sensorFailedAttempts) else ""
    val recent = server.history.takeIf { it.isNotEmpty() }?.joinToString("\n")
        ?: s(R.string.uploads_history_no_recent)
    return s(
        R.string.uploads_history,
        server.name,
        server.healthLabel,
        server.usageItemsUploaded,
        sensorDelivered,
        server.batteryItemsUploaded,
        server.usageFailedAttempts,
        sensorFailures,
        server.batteryFailedAttempts,
        recent,
    )
}

internal fun renderLocalUploadIssues(
    buckets: List<LocalUploadIssueBucket>,
    copy: CopyResolver = ENGLISH_UPLOADS,
): String {
    fun s(id: Int, vararg args: Any) = copy(id, args)
    if (buckets.isEmpty()) return ""
    val lines = buckets.joinToString("\n") { bucket ->
        val family = s(
            when (bucket.moduleFamily) {
                "USAGE_LIFECYCLE" -> R.string.uploads_issue_family_usage
                "BATTERY" -> R.string.uploads_issue_family_battery
                "DEVICE_TELEMETRY" -> R.string.uploads_issue_family_device
                else -> R.string.uploads_issue_family_unknown
            },
        )
        val issue = s(
            when (bucket.issue) {
                "DESTINATION_MISSING" -> R.string.uploads_issue_destination_missing
                "DESTINATION_IDENTITY_MISMATCH" -> R.string.uploads_issue_identity_mismatch
                "DESTINATION_SOURCE_DEVICE_MISSING" -> R.string.uploads_issue_source_device_missing
                "DESTINATION_SETUP_INCOMPLETE" -> R.string.uploads_issue_setup_incomplete
                "DESTINATION_DISABLED" -> R.string.uploads_issue_disabled
                "DESTINATION_NONCANONICAL" -> R.string.uploads_issue_noncanonical
                "DESTINATION_CREDENTIAL_INCOMPLETE" -> R.string.uploads_issue_credential_incomplete
                else -> R.string.uploads_issue_unknown
            },
        )
        s(R.string.uploads_issue_line, bucket.day, family, issue, bucket.count)
    }
    return s(R.string.uploads_issues_header, lines)
}
