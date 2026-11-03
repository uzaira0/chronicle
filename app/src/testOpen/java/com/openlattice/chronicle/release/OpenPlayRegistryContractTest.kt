package com.openlattice.chronicle.release

import com.openlattice.chronicle.BuildConfig
import com.openlattice.chronicle.collection.AndroidDataCollectionSetting
import com.openlattice.chronicle.collection.CollectionModuleId
import com.openlattice.chronicle.collection.SensorCollectionModules
import com.openlattice.chronicle.collection.capability.CapabilityEnvironment
import com.openlattice.chronicle.collection.capability.CollectionCapability
import com.openlattice.chronicle.collection.capability.CollectionCapabilityResolver
import com.openlattice.chronicle.collection.capability.DistributionChannel
import com.openlattice.chronicle.collection.capability.DistributionModulePolicy
import com.openlattice.chronicle.collection.permissions.ModulePermissions
import com.openlattice.chronicle.serialization.JsonSerializer
import java.io.File
import java.security.MessageDigest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OpenPlayRegistryContractTest {
    private val requiredModules = setOf(
        CollectionModuleId.USAGE_EVENTS,
        CollectionModuleId.IN_APP_ACTIVITY_CLASS,
        CollectionModuleId.DEVICE_LIFECYCLE,
        CollectionModuleId.USER_IDENTIFICATION,
        CollectionModuleId.UPLOAD_TELEMETRY,
        CollectionModuleId.SENSOR_AVAILABILITY,
        CollectionModuleId.QUESTIONNAIRE,
        CollectionModuleId.BATTERY_TELEMETRY,
        CollectionModuleId.CONNECTIVITY_STATE,
        CollectionModuleId.DEVICE_SETTINGS,
        CollectionModuleId.APP_NETWORK_USAGE,
    ) + (SensorCollectionModules.sensorModuleIds - CollectionModuleId.SENSOR_STEP_COUNTER)

    private val modules = AndroidDataCollectionSetting().effectiveModules().keys
    private val environment = CapabilityEnvironment(
        sdkInt = 36,
        distribution = DistributionChannel.current(),
        googleServicesAvailable = BuildConfig.HAS_GOOGLE_SERVICES,
        healthConnectAvailable = BuildConfig.HAS_HEALTH_CONNECT,
        healthConnectGranted = true,
        usageAccessGranted = true,
        grantedRuntimePermissions = ModulePermissions.runtimePermissionsFor(modules, 36),
        notificationListenerEnabled = true,
        accessibilityEnabled = true,
    )

    @Test
    fun playRegistryMatchesTheOpenArtifactsAndroidCapabilities() {
        assertEquals(DistributionChannel.OPEN, DistributionChannel.current())
        assertTrue(BuildConfig.HAS_GOOGLE_SERVICES)
        assertFalse(BuildConfig.HAS_HEALTH_CONNECT)
        assertTrue(BuildConfig.ALLOW_RESTRICTED_RESEARCH_PERMISSIONS)
        assertTrue(BuildConfig.HAS_APP_NETWORK_USAGE)
        assertFalse(BuildConfig.DIRECT_BATTERY_EXEMPTION_REQUEST)

        val accepted = CollectionCapabilityResolver.resolveAll(modules, environment)
            .filterValues { it == CollectionCapability.Ready }
            .keys.mapTo(linkedSetOf()) { it.id }

        val path = listOf(
            File("src/open/assets/approved-module-registry.json"),
            File("app/src/open/assets/approved-module-registry.json"),
            File("chronicle/app/src/open/assets/approved-module-registry.json"),
        ).first(File::isFile)
        val registry = checkNotNull(JsonSerializer.fromJson<Map<String, Any>>(path.readText()))
        assertEquals(1, (registry["schemaVersion"] as Number).toInt())
        assertEquals("OPEN", registry["distribution"])
        val ids = (registry.getValue("modules") as List<*>)
            .map { (it as Map<*, *>)["id"] as String }
        assertEquals("Registry must not contain duplicate modules", ids.size, ids.toSet().size)
        assertEquals(ids.joinToString(","), BuildConfig.DISTRIBUTION_APPROVED_MODULE_IDS)
        assertEquals(
            MessageDigest.getInstance("SHA-256").digest(path.readBytes()).joinToString("") { "%02x".format(it) },
            BuildConfig.APPROVED_MODULE_REGISTRY_SHA256,
        )
        assertEquals(requiredModules.mapTo(linkedSetOf()) { it.id }, ids.toSet())
        assertEquals(accepted, ids.toSet())
    }

    @Test
    fun unapprovedModulesAreRejectedAndApprovedModulesStillRequireTheirCollectors() {
        val capabilities = CollectionCapabilityResolver.resolveAll(CollectionModuleId.entries, environment)
        capabilities.filterKeys { it !in requiredModules }.forEach { (moduleId, capability) ->
            assertEquals(
                "${moduleId.id} must be rejected before device/service/permission checks",
                CollectionCapability.PolicyDisabled("This optional module is not included in this distribution"),
                capability,
            )
        }
        CollectionModuleId.entries.forEach { moduleId ->
            assertEquals(
                "${moduleId.id} must follow registry approval even with all collectors compiled in",
                moduleId in requiredModules,
                DistributionModulePolicy.supports(DistributionChannel.OPEN, moduleId, restrictedCollectorsCompiledIn = true),
            )
            assertEquals(
                "${moduleId.id} still needs its collector when approved by the registry",
                moduleId in requiredModules && moduleId !in DistributionModulePolicy.restrictedResearchModules,
                DistributionModulePolicy.supports(DistributionChannel.OPEN, moduleId, restrictedCollectorsCompiledIn = false),
            )
        }
    }
}
