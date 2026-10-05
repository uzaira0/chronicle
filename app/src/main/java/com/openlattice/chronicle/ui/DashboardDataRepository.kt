package com.openlattice.chronicle.ui

import android.content.Context
import android.hardware.SensorManager
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
import com.openlattice.chronicle.preferences.SensorSettings
import com.openlattice.chronicle.sensors.SensorTypeMapping
import com.openlattice.chronicle.services.upload.PendingUploadCounter
import com.openlattice.chronicle.services.upload.LocalUploadDiagnosticsStore
import com.openlattice.chronicle.services.upload.LocalUploadIssueBucket
import com.openlattice.chronicle.services.upload.UploadDashboardSnapshot
import com.openlattice.chronicle.serialization.ChronicleJson
import com.openlattice.chronicle.storage.ChronicleDb
import com.openlattice.chronicle.storage.ServerHealthStatus
import com.openlattice.chronicle.storage.UploadServerEntity
import com.openlattice.chronicle.storage.UploadStatsEntity
import com.openlattice.chronicle.storage.healthStatus
import com.openlattice.chronicle.utils.Utils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.format.DateTimeFormatter

const val DASHBOARD_REFRESH_MS = 5_000L

data class DashboardSnapshot(
    val studyId: String,
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

/**
 * The study team ended (NOT_ENROLLED, e.g. the participant was deleted) or paused participation:
 * nothing collects or uploads. Screens say so instead of module consents, collection counts, or
 * upload failure counters, which the server's not-enrolled rejections leave behind.
 */
enum class ParticipationStop(val participant: Int, val status: Int, val serverHealth: Int) {
    WITHDRAWAL_PENDING(R.string.overview_participant_withdrawal_pending, R.string.participation_status_withdrawal_pending,
        R.string.server_health_withdrawal_pending),
    ENDED(R.string.overview_participant_ended, R.string.participation_status_ended, R.string.server_health_not_enrolled),
    PAUSED(R.string.overview_participant_paused, R.string.participation_status_paused, R.string.server_health_participation_paused),
    ;

    companion object {
        fun of(status: ParticipationStatus): ParticipationStop? = when (status) {
            ParticipationStatus.NOT_ENROLLED -> ENDED
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
    val samplingRateHz: Int,
    val activeSeconds: Int,
    val idleSeconds: Int,
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
)

object DashboardDataRepository {
    suspend fun load(context: Context): DashboardSnapshot = withContext(Dispatchers.IO) {
        val appContext = context.applicationContext
        val enrollment = EnrollmentSettings(appContext)
        val withdrawal = com.openlattice.chronicle.services.withdrawal.WithdrawalStateStore(appContext).state()
        val participationStop = if (withdrawal == com.openlattice.chronicle.services.withdrawal.WithdrawalState.PENDING ||
            withdrawal == com.openlattice.chronicle.services.withdrawal.WithdrawalState.NEEDS_SUPPORT)
            ParticipationStop.WITHDRAWAL_PENDING else ParticipationStop.of(enrollment.getParticipationStatus())
        val db = ChronicleDb.getInstance(appContext)
        val uploadDashboard = PendingUploadCounter.dashboardSnapshot(appContext, LocalDate.now())
        val servers = listOfNotNull(db.uploadServerDao().getConfiguredServer())
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
            android.util.Log.e("DashboardDataRepository", "Upload diagnostics unavailable; legacy import will retry", error)
            null
        }
        DashboardSnapshot(
            studyId = enrollment.getStudyId().toString(),
            participantId = enrollment.getParticipantId(),
            // Every uploader records its success on UploadServerEntity. The encrypted-pref
            // timestamp is legacy usage-only state, so using it alone made Overview say
            // "Never" while the Uploads tab showed successful sensor/battery deliveries.
            lastUpload = latestServerUpload?.let(::formatUploadTime)
                ?: Utils.getLastUpload(appContext),
            latestTimestampUploaded = Utils.getLatestTimestampUploaded(appContext),
            uploads = uploadDashboard,
            collection = participationStop?.let { CollectionStatusSummary(0, 0, 0, appContext.getString(it.status)) }
                ?: loadCollectionSummary(appContext),
            collectionModules = runCatching {
                CollectionLoopStore.of(appContext).loadAll().values.toList()
            }.getOrDefault(emptyList()),
            sensors = loadSensorSummary(appContext, db),
            serverHealth = loadServerHealth(appContext, servers, hasValidatedInternet(appContext), participationStop,
                blockedUploads = servers.any { it.enabled && com.openlattice.chronicle.services.upload.UploadRetryGate.isBlocked(appContext, it, db) }),
            participationStop = participationStop,
            localUploadIssues = diagnostics.orEmpty(),
            localUploadDiagnosticsAvailable = diagnostics != null,
            workSchedulingAvailable = !com.openlattice.chronicle.WorkSchedulingStatus.unavailable,
            servers = servers.map { server ->
                val disclosure = parseDisclosure(server.studyDisclosureJson)
                UploadServerSummary(
                    id = server.id,
                    name = server.name,
                    url = server.url,
                    enabled = server.enabled,
                    healthLabel = appContext.getString(participationStop?.status ?: if (
                        com.openlattice.chronicle.services.upload.UploadRetryGate.isBlocked(appContext, server, db)
                    ) R.string.upload_terminal_blocked else server.healthStatus().labelRes()),
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
                            copy = appContext.copyResolver(),
                        )
                    },
                    responsibleInstitution = disclosure?.participantPolicy?.responsibleInstitution,
                    researchContact = disclosure?.participantPolicy?.researchContact,
                    privacyPolicyUrl = disclosure?.participantPolicy?.privacyPolicyUrl,
                    disclosureVersion = disclosure?.participantPolicy?.version,
                    withdrawalUrl = disclosure?.participantPolicy?.withdrawalUrl,
                    policyEffectiveAt = disclosure?.participantPolicy?.effectiveAt?.toString(),
                )
            },
        )
    }

    private fun loadCollectionSummary(context: Context): CollectionStatusSummary {
        return try {
            if (com.openlattice.chronicle.collection.state.StorageAdmission.isPaused(context)) {
                return CollectionStatusSummary(0, 0, 0, context.getString(R.string.collection_paused_storage))
            }
            val states = CollectionLoopStore.of(context).loadAll().values
            if (states.isEmpty()) {
                CollectionStatusSummary(0, 0, 0, context.getString(R.string.collection_status_waiting))
            } else {
                val active = states.count { it.phase == CollectionModulePhase.ACTIVE }
                val waitingReview = states.count { it.phase == CollectionModulePhase.AWAITING_DECISION }
                val disabled = states.count { it.phase == CollectionModulePhase.INACTIVE }
                CollectionStatusSummary(
                    active = active,
                    waitingReview = waitingReview,
                    disabled = disabled,
                    message = context.getString(
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
                available = mappedAvailableSensors(context).sortedBy { it.name },
                pendingByType = emptyMap(),
                samplingRateHz = 0,
                activeSeconds = 0,
                idleSeconds = 0,
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
        val activeSeconds = settings.getDutyCycleActiveSeconds()
        val periodSeconds = settings.getDutyCyclePeriodSeconds()
        return SensorDashboardSummary(
            configured = configured,
            enabled = enabled,
            disabled = disabled,
            available = mappedAvailableSensors(context).sortedBy { it.name },
            pendingByType = db.sensorSampleDao().countBySensorType()
                .associate { it.sensorType to it.count },
            samplingRateHz = settings.getSamplingRateHz(),
            activeSeconds = activeSeconds,
            idleSeconds = (periodSeconds - activeSeconds).coerceAtLeast(0),
            rateHzByType = configuredSet.associateWith { settings.getSamplingRateHz(it) },
            activeSecondsByType = configuredSet.associateWith { settings.getDutyCycleActiveSeconds(it) },
            periodSecondsByType = configuredSet.associateWith { settings.getDutyCyclePeriodSeconds(it) },
            lastUpload = settings.getLastSensorUpload(),
            serviceRunning = DistributionRestrictedRuntime.hardwareSensorsRunning(context),
        )
    }

    private fun mappedAvailableSensors(context: Context): Set<AndroidSensorType> {
        val manager = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
        return AndroidSensorType.values()
            .filter { sensorType ->
                runCatching {
                    manager.getDefaultSensor(SensorTypeMapping.toAndroidType(sensorType)) != null
                }.getOrDefault(false)
            }
            .toSet()
    }

    internal fun loadServerHealth(
        context: Context,
        servers: List<UploadServerEntity>,
        online: Boolean,
        participationStop: ParticipationStop? = null,
        blockedUploads: Boolean = false,
    ): ServerHealthSummary {
        if (servers.isEmpty()) {
            return ServerHealthSummary(0, context.getString(R.string.uploads_none_configured))
        }
        // The server rejects a not-enrolled device's uploads; those failures say nothing about its health.
        participationStop?.let { return ServerHealthSummary(servers.size, context.getString(it.serverHealth)) }
        val enabled = servers.filter { it.enabled }
        if (enabled.isEmpty()) {
            return ServerHealthSummary(servers.size, context.getString(R.string.server_health_all_disabled))
        }
        if (blockedUploads) {
            return ServerHealthSummary(servers.size, context.getString(R.string.upload_terminal_blocked))
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
        )?.let(::formatUploadTime)

    private fun parseDisclosure(rawJson: String?): MobileEnrollmentManifest? =
        rawJson?.let {
            runCatching {
                ChronicleJson.moshi.adapter(MobileEnrollmentManifest::class.java).fromJson(it)
            }.getOrNull()
        }

    fun formatUploadTime(rawTime: String): String {
        return try {
            OffsetDateTime.parse(rawTime).format(DateTimeFormatter.ofPattern("MMM d, h:mm a"))
        } catch (e: Exception) {
            rawTime
        }
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
