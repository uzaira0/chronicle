package com.openlattice.chronicle.collection.permissions

import com.openlattice.chronicle.collection.CollectionModuleId
import com.openlattice.chronicle.collection.HealthConnectRecordType

/**
 * How a permission is obtained — this determines the request path the app must wire so the
 * permission is actually granted (not merely declared).
 *
 *  - [MANIFEST_NORMAL]      — auto-granted at install; no request needed.
 *  - [RUNTIME]              — a dangerous runtime permission; requested via
 *                            `ActivityResultContracts.RequestMultiplePermissions` (API 23+; API 29+
 *                            for ACTIVITY_RECOGNITION).
 *  - [USAGE_ACCESS]         — Usage Access special permission; granted by sending the participant to
 *                            `Settings.ACTION_USAGE_ACCESS_SETTINGS` (checked via AppOps GET_USAGE_STATS).
 *  - [HEALTH_CONNECT]       — a Health Connect read permission; granted on the Health Connect screen
 *                            launched by `PermissionController.createRequestPermissionResultContract()`,
 *                            NOT the normal runtime dialog.
 *  - [NOTIFICATION_LISTENER]— Notification access; the participant enables the listener service in
 *                            `Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS` (declared as a service permission).
 *  - [ACCESSIBILITY]        — an AccessibilityService; the participant enables it in
 *                            `Settings.ACTION_ACCESSIBILITY_SETTINGS` (declared as a service permission).
 */
public enum class PermissionKind {
    MANIFEST_NORMAL,
    RUNTIME,
    USAGE_ACCESS,
    HEALTH_CONNECT,
    NOTIFICATION_LISTENER,
    ACCESSIBILITY,
}

/** One permission a collection module needs, and how that permission is obtained. */
public data class PermissionRequirement(public val permission: String, public val kind: PermissionKind)

/**
 * The single source of truth for **which OS permission each collection module needs and how it is
 * granted** — the registry that closes the permission-gap class of bug (a module consented and
 * collectable, but its permission never granted, so it silently produces nothing).
 *
 * This map is the registry the app's permission-request surface ([com.openlattice.chronicle.ui.DataSharingFragment])
 * iterates, so adding a module's permission here is enough to make the app request it — there is no
 * per-module request wiring to drift. `ModulePermissionCoverageTest` then asserts each entry is
 * actually declared in a manifest and that a request path exists for the runtime / Health Connect kinds.
 *
 * Permission names are string literals (not `android.Manifest.permission.*`) so this stays usable
 * from plain JVM unit tests, and so they can be compared directly against the `<uses-permission>`
 * names in the manifests.
 */
public object ModulePermissions {

    public const val ACTIVITY_RECOGNITION: String = "android.permission.ACTIVITY_RECOGNITION"
    public const val PACKAGE_USAGE_STATS: String = "android.permission.PACKAGE_USAGE_STATS"
    public const val ACCESS_NETWORK_STATE: String = "android.permission.ACCESS_NETWORK_STATE"
    public const val BIND_NOTIFICATION_LISTENER_SERVICE: String =
        "android.permission.BIND_NOTIFICATION_LISTENER_SERVICE"
    public const val BIND_ACCESSIBILITY_SERVICE: String = "android.permission.BIND_ACCESSIBILITY_SERVICE"
    public const val HEALTH_CONNECT_BACKGROUND_READ: String =
        "android.permission.health.READ_HEALTH_DATA_IN_BACKGROUND"

    /**
     * The Health Connect read permissions the `health_connect` module actually reads. This list is
     * authoritative: it MUST equal both `HealthConnectPermissions.READ_PERMISSIONS` (what the request
     * launcher asks for) and the `<uses-permission android:name="android.permission.health.READ_*">`
     * set in `collection-device`'s manifest — exactly, no extras. Declaring health permissions the app
     * never reads is a Health Connect / Play-policy violation and over-broad consent, so the coverage
     * test enforces equality, not containment.
     */
    public val HEALTH_CONNECT_RECORD_PERMISSIONS: Map<HealthConnectRecordType, String> = linkedMapOf(
        HealthConnectRecordType.STEPS to "android.permission.health.READ_STEPS",
        HealthConnectRecordType.DISTANCE to "android.permission.health.READ_DISTANCE",
        HealthConnectRecordType.HEART_RATE to "android.permission.health.READ_HEART_RATE",
        HealthConnectRecordType.TOTAL_CALORIES_BURNED to "android.permission.health.READ_TOTAL_CALORIES_BURNED",
        HealthConnectRecordType.ACTIVE_CALORIES_BURNED to "android.permission.health.READ_ACTIVE_CALORIES_BURNED",
        HealthConnectRecordType.FLOORS_CLIMBED to "android.permission.health.READ_FLOORS_CLIMBED",
        HealthConnectRecordType.RESTING_HEART_RATE to "android.permission.health.READ_RESTING_HEART_RATE",
        HealthConnectRecordType.OXYGEN_SATURATION to "android.permission.health.READ_OXYGEN_SATURATION",
        HealthConnectRecordType.RESPIRATORY_RATE to "android.permission.health.READ_RESPIRATORY_RATE",
        HealthConnectRecordType.SLEEP to "android.permission.health.READ_SLEEP",
        HealthConnectRecordType.EXERCISE to "android.permission.health.READ_EXERCISE",
        HealthConnectRecordType.HEART_RATE_VARIABILITY to "android.permission.health.READ_HEART_RATE_VARIABILITY",
        HealthConnectRecordType.BODY_TEMPERATURE to "android.permission.health.READ_BODY_TEMPERATURE",
        HealthConnectRecordType.SKIN_TEMPERATURE to "android.permission.health.READ_SKIN_TEMPERATURE",
    )

    /** Full app-supported declaration surface; individual studies request a subset. */
    public val HEALTH_CONNECT_READ: List<String> = HEALTH_CONNECT_RECORD_PERMISSIONS.values.toList()

    /** Supplemental permission required because collection is driven by WorkManager. */
    public val HEALTH_CONNECT_REQUEST: List<String> =
        HEALTH_CONNECT_READ + HEALTH_CONNECT_BACKGROUND_READ

    public val REQUIREMENTS: Map<CollectionModuleId, List<PermissionRequirement>> = buildMap {
        val usageAccess = PermissionRequirement(PACKAGE_USAGE_STATS, PermissionKind.USAGE_ACCESS)
        val activityRecognition = PermissionRequirement(ACTIVITY_RECOGNITION, PermissionKind.RUNTIME)
        val notificationListener = PermissionRequirement(
            BIND_NOTIFICATION_LISTENER_SERVICE,
            PermissionKind.NOTIFICATION_LISTENER,
        )

        // Usage Access (special) — the app already gates on it at startup for usage_events.
        put(CollectionModuleId.USAGE_EVENTS, listOf(usageAccess))
        put(CollectionModuleId.IN_APP_ACTIVITY_CLASS, listOf(usageAccess))
        put(CollectionModuleId.APP_NETWORK_USAGE, listOf(usageAccess))

        // ACTIVITY_RECOGNITION (runtime, API 29+) — required by the GMS Activity/Sleep APIs AND by
        // SensorManager for TYPE_STEP_COUNTER / TYPE_SIGNIFICANT_MOTION. Without an explicit grant the
        // framework logs "Tried enabling a sensor … without holding android.permission.ACTIVITY_RECOGNITION"
        // and these modules collect nothing.
        put(CollectionModuleId.ACTIVITY_RECOGNITION, listOf(activityRecognition))
        put(CollectionModuleId.SLEEP, listOf(activityRecognition))
        put(CollectionModuleId.SENSOR_STEP_COUNTER, listOf(activityRecognition))
        put(CollectionModuleId.SENSOR_SIGNIFICANT_MOTION, listOf(activityRecognition))

        // Health Connect (own grant flow) — read-only.
        put(
            CollectionModuleId.HEALTH_CONNECT,
            HEALTH_CONNECT_REQUEST.map { PermissionRequirement(it, PermissionKind.HEALTH_CONNECT) },
        )

        // Normal manifest permission, no runtime prompt.
        put(
            CollectionModuleId.CONNECTIVITY_STATE,
            listOf(PermissionRequirement(ACCESS_NETWORK_STATE, PermissionKind.MANIFEST_NORMAL)),
        )

        // Service-bound special accesses granted in system Settings.
        put(
            CollectionModuleId.NOTIFICATION_ACTIVITY,
            listOf(notificationListener),
        )
        // The current mic-free audio collector is hosted by NotificationListenerService so it can
        // observe MediaSession state continuously. Both independently consented audio modules need
        // that access even when notification_activity itself is disabled.
        put(CollectionModuleId.AUDIO_ACTIVITY, listOf(notificationListener))
        put(CollectionModuleId.AUDIO_CONTENT, listOf(notificationListener))
        put(
            CollectionModuleId.INTERACTION_EVENTS,
            listOf(PermissionRequirement(BIND_ACCESSIBILITY_SERVICE, PermissionKind.ACCESSIBILITY)),
        )
    }

    public fun requirementsFor(moduleId: CollectionModuleId): List<PermissionRequirement> =
        REQUIREMENTS[moduleId] ?: emptyList()

    /** Exact Health Connect read permissions for a study-approved record-type subset. */
    public fun healthConnectReadPermissionsFor(
        recordTypes: Set<HealthConnectRecordType>,
    ): Set<String> = recordTypes.mapNotNullTo(LinkedHashSet(), HEALTH_CONNECT_RECORD_PERMISSIONS::get)

    /**
     * The runtime (dangerous) permissions the given modules need — the set requestable in one
     * `RequestMultiplePermissions` launch. Empty if none of [moduleIds] needs a runtime permission.
     */
    public fun runtimePermissionsFor(moduleIds: Collection<CollectionModuleId>): Set<String> =
        runtimePermissionsFor(moduleIds, sdkInt = Int.MAX_VALUE)

    /** Runtime permissions applicable at [sdkInt]; activity recognition is runtime-only on API 29+. */
    public fun runtimePermissionsFor(
        moduleIds: Collection<CollectionModuleId>,
        sdkInt: Int,
    ): Set<String> =
        moduleIds.asSequence()
            .flatMap { requirementsFor(it).asSequence() }
            .filter { it.kind == PermissionKind.RUNTIME }
            .filterNot { it.permission == ACTIVITY_RECOGNITION && sdkInt < 29 }
            .map { it.permission }
            .toCollection(LinkedHashSet())

    /** Whether any of [moduleIds] needs a permission of the given [kind]. */
    public fun needsKind(moduleIds: Collection<CollectionModuleId>, kind: PermissionKind): Boolean =
        moduleIds.any { id -> requirementsFor(id).any { it.kind == kind } }

    /** Whether any of [moduleIds] needs the Health Connect grant flow. */
    public fun needsHealthConnect(moduleIds: Collection<CollectionModuleId>): Boolean =
        needsKind(moduleIds, PermissionKind.HEALTH_CONNECT)
}
