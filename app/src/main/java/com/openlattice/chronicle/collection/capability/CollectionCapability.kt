package com.openlattice.chronicle.collection.capability

import android.content.Context
import android.content.pm.PackageManager
import android.hardware.SensorManager
import android.os.Build
import androidx.core.content.ContextCompat
import com.openlattice.chronicle.BuildConfig
import com.openlattice.chronicle.R
import com.openlattice.chronicle.android.AndroidSensorType
import com.openlattice.chronicle.collection.CollectionModuleId
import com.openlattice.chronicle.collection.DistributionRestrictedRuntime
import com.openlattice.chronicle.collection.SensorCollectionModules
import com.openlattice.chronicle.collection.permissions.ModulePermissions
import com.openlattice.chronicle.collection.permissions.PermissionKind
import com.openlattice.chronicle.collection.state.CaptureRegistrationStatus
import com.openlattice.chronicle.hasUsageSettingPermission
import com.openlattice.chronicle.sensors.SensorTypeMapping
import com.openlattice.chronicle.services.notifications.hasNotificationListenerAccess
import com.openlattice.chronicle.ui.CopyResolver
import com.openlattice.chronicle.ui.englishCopy

public enum class DistributionChannel(public val minimalPublic: Boolean) {
    AMAZON(minimalPublic = true),
    RESEARCH(minimalPublic = false),
    OPEN(minimalPublic = false);

    public companion object {
        private val configured = valueOf(BuildConfig.DISTRIBUTION_CHANNEL)

        public fun current(): DistributionChannel = configured
    }
}

/**
 * Distribution-level collection boundary. The Amazon artifact intentionally carries only the
 * smallest useful research surface: app usage, basic device telemetry, upload diagnostics, and
 * the participant's explicitly requested unlock-identification prompt. A study requesting any
 * other module must be rejected before consent rather than silently collecting less than its
 * signed manifest promises.
 */
internal object DistributionModulePolicy {
    internal fun supports(moduleId: CollectionModuleId): Boolean = supports(DistributionChannel.current(), moduleId)

    /** Generated from `src/amazon/assets/approved-module-registry.json` at build time. */
    internal val amazonSupportedModules: Set<CollectionModuleId> =
        approvedModules(BuildConfig.AMAZON_APPROVED_MODULE_IDS)

    /** Generated from this flavor's approved-module registry; research has no allowlist. */
    private val distributionSupportedModules: Set<CollectionModuleId> =
        approvedModules(BuildConfig.DISTRIBUTION_APPROVED_MODULE_IDS)

    private fun approvedModules(moduleIds: String): Set<CollectionModuleId> =
        moduleIds.split(',')
            .filter(String::isNotBlank)
            .mapTo(linkedSetOf()) { moduleId ->
                requireNotNull(CollectionModuleId.fromIdOrNull(moduleId)) {
                    "Unknown module in the approved registry: $moduleId"
                }
            }

    /**
     * Modules whose collectors, services and OS special accesses ship only behind
     * `ALLOW_RESTRICTED_RESEARCH_PERMISSIONS`: the sensor service, the accessibility collector,
     * the notification listener and the GMS activity/sleep registration. A build with that flag
     * off never starts them, so a study enabling one of these must be rejected before consent
     * instead of collecting a promise the artifact cannot keep.
     */
    internal val restrictedResearchModules: Set<CollectionModuleId> = setOf(
        CollectionModuleId.INTERACTION_EVENTS,
        CollectionModuleId.AUDIO_ACTIVITY,
        CollectionModuleId.AUDIO_CONTENT,
        CollectionModuleId.NOTIFICATION_ACTIVITY,
        CollectionModuleId.SLEEP,
        CollectionModuleId.ACTIVITY_RECOGNITION,
    ) + SensorCollectionModules.sensorModuleIds

    /**
     * Amazon and Open require registry approval. Research has no allowlist. Open and
     * Research also require the restricted collectors for modules whose runtime uses them.
     */
    internal fun supports(distribution: DistributionChannel, moduleId: CollectionModuleId): Boolean =
        supports(distribution, moduleId, BuildConfig.ALLOW_RESTRICTED_RESEARCH_PERMISSIONS)

    internal fun supports(
        distribution: DistributionChannel,
        moduleId: CollectionModuleId,
        restrictedCollectorsCompiledIn: Boolean,
    ): Boolean =
        when (distribution) {
            DistributionChannel.AMAZON -> moduleId in amazonSupportedModules
            DistributionChannel.OPEN -> moduleId in distributionSupportedModules &&
                (restrictedCollectorsCompiledIn || moduleId !in restrictedResearchModules)
            DistributionChannel.RESEARCH ->
                restrictedCollectorsCompiledIn || moduleId !in restrictedResearchModules
        }
}

public sealed interface CollectionCapability {
    public val message: String

    public data object Ready : CollectionCapability {
        override val message: String = "Ready"
    }

    public data class PermissionRequired(override val message: String) : CollectionCapability
    public data class UserEnablementRequired(override val message: String) : CollectionCapability
    public data class UnsupportedApi(public val minimumApi: Int, override val message: String) : CollectionCapability
    public data class UnsupportedDevice(override val message: String) : CollectionCapability
    public data class ServiceUnavailable(override val message: String) : CollectionCapability
    public data class PolicyDisabled(override val message: String) : CollectionCapability
    public data class TemporaryFailure(override val message: String) : CollectionCapability

    public val canCollectNow: Boolean
        get() = this is Ready

    public val canRequestAccess: Boolean
        get() = this is PermissionRequired || this is UserEnablementRequired
}

public data class CapabilityEnvironment(
    val sdkInt: Int,
    val distribution: DistributionChannel,
    val googleServicesAvailable: Boolean,
    val healthConnectAvailable: Boolean,
    val healthConnectGranted: Boolean,
    val usageAccessGranted: Boolean,
    val grantedRuntimePermissions: Set<String>,
    val notificationListenerEnabled: Boolean,
    val accessibilityEnabled: Boolean,
    val availableSensors: Set<AndroidSensorType> = AndroidSensorType.entries.toSet(),
    // Whether this artifact compiled the restricted collectors in (see DistributionModulePolicy).
    // Carried on the environment so a unit test can describe a full-channel device on any
    // flavor; production always reads the build flag.
    val restrictedCollectorsCompiledIn: Boolean = BuildConfig.ALLOW_RESTRICTED_RESEARCH_PERMISSIONS,
    val failedCaptureRegistrations: Set<CollectionModuleId> = emptySet(),
)

private val ENGLISH_CAPABILITY: CopyResolver = englishCopy(
    mapOf(
        R.string.cap_not_in_play to "%s is not included in this minimal release",
        R.string.cap_sensor_unavailable to "%s is not available on this device",
        R.string.cap_optional_not_included to "This optional module is not included in this distribution",
        R.string.cap_hc_fire_os to "Health Connect is not available on Fire OS",
        R.string.cap_hc_android_9 to "Health Connect requires Android 9 or later",
        R.string.cap_hc_not_installed to "Health Connect is not installed or available",
        R.string.cap_hc_access_required to "Health Connect access is required",
        R.string.cap_gms_fire_os to "%s requires Google Play Services and is unavailable on Fire OS",
        R.string.cap_gms_required to "%s requires Google Play Services",
        R.string.cap_usage_access to "Usage access must be enabled in Settings",
        R.string.cap_device_permission to "Device permission is required",
        R.string.cap_notification_access to "Notification access must be enabled in Settings",
        R.string.cap_accessibility to "Chronicle accessibility access must be enabled",
        R.string.cap_capture_retry to "Capture is temporarily unavailable. Chronicle will retry; review Android access in Data Sharing.",
        R.string.cap_sleep_collection to "Sleep collection",
        R.string.cap_activity_recognition to "Activity recognition",
    ),
)

public object CollectionCapabilityResolver {
    private val usageModules = setOf(
        CollectionModuleId.USAGE_EVENTS,
        CollectionModuleId.IN_APP_ACTIVITY_CLASS,
        CollectionModuleId.APP_NETWORK_USAGE,
    )
    private val googleModules = setOf(CollectionModuleId.SLEEP, CollectionModuleId.ACTIVITY_RECOGNITION)

    public fun snapshot(context: Context): CapabilityEnvironment {
        val appContext = context.applicationContext
        val activeRuntimePermissions = ModulePermissions.runtimePermissionsFor(
            CollectionModuleId.entries,
            Build.VERSION.SDK_INT,
        )
        return CapabilityEnvironment(
            sdkInt = Build.VERSION.SDK_INT,
            distribution = DistributionChannel.current(),
            googleServicesAvailable = BuildConfig.ALLOW_RESTRICTED_RESEARCH_PERMISSIONS &&
                DistributionRestrictedRuntime.activityRecognitionAvailable(appContext),
            healthConnectAvailable = BuildConfig.HAS_HEALTH_CONNECT &&
                DistributionRestrictedRuntime.healthConnectAvailable(appContext),
            healthConnectGranted = BuildConfig.HAS_HEALTH_CONNECT &&
                DistributionRestrictedRuntime.healthConnectGranted(appContext),
            usageAccessGranted = hasUsageSettingPermission(appContext),
            failedCaptureRegistrations = CaptureRegistrationStatus.failedModules(appContext),
            grantedRuntimePermissions = activeRuntimePermissions.filterTo(linkedSetOf()) {
                ContextCompat.checkSelfPermission(appContext, it) == PackageManager.PERMISSION_GRANTED
            },
            notificationListenerEnabled = hasNotificationListenerAccess(appContext),
            accessibilityEnabled = BuildConfig.ALLOW_RESTRICTED_RESEARCH_PERMISSIONS &&
                DistributionRestrictedRuntime.interactionAccessibilityEnabled(appContext),
            availableSensors = availableSensors(appContext),
        )
    }

    public fun availableSensors(context: Context): Set<AndroidSensorType> {
        val sensorManager = context.getSystemService(Context.SENSOR_SERVICE) as? SensorManager
        return AndroidSensorType.entries.filterTo(linkedSetOf()) { sensorType ->
            sensorManager?.getDefaultSensor(SensorTypeMapping.toAndroidType(sensorType)) != null
        }
    }

    private fun CopyResolver.text(id: Int, vararg args: Any): String = invoke(id, args)

    private fun serviceCapability(
        moduleId: CollectionModuleId,
        environment: CapabilityEnvironment,
        copy: CopyResolver,
    ): CollectionCapability? {
        return when {
            moduleId == CollectionModuleId.HEALTH_CONNECT -> when {
                !BuildConfig.HAS_HEALTH_CONNECT -> CollectionCapability.PolicyDisabled(copy.text(R.string.cap_optional_not_included))
                environment.distribution == DistributionChannel.AMAZON -> CollectionCapability.ServiceUnavailable(copy.text(R.string.cap_hc_fire_os))
                environment.sdkInt < Build.VERSION_CODES.P ->
                    CollectionCapability.UnsupportedApi(Build.VERSION_CODES.P, copy.text(R.string.cap_hc_android_9))
                !environment.healthConnectAvailable -> CollectionCapability.ServiceUnavailable(copy.text(R.string.cap_hc_not_installed))
                !environment.healthConnectGranted -> CollectionCapability.PermissionRequired(copy.text(R.string.cap_hc_access_required))
                else -> null
            }
            moduleId in googleModules -> when {
                environment.distribution == DistributionChannel.AMAZON ->
                    CollectionCapability.ServiceUnavailable(copy.text(R.string.cap_gms_fire_os, moduleLabel(moduleId, copy)))
                !environment.googleServicesAvailable ->
                    CollectionCapability.ServiceUnavailable(copy.text(R.string.cap_gms_required, moduleLabel(moduleId, copy)))
                else -> null
            }
            else -> null
        }
    }

    public fun resolve(
        moduleId: CollectionModuleId,
        environment: CapabilityEnvironment,
        copy: CopyResolver = ENGLISH_CAPABILITY,
    ): CollectionCapability {
        if (
            !DistributionModulePolicy.supports(
                environment.distribution,
                moduleId,
                environment.restrictedCollectorsCompiledIn,
            )
        ) {
            return CollectionCapability.PolicyDisabled(
                if (environment.distribution.minimalPublic) {
                    copy.text(R.string.cap_not_in_play, moduleId.id)
                } else {
                    copy.text(R.string.cap_optional_not_included)
                },
            )
        }

        val sensorType = SensorCollectionModules.sensorTypeOf(moduleId)
        if (sensorType != null && sensorType !in environment.availableSensors) {
            return CollectionCapability.UnsupportedDevice(
                copy.text(R.string.cap_sensor_unavailable, sensorLabel(sensorType)),
            )
        }

        serviceCapability(moduleId, environment, copy)?.let { return it }

        if (moduleId in usageModules && !environment.usageAccessGranted) {
            return CollectionCapability.UserEnablementRequired(copy.text(R.string.cap_usage_access))
        }

        if (moduleId in environment.failedCaptureRegistrations) {
            return CollectionCapability.ServiceUnavailable(copy.text(R.string.cap_capture_retry))
        }
        val missingRuntime = ModulePermissions.runtimePermissionsFor(listOf(moduleId), environment.sdkInt)
            .filterNot(environment.grantedRuntimePermissions::contains)
        if (missingRuntime.isNotEmpty()) {
            return CollectionCapability.PermissionRequired(copy.text(R.string.cap_device_permission))
        }

        if (ModulePermissions.needsKind(listOf(moduleId), PermissionKind.NOTIFICATION_LISTENER) &&
            !environment.notificationListenerEnabled
        ) {
            return CollectionCapability.UserEnablementRequired(copy.text(R.string.cap_notification_access))
        }
        if (ModulePermissions.needsKind(listOf(moduleId), PermissionKind.ACCESSIBILITY) &&
            !environment.accessibilityEnabled
        ) {
            return CollectionCapability.UserEnablementRequired(copy.text(R.string.cap_accessibility))
        }

        return CollectionCapability.Ready
    }

    public fun resolveAll(
        moduleIds: Collection<CollectionModuleId>,
        environment: CapabilityEnvironment,
        copy: CopyResolver = ENGLISH_CAPABILITY,
    ): Map<CollectionModuleId, CollectionCapability> = moduleIds.associateWith { resolve(it, environment, copy) }

    private fun moduleLabel(moduleId: CollectionModuleId, copy: CopyResolver): String = when (moduleId) {
        CollectionModuleId.SLEEP -> copy(R.string.cap_sleep_collection, emptyArray())
        CollectionModuleId.ACTIVITY_RECOGNITION -> copy(R.string.cap_activity_recognition, emptyArray())
        else -> moduleId.id
    }

    private fun sensorLabel(sensorType: AndroidSensorType): String = sensorType.name
        .replace(Regex("([a-z])([A-Z])"), "$1 $2")
        .replaceFirstChar(Char::uppercase)
}
