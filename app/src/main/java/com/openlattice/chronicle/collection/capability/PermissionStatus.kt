package com.openlattice.chronicle.collection.capability

import android.content.Context
import com.openlattice.chronicle.BuildConfig
import com.openlattice.chronicle.collection.CollectionModuleId
import com.openlattice.chronicle.collection.permissions.ModulePermissions
import com.openlattice.chronicle.collection.permissions.PermissionKind
import com.openlattice.chronicle.collection.state.CollectionLoopStore
import com.openlattice.chronicle.collection.state.CollectionModuleState
import com.openlattice.chronicle.collection.state.CollectionModulePhase

/** Missing-permission status for the active modules (see [activeModulePermissionStatus]). */
internal data class PermissionStatus(
    val missingRuntime: List<String>,
    val needHealthConnect: Boolean,
    val needUsageAccess: Boolean = false,
    val needNotificationListener: Boolean = false,
    val needAccessibility: Boolean = false,
    val notificationAccessModules: Set<CollectionModuleId> = emptySet(),
    val runtimeAccessModules: Set<CollectionModuleId> = emptySet(),
) {
    val hasMissing: Boolean
        get() = missingRuntime.isNotEmpty() || needHealthConnect || needUsageAccess ||
            needNotificationListener || needAccessibility
}

/**
 * Which permissions the currently ACTIVE modules still need. A module is only counted once it is
 * ACTIVE (accepted + study-enabled) — we never prompt for data the participant hasn't agreed to
 * share. Shared by Data Sharing (the "Review access" rows), Overview and MainActivity routing.
 */
internal fun activeModulePermissionStatus(
    states: List<CollectionModuleState>,
    environment: CapabilityEnvironment,
): PermissionStatus {
    val active = states.filter { it.phase == CollectionModulePhase.ACTIVE }.map { it.moduleId }
    val missingRuntime = ModulePermissions.runtimePermissionsFor(active, environment.sdkInt)
        .filterNot(environment.grantedRuntimePermissions::contains)
    val needUsageAccess = ModulePermissions.needsKind(active, PermissionKind.USAGE_ACCESS) &&
        !environment.usageAccessGranted
    val needHealthConnect = BuildConfig.HAS_HEALTH_CONNECT &&
        ModulePermissions.needsHealthConnect(active) &&
        environment.healthConnectAvailable && !environment.healthConnectGranted
    val notificationAccessModules = active
        .filter { ModulePermissions.needsKind(listOf(it), PermissionKind.NOTIFICATION_LISTENER) }
        .toSet()
    val needNotificationListener = notificationAccessModules.isNotEmpty() &&
        !environment.notificationListenerEnabled
    val needAccessibility = BuildConfig.ALLOW_RESTRICTED_RESEARCH_PERMISSIONS &&
        ModulePermissions.needsKind(active, PermissionKind.ACCESSIBILITY) &&
        !environment.accessibilityEnabled
    return PermissionStatus(
        missingRuntime,
        needHealthConnect,
        needUsageAccess,
        needNotificationListener,
        needAccessibility,
        notificationAccessModules,
        active.filterTo(linkedSetOf()) { module ->
            ModulePermissions.runtimePermissionsFor(listOf(module), environment.sdkInt)
                .any(missingRuntime::contains)
        },
    )
}

/** Blocking store + grant read; call off the main thread. True when an ACTIVE module lacks its access. */
internal fun activeModuleAccessMissing(context: Context): Boolean = activeModulePermissionStatus(
    CollectionLoopStore.of(context).loadAll().values.toList(),
    CollectionCapabilityResolver.snapshot(context),
).hasMissing

