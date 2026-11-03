package com.openlattice.chronicle.ui

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import com.openlattice.chronicle.BuildConfig
import com.openlattice.chronicle.R
import com.openlattice.chronicle.android.AndroidSensorType
import com.openlattice.chronicle.api.MobileEnrollmentManifest
import com.openlattice.chronicle.collection.DistributionRestrictedRuntime
import com.openlattice.chronicle.collection.state.CollectionLoopStore
import com.openlattice.chronicle.collection.state.CollectionModulePhase
import com.openlattice.chronicle.collection.state.CollectionModuleState
import com.openlattice.chronicle.data.ParticipationStatus
import com.openlattice.chronicle.preferences.EnrollmentSettings
import com.openlattice.chronicle.preferences.INVALID_STUDY_ID
import com.openlattice.chronicle.preferences.SensorSettings
import com.openlattice.chronicle.collection.capability.CollectionCapabilityResolver
import com.openlattice.chronicle.serialization.ChronicleJson
import com.openlattice.chronicle.services.upload.LocalUploadDiagnosticsStore
import com.openlattice.chronicle.services.upload.LocalUploadIssueBucket
import com.openlattice.chronicle.services.upload.PendingUploadCounter
import com.openlattice.chronicle.services.upload.UploadDashboardSnapshot
import com.openlattice.chronicle.services.withdrawal.WithdrawalState
import com.openlattice.chronicle.services.withdrawal.WithdrawalStateStore
import com.openlattice.chronicle.storage.ChronicleDb
import com.openlattice.chronicle.storage.ServerHealthStatus
import com.openlattice.chronicle.storage.UploadServerEntity
import com.openlattice.chronicle.storage.UploadStatsEntity
import com.openlattice.chronicle.storage.healthStatus
import com.openlattice.chronicle.utils.Utils
import java.time.LocalDate
import java.time.OffsetDateTime
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import android.util.Log
import com.openlattice.chronicle.WorkSchedulingStatus
import com.openlattice.chronicle.collection.state.StorageAdmission

const val DASHBOARD_REFRESH_MS = 5_000L

data class DashboardSnapshot(
    val studyId: UUID?,
    val participantId: String,
    val lastUpload: String,
    val latestTimestampUploaded: String,
    val uploads: UploadDashboardSnapshot,
    val collection: CollectionStatusSummary,
    val collectionModules: List<CollectionModuleState>,
    val sensors: SensorDashboardSummary,
    val serverHealth: ServerHealthSummary,
    val servers: List<UploadServerSummary>,
    val localUploadIssues: List<LocalUploadIssueBucket>,
    val localUploadDiagnosticsAvailable: Boolean = true,
    val workSchedulingAvailable: Boolean = true,
    val participationStop: ParticipationStop? = null,
)

data class StopCopy(
    val participant: Int,
    val status: Int,
    val serverHealth: Int? = null,
    val uploadsLabel: Int? = null,
)

/**
 * Screens describe stopped collection instead of module consents or collection counts.
 * Paused and completed participation still drains queued uploads and reports their health.
 */
enum class ParticipationStop(val copy: StopCopy) {
    WITHDRAWAL_PENDING(StopCopy(
        participant = R.string.overview_participant_withdrawal_pending,
        status = R.string.participation_status_withdrawal_pending,
        serverHealth = R.string.server_health_withdrawal_pending,
        uploadsLabel = R.string.summary_uploads_withdrawal_pending,
    )),
    ENDED(StopCopy(
        participant = R.string.overview_participant_ended,
        status = R.string.participation_status_ended,
        serverHealth = R.string.server_health_not_enrolled,
        uploadsLabel = R.string.summary_uploads_stopped,
    )),
    COMPLETED(StopCopy(
        participant = R.string.overview_participant_completed,
        status = R.string.participation_status_completed,
    )),
    PAUSED(StopCopy(
        participant = R.string.overview_participant_paused,
        status = R.string.participation_status_paused,
    )),
    /** No enrollment on this device: before the first one, or after a completed withdrawal. */
    NOT_ENROLLED(StopCopy(
        participant = R.string.overview_participant_not_enrolled,
        status = R.string.participation_status_not_enrolled,
        serverHealth = R.string.uploads_none_configured,
        uploadsLabel = R.string.summary_uploads_not_configured,
    )),
    ;

    companion object {
        fun of(status: ParticipationStatus): ParticipationStop? = when (status) {
            ParticipationStatus.NOT_ENROLLED -> ENDED
            ParticipationStatus.COLLECTION_COMPLETED -> COMPLETED
            ParticipationStatus.PAUSED -> PAUSED
            else -> null
        }
    }
}

data class CollectionStatusSummary(
    val active: Int,
    val waitingReview: Int,
    val disabled: Int,
    val message: String,
)

data class SensorDashboardSummary(
    val configured: List<AndroidSensorType>,
    val enabled: List<AndroidSensorType>,
    val disabled: List<AndroidSensorType>,
    val available: List<AndroidSensorType>,
    val pendingByType: Map<String, Int>,
    // Per-sensor study config (per-sensor consent redesign): each configured sensor's own
    // sampling rate + duty cycle, for read-only display in the Data Sharing sensor rows.
    val rateHzByType: Map<AndroidSensorType, Int>,
    val activeSecondsByType: Map<AndroidSensorType, Int>,
    val periodSecondsByType: Map<AndroidSensorType, Int>,
    val lastUpload: String?,
    val serviceRunning: Boolean,
)

data class ServerHealthSummary(
    val serverCount: Int,
    val message: String,
)

data class UploadServerSummary(
    val id: Long,
    val name: String,
    val url: String,
    val enabled: Boolean,
    val healthLabel: String,
    val lastSuccess: String?,
    val history: List<String>,
    val usageItemsUploaded: Int = 0,
    val usageFailedAttempts: Int = 0,
    val sensorItemsUploaded: Int = 0,
    val sensorFailedAttempts: Int = 0,
    val batteryItemsUploaded: Int = 0,
    val batteryFailedAttempts: Int = 0,
    val responsibleInstitution: String? = null,
    val researchContact: String? = null,
    val privacyPolicyUrl: String? = null,
    val disclosureVersion: String? = null,
    val withdrawalUrl: String? = null,
    val policyEffectiveAt: String? = null,
    val participationStop: ParticipationStop? = null,
) {
    val uploadsLabel: Int
        get() = participationStop?.copy?.uploadsLabel
            ?: if (enabled) R.string.summary_uploads_active else R.string.summary_uploads_stopped
}

object DashboardDataRepository {
    suspend fun load(context: Context): DashboardSnapshot = withContext(Dispatchers.IO) {
        val appContext = context.applicationContext
        val enrollment = EnrollmentSettings(appContext)
        val enrolledStudy = enrollment.getStudyId().takeIf { it != INVALID_STUDY_ID }
        val participationStop = participationStop(appContext, enrollment, enrolledStudy)
        val db = ChronicleDb.getInstance(appContext)
        val uploadDashboard = PendingUploadCounter.dashboardSnapshot(appContext, LocalDate.now())
        val servers = listOfNotNull(db.uploadServerDao().getConfiguredServer())
        val online = hasValidatedInternet(appContext)
        val latestServerUpload = latestUploadSuccessTime(
            servers.flatMap { server ->
                listOf(
                    server.lastUsageUploadSuccessTime,
                    server.lastSensorUploadSuccessTime,
                    server.lastBatteryUploadSuccessTime,
                )
            },
        )

        val diagnostics = try {
            LocalUploadDiagnosticsStore.of(appContext).recent()
        } catch (error: Exception) {
            Log.e("DashboardDataRepository", "Upload diagnostics unavailable; legacy import will retry", error)
            null
        }
        DashboardSnapshot(
            studyId = enrolledStudy,
            participantId = enrollment.getParticipantId(),
            // Every uploader records its success on UploadServerEntity. The encrypted-pref
            // timestamp is legacy usage-only state, so using it alone made Overview say
            // "Never" while the Uploads tab showed successful sensor/battery deliveries.
            lastUpload = latestServerUpload?.let(Utils::formatAsMediumDateTime)
                ?: Utils.getLastUpload(appContext),
            latestTimestampUploaded = Utils.getLatestTimestampUploaded(appContext),
            uploads = uploadDashboard,
            collection = participationStop?.let { CollectionStatusSummary(0, 0, 0, appContext.getString(it.copy.status)) }
                ?: loadCollectionSummary(appContext),
            // An unreadable module table fails the load (StorageFailureBoundary), never "no modules".
            collectionModules = CollectionLoopStore.of(appContext).loadAll().values.toList(),
            sensors = loadSensorSummary(appContext, db),
            serverHealth = loadServerHealth(appContext, servers, online, participationStop),
            participationStop = participationStop,
            localUploadIssues = diagnostics.orEmpty(),
            localUploadDiagnosticsAvailable = diagnostics != null,
            workSchedulingAvailable = !WorkSchedulingStatus.unavailable,
            servers = servers.map { server ->
                loadServerSummary(
                    context = appContext,
                    db = db,
                    server = server,
                    health = loadServerHealth(appContext, listOf(server), online, participationStop),
                    participationStop = participationStop,
                )
            },
        )
    }

    private fun participationStop(context: Context, enrollment: EnrollmentSettings, studyId: UUID?): ParticipationStop? {
        val withdrawal = WithdrawalStateStore(context).state()
        return when {
            withdrawal == WithdrawalState.PENDING || withdrawal == WithdrawalState.NEEDS_SUPPORT -> ParticipationStop.WITHDRAWAL_PENDING
            studyId == null -> ParticipationStop.NOT_ENROLLED
            else -> ParticipationStop.of(enrollment.getParticipationStatus())
        }
    }

    private fun loadServerSummary(
        context: Context,
        db: ChronicleDb,
        server: UploadServerEntity,
        health: ServerHealthSummary,
        participationStop: ParticipationStop?,
    ): UploadServerSummary {
        val disclosure = parseDisclosure(server.studyDisclosureJson)
        return UploadServerSummary(
            id = server.id,
            name = server.name,
            url = server.url,
            enabled = server.enabled,
            healthLabel = health.message,
            lastSuccess = latestSuccess(server),
            usageItemsUploaded = server.usageUploadSuccessCount,
            usageFailedAttempts = server.usageUploadFailureCount,
            sensorItemsUploaded = server.sensorUploadSuccessCount,
            sensorFailedAttempts = server.sensorUploadFailureCount,
            batteryItemsUploaded = server.batteryUploadSuccessCount,
            batteryFailedAttempts = server.batteryUploadFailureCount,
            history = db.uploadStatsDao().getRecentStats(server.id, "${server.id}:${server.createdAt}", 7).map { stat ->
                formatDailyUploadStats(
                    stat,
                    includeRestricted = BuildConfig.ALLOW_RESTRICTED_RESEARCH_PERMISSIONS,
                    copy = context.copyResolver(),
                )
            },
            responsibleInstitution = disclosure?.participantPolicy?.responsibleInstitution,
            researchContact = disclosure?.participantPolicy?.researchContact,
            privacyPolicyUrl = disclosure?.participantPolicy?.privacyPolicyUrl,
            disclosureVersion = disclosure?.participantPolicy?.version,
            withdrawalUrl = disclosure?.participantPolicy?.withdrawalUrl,
            policyEffectiveAt = disclosure?.participantPolicy?.effectiveAt?.let(Utils::formatPolicyDate),
            participationStop = participationStop,
        )
    }

    private fun loadCollectionSummary(context: Context): CollectionStatusSummary {
        return try {
            if (StorageAdmission.isPaused(context)) {
                return CollectionStatusSummary(0, 0, 0, context.getString(R.string.collection_paused_storage))
            }
            val states = CollectionLoopStore.of(context).loadAll().values
            if (states.isEmpty()) {
                CollectionStatusSummary(0, 0, 0, context.getString(R.string.collection_status_waiting))
            } else {
                val halted = CollectionModuleState.collectionHalted(states)
                val active = if (halted) 0 else states.count { it.phase == CollectionModulePhase.ACTIVE }
                val waitingReview = states.count { it.phase == CollectionModulePhase.AWAITING_DECISION }
                val disabled = states.count { it.phase == CollectionModulePhase.INACTIVE }
                CollectionStatusSummary(
                    active = active,
                    waitingReview = waitingReview,
                    disabled = disabled,
                    message = (if (halted) context.getString(R.string.ds_status_paused_required) + "\n" else "") + context.getString(
                        R.string.collection_status_counts,
                        active,
                        waitingReview,
                        disabled,
                    ),
                )
            }
        } catch (e: Exception) {
            CollectionStatusSummary(0, 0, 0, context.getString(R.string.collection_status_unavailable))
        }
    }

    private fun loadSensorSummary(context: Context, db: ChronicleDb): SensorDashboardSummary {
        if (!BuildConfig.ALLOW_RESTRICTED_RESEARCH_PERMISSIONS) {
            return SensorDashboardSummary(
                configured = emptyList(),
                enabled = emptyList(),
                disabled = emptyList(),
                available = CollectionCapabilityResolver.availableSensors(context).sortedBy { it.name },
                pendingByType = emptyMap(),
                rateHzByType = emptyMap(),
                activeSecondsByType = emptyMap(),
                periodSecondsByType = emptyMap(),
                lastUpload = null,
                serviceRunning = false,
            )
        }
        val settings = SensorSettings(context)
        val configuredSet = settings.getConfiguredSensors()
        val configured = configuredSet.sortedBy { it.name }
        val enabled = settings.getEnabledSensors().sortedBy { it.name }
        val disabled = (configuredSet - settings.getEnabledSensors()).sortedBy { it.name }
        return SensorDashboardSummary(
            configured = configured,
            enabled = enabled,
            disabled = disabled,
            available = CollectionCapabilityResolver.availableSensors(context).sortedBy { it.name },
            pendingByType = db.sensorSampleDao().countBySensorType()
                .associate { it.sensorType to it.count },
            rateHzByType = configuredSet.associateWith { settings.getSamplingRateHz(it) },
            activeSecondsByType = configuredSet.associateWith { settings.getDutyCycleActiveSeconds(it) },
            periodSecondsByType = configuredSet.associateWith { settings.getDutyCyclePeriodSeconds(it) },
            lastUpload = settings.getLastSensorUpload(),
            serviceRunning = DistributionRestrictedRuntime.hardwareSensorsRunning(context),
        )
    }

    internal fun loadServerHealth(
        context: Context,
        servers: List<UploadServerEntity>,
        online: Boolean,
        participationStop: ParticipationStop? = null,
    ): ServerHealthSummary {
        if (servers.isEmpty()) {
            return ServerHealthSummary(0, context.getString(R.string.uploads_none_configured))
        }
        // The server rejects a not-enrolled device's uploads; those failures say nothing about its health.
        participationStop?.copy?.serverHealth?.let { return ServerHealthSummary(servers.size, context.getString(it)) }
        val enabled = servers.filter { it.enabled }
        if (enabled.isEmpty()) {
            return ServerHealthSummary(servers.size, context.getString(R.string.server_health_all_disabled))
        }
        // Failure counters move only when an upload runs, and uploads wait for a network, so
        // offline the counters still read as healthy. Say what is known instead.
        if (!online) return ServerHealthSummary(servers.size, context.getString(R.string.server_health_offline))
        val unhealthy = enabled.filter {
            val health = it.healthStatus()
            health != ServerHealthStatus.HEALTHY && health != ServerHealthStatus.UNKNOWN
        }
        val unknown = enabled.count { it.healthStatus() == ServerHealthStatus.UNKNOWN }
        val message = when {
            // Online and failing upload after upload (a server refusing this app version never recovers on its own):
            // the data is safe here, but only the study team can fix it.
            unhealthy.any { it.healthStatus() == ServerHealthStatus.UNHEALTHY } ->
                context.getString(R.string.server_health_uploads_failing)
            unhealthy.isNotEmpty() -> context.getString(
                R.string.server_health_status,
                context.getString(unhealthy.first().healthStatus().labelRes()).lowercase(),
            )
            unknown == enabled.size -> context.getString(R.string.server_health_no_status)
            unknown > 0 -> context.getString(R.string.server_health_waiting)
            else -> context.getString(R.string.server_health_healthy)
        }
        return ServerHealthSummary(servers.size, message)
    }

    private fun hasValidatedInternet(context: Context): Boolean {
        val manager = context.getSystemService(ConnectivityManager::class.java) ?: return false
        val capabilities = manager.activeNetwork?.let(manager::getNetworkCapabilities) ?: return false
        return capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
            capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
    }

    private fun latestSuccess(server: UploadServerEntity): String? =
        latestUploadSuccessTime(
            listOf(
                server.lastUsageUploadSuccessTime,
                server.lastSensorUploadSuccessTime,
                server.lastBatteryUploadSuccessTime,
            ),
        )?.let(Utils::formatAsMediumDateTime)

    private fun parseDisclosure(rawJson: String?): MobileEnrollmentManifest? =
        rawJson?.let {
            runCatching {
                ChronicleJson.moshi.adapter(MobileEnrollmentManifest::class.java).fromJson(it)
            }.getOrNull()
        }
}

/** Returns the chronologically latest valid ISO-8601 success timestamp across upload families. */
internal fun latestUploadSuccessTime(rawTimes: Iterable<String?>): String? =
    rawTimes.mapNotNull { raw ->
        raw?.let { value ->
            runCatching { OffsetDateTime.parse(value) }.getOrNull()?.let { parsed -> parsed to value }
        }
    }.maxByOrNull { (parsed, _) -> parsed }?.second

/** English fallback for the daily upload-history line; screens pass [Context.copyResolver]. */
private val ENGLISH_UPLOAD_STATS: CopyResolver = englishCopy(
    mapOf(
        R.string.upload_stats_daily_research to "%s: success %d usage, %d sensors, %d battery; failures %d/%d/%d",
        R.string.upload_stats_daily to "%s: success %d usage, %d battery; failures %d/%d",
    ),
)

internal fun formatDailyUploadStats(
    stat: UploadStatsEntity,
    includeRestricted: Boolean,
    copy: CopyResolver = ENGLISH_UPLOAD_STATS,
): String = if (includeRestricted) {
    copy(
        R.string.upload_stats_daily_research,
        arrayOf<Any>(
            stat.date,
            stat.usageEventsUploaded,
            stat.sensorSamplesUploaded,
            stat.batterySamplesUploaded,
            stat.usageUploadFailures,
            stat.sensorUploadFailures,
            stat.batteryUploadFailures,
        ),
    )
} else {
    copy(
        R.string.upload_stats_daily,
        arrayOf<Any>(
            stat.date,
            stat.usageEventsUploaded,
            stat.batterySamplesUploaded,
            stat.usageUploadFailures,
            stat.batteryUploadFailures,
        ),
    )
}

/** Resource id of the participant-facing label for a derived server health status. */
internal fun ServerHealthStatus.labelRes(): Int = when (this) {
    ServerHealthStatus.HEALTHY -> R.string.health_status_healthy
    ServerHealthStatus.DEGRADED -> R.string.health_status_degraded
    ServerHealthStatus.UNHEALTHY -> R.string.health_status_unhealthy
    ServerHealthStatus.DISABLED -> R.string.health_status_disabled
    ServerHealthStatus.UNKNOWN -> R.string.health_status_unknown
}
