package com.openlattice.chronicle.collection.capability

import com.openlattice.chronicle.BuildConfig
import com.openlattice.chronicle.collection.CollectionModuleId
import com.openlattice.chronicle.collection.permissions.ModulePermissions
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CollectionCapabilityResolverTest {

    private val playSupportedModules = setOf(
        CollectionModuleId.USAGE_EVENTS,
        CollectionModuleId.IN_APP_ACTIVITY_CLASS,
        CollectionModuleId.DEVICE_LIFECYCLE,
        CollectionModuleId.USER_IDENTIFICATION,
        CollectionModuleId.UPLOAD_TELEMETRY,
        CollectionModuleId.BATTERY_TELEMETRY,
        CollectionModuleId.CONNECTIVITY_STATE,
        CollectionModuleId.DEVICE_SETTINGS,
    )

    @Test
    fun missingHardwareSensorIsUnsupportedDevice() {
        val environment = ready.copy(availableSensors = emptySet())

        val result = CollectionCapabilityResolver.resolve(
            CollectionModuleId.SENSOR_ACCELEROMETER,
            environment,
        )

        assertTrue(result is CollectionCapability.UnsupportedDevice)
        assertTrue(result.message.contains("Accelerometer"))
    }

    @Test
    fun playReleasePolicyDisablesEveryRiskyOrNonessentialModule() {
        assertEquals(playSupportedModules, DistributionModulePolicy.playSupportedModules)
        val forbidden = CollectionModuleId.entries.filter { it.active && it !in playSupportedModules }

        forbidden.forEach { moduleId ->
            val result = resolve(moduleId, playReady)
            assertTrue(
                "${moduleId.id} must be policy-disabled in Play, but was $result",
                result is CollectionCapability.PolicyDisabled,
            )
        }
    }

    @Test
    fun amazonReleaseUsesTheSameMinimalModulePolicy() {
        val amazonReady = ready.copy(distribution = DistributionChannel.AMAZON)
        val forbidden = CollectionModuleId.entries.filter { it.active && it !in playSupportedModules }

        forbidden.forEach { moduleId ->
            assertTrue(
                "${moduleId.id} must be policy-disabled in Amazon",
                resolve(moduleId, amazonReady) is CollectionCapability.PolicyDisabled,
            )
        }
        playSupportedModules.forEach { moduleId ->
            assertEquals(CollectionCapability.Ready, resolve(moduleId, amazonReady))
        }
    }

    @Test
    fun playReleaseKeepsUnlockIdentificationAndBasicTelemetryAvailable() {
        playSupportedModules.forEach { moduleId ->
            assertEquals("${moduleId.id} must remain available", CollectionCapability.Ready, resolve(moduleId, playReady))
        }
    }
    private val ready = CapabilityEnvironment(
        sdkInt = 36,
        distribution = DistributionChannel.RESEARCH,
        googleServicesAvailable = true,
        healthConnectAvailable = true,
        healthConnectGranted = true,
        usageAccessGranted = true,
        grantedRuntimePermissions = setOf(ModulePermissions.ACTIVITY_RECOGNITION),
        notificationListenerEnabled = true,
        accessibilityEnabled = true,
    )
    private val playReady = ready.copy(distribution = DistributionChannel.PLAY)

    @Test
    fun amazonPolicyDisablesRestrictedModulesBeforeServiceChecks() {
        val fire = ready.copy(distribution = DistributionChannel.AMAZON)

        assertTrue(resolve(CollectionModuleId.SLEEP, fire) is CollectionCapability.PolicyDisabled)
        assertTrue(resolve(CollectionModuleId.ACTIVITY_RECOGNITION, fire) is CollectionCapability.PolicyDisabled)
        assertTrue(resolve(CollectionModuleId.HEALTH_CONNECT, fire) is CollectionCapability.PolicyDisabled)
    }

    @Test
    fun healthConnectReportsApiAndServiceBoundaries() {
        val api27 = ready.copy(sdkInt = 27)
        val missingService = ready.copy(healthConnectAvailable = false)

        if (!BuildConfig.HAS_HEALTH_CONNECT) {
            assertTrue(
                resolve(CollectionModuleId.HEALTH_CONNECT, ready) is CollectionCapability.PolicyDisabled,
            )
            return
        }

        assertEquals(28, (resolve(CollectionModuleId.HEALTH_CONNECT, api27) as CollectionCapability.UnsupportedApi).minimumApi)
        assertTrue(resolve(CollectionModuleId.HEALTH_CONNECT, missingService) is CollectionCapability.ServiceUnavailable)
        assertTrue(
            resolve(CollectionModuleId.HEALTH_CONNECT, ready.copy(healthConnectGranted = false))
                is CollectionCapability.PermissionRequired,
        )
    }

    @Test
    fun activityRecognitionPermissionIsRuntimeOnlyFromApi29() {
        val noRuntimeGrant = ready.copy(grantedRuntimePermissions = emptySet())

        assertEquals(
            CollectionCapability.Ready,
            resolve(CollectionModuleId.ACTIVITY_RECOGNITION, noRuntimeGrant.copy(sdkInt = 28)),
        )
        assertTrue(
            resolve(CollectionModuleId.ACTIVITY_RECOGNITION, noRuntimeGrant.copy(sdkInt = 29))
                is CollectionCapability.PermissionRequired,
        )
    }

    @Test
    fun specialAccessesHaveDistinctEnablementStates() {
        assertTrue(
            resolve(CollectionModuleId.USAGE_EVENTS, ready.copy(usageAccessGranted = false))
                is CollectionCapability.UserEnablementRequired,
        )
        assertTrue(
            resolve(CollectionModuleId.NOTIFICATION_ACTIVITY, ready.copy(notificationListenerEnabled = false))
                is CollectionCapability.UserEnablementRequired,
        )
        assertTrue(
            resolve(CollectionModuleId.INTERACTION_EVENTS, ready.copy(accessibilityEnabled = false))
                is CollectionCapability.UserEnablementRequired,
        )
    }

    @Test
    fun missingGoogleServicesIsNotReportedAsReady() {
        val result = resolve(CollectionModuleId.SLEEP, ready.copy(googleServicesAvailable = false))

        assertTrue(result is CollectionCapability.ServiceUnavailable)
        assertTrue(result.message.contains("Google Play Services"))
    }

    private fun resolve(
        moduleId: CollectionModuleId,
        environment: CapabilityEnvironment,
    ): CollectionCapability = CollectionCapabilityResolver.resolve(moduleId, environment)
}
