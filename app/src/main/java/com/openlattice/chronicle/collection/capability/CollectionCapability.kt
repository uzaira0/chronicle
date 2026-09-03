package com.openlattice.chronicle.collection.capability

import android.content.Context
import android.content.pm.PackageManager
import android.hardware.SensorManager
import android.os.Build
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.openlattice.chronicle.BuildConfig
import com.openlattice.chronicle.R
import com.openlattice.chronicle.ui.CopyResolver
import com.openlattice.chronicle.ui.englishCopy
import com.openlattice.chronicle.collection.CollectionModuleId
import com.openlattice.chronicle.collection.DistributionRestrictedRuntime
import com.openlattice.chronicle.collection.SensorCollectionModules
import com.openlattice.chronicle.android.AndroidSensorType
import com.openlattice.chronicle.collection.permissions.ModulePermissions
import com.openlattice.chronicle.collection.permissions.PermissionKind
import com.openlattice.chronicle.hasUsageSettingPermission
import com.openlattice.chronicle.sensors.SensorTypeMapping

public enum class DistributionChannel {
    PLAY,
    AMAZON,
    RESEARCH,
    OPEN;

    public companion object {
        public fun current(): DistributionChannel = valueOf(BuildConfig.DISTRIBUTION_CHANNEL)
    }
}

/**
 * Distribution-level collection boundary. The Play artifact intentionally carries only the
 * smallest useful research surface: app usage, basic device telemetry, upload diagnostics, and
 * the participant's explicitly requested unlock-identification prompt. A study requesting any
 * other module must be rejected before consent rather than silently collecting less than its
 * signed manifest promises.
 */
internal object DistributionModulePolicy {
    /** Generated from `src/play/assets/approved-module-registry.json` at build time. */
    internal val playSupportedModules: Set<CollectionModuleId> =
        BuildConfig.PLAY_APPROVED_MODULE_IDS.split(',')
            .filter(String::isNotBlank)
            .mapTo(linkedSetOf()) { moduleId ->
                requireNotNull(CollectionModuleId.fromIdOrNull(moduleId)) {
                    "Unknown module in the approved Play registry: $moduleId"
                }
            }

    internal fun supports(distribution: DistributionChannel, moduleId: CollectionModuleId): Boolean =
        distribution !in setOf(DistributionChannel.PLAY, DistributionChannel.AMAZON) ||
            moduleId in playSupportedModules
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
)

private val ENGLISH_CAPABILITY: CopyResolver = englishCopy(
    mapOf(
        R.string.cap_not_in_play to "%s is not included in the minimal Google Play release",
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
        val sensorManager = appContext.getSystemService(Context.SENSOR_SERVICE) as? SensorManager
        val availableSensors = AndroidSensorType.entries.filterTo(linkedSetOf()) { sensorType ->
            sensorManager?.getDefaultSensor(SensorTypeMapping.toAndroidType(sensorType)) != null
        }
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
            grantedRuntimePermissions = activeRuntimePermissions.filterTo(linkedSetOf()) {
                ContextCompat.checkSelfPermission(appContext, it) == PackageManager.PERMISSION_GRANTED
            },
            notificationListenerEnabled = NotificationManagerCompat.getEnabledListenerPackages(appContext)
                .contains(appContext.packageName),
            accessibilityEnabled = BuildConfig.ALLOW_RESTRICTED_RESEARCH_PERMISSIONS &&
                DistributionRestrictedRuntime.interactionAccessibilityEnabled(appContext),
            availableSensors = availableSensors,
        )
    }

    public fun resolve(
        moduleId: CollectionModuleId,
        environment: CapabilityEnvironment,
        copy: CopyResolver = ENGLISH_CAPABILITY,
    ): CollectionCapability {
        fun s(id: Int, vararg args: Any) = copy(id, args)
        if (!DistributionModulePolicy.supports(environment.distribution, moduleId)) {
            return CollectionCapability.PolicyDisabled(
                s(R.string.cap_not_in_play, moduleId.id),
            )
        }

        val sensorType = SensorCollectionModules.sensorTypeOf(moduleId)
        if (sensorType != null && sensorType !in environment.availableSensors) {
            return CollectionCapability.UnsupportedDevice(
                s(R.string.cap_sensor_unavailable, sensorLabel(sensorType)),
            )
        }

        if (moduleId == CollectionModuleId.HEALTH_CONNECT) {
            if (!BuildConfig.HAS_HEALTH_CONNECT) {
                return CollectionCapability.PolicyDisabled(
                    s(R.string.cap_optional_not_included),
                )
            }
            if (environment.distribution == DistributionChannel.AMAZON) {
                return CollectionCapability.ServiceUnavailable(s(R.string.cap_hc_fire_os))
            }
            if (environment.sdkInt < Build.VERSION_CODES.P) {
                return CollectionCapability.UnsupportedApi(
                    Build.VERSION_CODES.P,
                    s(R.string.cap_hc_android_9),
                )
            }
            if (!environment.healthConnectAvailable) {
                return CollectionCapability.ServiceUnavailable(s(R.string.cap_hc_not_installed))
            }
            if (!environment.healthConnectGranted) {
                return CollectionCapability.PermissionRequired(s(R.string.cap_hc_access_required))
            }
        }

        if (moduleId in googleModules) {
            if (environment.distribution == DistributionChannel.AMAZON) {
                return CollectionCapability.ServiceUnavailable(
                    s(R.string.cap_gms_fire_os, moduleLabel(moduleId, copy)),
                )
            }
            if (!environment.googleServicesAvailable) {
                return CollectionCapability.ServiceUnavailable(
                    s(R.string.cap_gms_required, moduleLabel(moduleId, copy)),
                )
            }
        }

        if (moduleId in usageModules && !environment.usageAccessGranted) {
            return CollectionCapability.UserEnablementRequired(s(R.string.cap_usage_access))
        }

        val missingRuntime = ModulePermissions.runtimePermissionsFor(listOf(moduleId), environment.sdkInt)
            .filterNot(environment.grantedRuntimePermissions::contains)
        if (missingRuntime.isNotEmpty()) {
            return CollectionCapability.PermissionRequired(s(R.string.cap_device_permission))
        }

        if (ModulePermissions.needsKind(listOf(moduleId), PermissionKind.NOTIFICATION_LISTENER) &&
            !environment.notificationListenerEnabled
        ) {
            return CollectionCapability.UserEnablementRequired(s(R.string.cap_notification_access))
        }
        if (ModulePermissions.needsKind(listOf(moduleId), PermissionKind.ACCESSIBILITY) &&
            !environment.accessibilityEnabled
        ) {
            return CollectionCapability.UserEnablementRequired(s(R.string.cap_accessibility))
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
