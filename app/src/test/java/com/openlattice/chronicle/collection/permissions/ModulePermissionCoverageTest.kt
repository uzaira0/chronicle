package com.openlattice.chronicle.collection.permissions

import com.openlattice.chronicle.collection.CollectionModuleId
import com.openlattice.chronicle.collection.HealthConnectRecordType
import com.openlattice.chronicle.collection.capability.CapabilityEnvironment
import com.openlattice.chronicle.collection.capability.DistributionChannel
import com.openlattice.chronicle.collection.capability.activeModulePermissionStatus
import com.openlattice.chronicle.collection.state.CollectionModuleState
import com.openlattice.chronicle.collection.state.ParticipantDecision
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Module requirements must agree with packaged manifests and observable access decisions. */
class ModulePermissionCoverageTest {

    private companion object {
        /** The android build root — the dir containing `app/src/main`. */
        fun buildRoot(): File {
            var dir: File? = File(".").absoluteFile
            repeat(6) {
                val base = dir
                if (base != null && File(base, "app/src/main/AndroidManifest.xml").isFile) return base
                dir = base?.parentFile
            }
            error("Could not locate the android build root (cwd=${File(".").absolutePath})")
        }

        fun moduleManifests(root: File): List<File> =
            (root.listFiles { f -> f.isDirectory } ?: emptyArray())
                .map { File(it, "src/main/AndroidManifest.xml") }
                .filter { it.isFile } + File(root, "app/src/research/AndroidManifest.xml")

        /** Every `<uses-permission android:name="…">` name across all module manifests. */
        fun declaredUsesPermissions(root: File): Set<String> {
            val tag = Regex("""<uses-permission\b[\s\S]*?/>""")
            val name = Regex("""android:name="([^"]+)"""")
            return moduleManifests(root).flatMap { m ->
                tag.findAll(m.readText()).mapNotNull { name.find(it.value)?.groupValues?.get(1) }
            }.toSet()
        }

        /** Every `<service … android:permission="…">` value (covers notification-listener / accessibility). */
        fun declaredServicePermissions(root: File): Set<String> {
            val tag = Regex("""<service\b[\s\S]*?>""")
            val perm = Regex("""android:permission="([^"]+)"""")
            return moduleManifests(root).flatMap { m ->
                tag.findAll(m.readText()).mapNotNull { perm.find(it.value)?.groupValues?.get(1) }
            }.toSet()
        }
    }

    @Test
    fun everyUsesPermissionRequirementIsDeclared() {
        val root = buildRoot()
        val declared = declaredUsesPermissions(root)
        val usesPermissionKinds = setOf(
            PermissionKind.MANIFEST_NORMAL,
            PermissionKind.RUNTIME,
            PermissionKind.USAGE_ACCESS,
            PermissionKind.HEALTH_CONNECT,
        )
        val missing = ModulePermissions.REQUIREMENTS.values.flatten()
            .filter { it.kind in usesPermissionKinds }
            .map { it.permission }
            .filterNot { it in declared }
            .toSortedSet()
        assertTrue(
            "These module permissions are in ModulePermissions.REQUIREMENTS but are NOT declared as " +
                "<uses-permission> in any module manifest, so the OS can never grant them: $missing",
            missing.isEmpty(),
        )
    }

    @Test
    fun serviceBoundPermissionsAreDeclaredAsServicePermissions() {
        val root = buildRoot()
        val declared = declaredServicePermissions(root)
        val serviceKinds = setOf(PermissionKind.NOTIFICATION_LISTENER, PermissionKind.ACCESSIBILITY)
        val missing = ModulePermissions.REQUIREMENTS.values.flatten()
            .filter { it.kind in serviceKinds }
            .map { it.permission }
            .filterNot { it in declared }
            .toSortedSet()
        assertTrue(
            "These service-bound permissions are required but no <service android:permission=\"…\"> " +
                "declares them: $missing",
            missing.isEmpty(),
        )
    }

    @Test
    fun healthConnectPermissionSetIsInLockStepWithTheManifest() {
        val root = buildRoot()
        val declaredHealth = declaredUsesPermissions(root).filter { it.startsWith("android.permission.health.") }.toSet()
        // Exact match (not containment): data-type reads plus the WorkManager background-read
        // supplement are the complete Health Connect permission surface.
        assertEquals(
            "Declared Health Connect permissions must exactly equal ModulePermissions.HEALTH_CONNECT_REQUEST.",
            ModulePermissions.HEALTH_CONNECT_REQUEST.toSortedSet(),
            declaredHealth.toSortedSet(),
        )
    }

    @Test
    fun healthConnectStudyScopeMapsToOnlyItsApprovedPermissions() {
        assertEquals(
            setOf(
                "android.permission.health.READ_STEPS",
                "android.permission.health.READ_SLEEP",
            ),
            ModulePermissions.healthConnectReadPermissionsFor(
                setOf(HealthConnectRecordType.STEPS, HealthConnectRecordType.SLEEP),
            ),
        )
        assertTrue(ModulePermissions.healthConnectReadPermissionsFor(emptySet()).isEmpty())
    }

    @Test
    fun amazonManifestDoesNotDeclareHealthConnectPermissions() {
        val manifest = File(buildRoot(), "app/src/amazon/AndroidManifest.xml").readText()
        assertTrue("Amazon must not declare Health Connect permissions", !manifest.contains("permission.health."))
    }

    @Test
    fun healthConnectIsResearchOnlyAndAbsentFromPlay() {
        val root = buildRoot()
        listOf("research").forEach { flavor ->
            val manifest = File(root, "app/src/$flavor/AndroidManifest.xml").readText()
            assertTrue(
                "$flavor must register the current Health Connect rationale action",
                manifest.contains("androidx.health.ACTION_SHOW_PERMISSIONS_RATIONALE"),
            )
            assertTrue(
                "$flavor must make the Health Connect provider discoverable",
                manifest.contains("<package android:name=\"com.google.android.apps.healthdata\""),
            )
            assertTrue(
                "$flavor must not ship the obsolete androidx.health.connect.action",
                !manifest.contains("androidx.health.connect.action.SHOW_PERMISSIONS_RATIONALE"),
            )
        }

        // Play ships the open artifact; neither public store build carries Health Connect.
        listOf("amazon", "open").forEach { flavor ->
            val manifest = File(root, "app/src/$flavor/AndroidManifest.xml").readText()
            assertTrue("$flavor must not request Health Connect", !manifest.contains("permission.health."))
            assertTrue(!manifest.contains("com.google.android.apps.healthdata"))
            assertTrue(!manifest.contains("HealthConnectRationaleActivity"))
        }

    }

    @Test
    fun activeModulesRequestOnlyTheirMissingRuntimePermissions() {
        val module = CollectionModuleState(
            CollectionModuleId.SENSOR_STEP_COUNTER, true,
            ParticipantDecision.ACCEPTED, 1L, false, 1)
        val environment = CapabilityEnvironment(
            sdkInt = 36,
            distribution = DistributionChannel.current(),
            googleServicesAvailable = true, healthConnectAvailable = false, healthConnectGranted = false,
            usageAccessGranted = true, grantedRuntimePermissions = emptySet(),
            notificationListenerEnabled = true, accessibilityEnabled = true)
        val missing = activeModulePermissionStatus(listOf(module), environment)
        assertEquals(listOf("android.permission.ACTIVITY_RECOGNITION"), missing.missingRuntime)
        assertEquals(setOf(CollectionModuleId.SENSOR_STEP_COUNTER), missing.runtimeAccessModules)
        val granted = activeModulePermissionStatus(listOf(module),
            environment.copy(grantedRuntimePermissions = setOf("android.permission.ACTIVITY_RECOGNITION")))
        assertTrue(granted.missingRuntime.isEmpty())
        assertTrue(granted.runtimeAccessModules.isEmpty())
        val undecided = module.copy(decision = ParticipantDecision.UNDECIDED)
        assertTrue(activeModulePermissionStatus(listOf(undecided), environment)
            .missingRuntime.isEmpty())
    }

    @Test
    fun keyRequirementsAreLockedSoTheyCannotBeSilentlyDropped() {
        // A snapshot of the requirements behind the two gaps we just fixed — so removing one fails
        // loudly rather than re-shipping an inert module.
        fun runtimePerms(id: CollectionModuleId) =
            ModulePermissions.requirementsFor(id).filter { it.kind == PermissionKind.RUNTIME }.map { it.permission }.toSet()

        listOf(
            CollectionModuleId.ACTIVITY_RECOGNITION,
            CollectionModuleId.SLEEP,
            CollectionModuleId.SENSOR_STEP_COUNTER,
        ).forEach { id ->
            assertEquals(
                "$id must require ACTIVITY_RECOGNITION at runtime",
                setOf(ModulePermissions.ACTIVITY_RECOGNITION),
                runtimePerms(id),
            )
        }
        assertTrue("Significant motion has no platform permission requirement",
            runtimePerms(CollectionModuleId.SENSOR_SIGNIFICANT_MOTION).isEmpty())
        assertTrue(
            "health_connect must require the Health Connect grant flow",
            ModulePermissions.needsHealthConnect(listOf(CollectionModuleId.HEALTH_CONNECT)),
        )
        assertTrue(
            "WorkManager-driven health collection must request background Health Connect reads",
            ModulePermissions.HEALTH_CONNECT_BACKGROUND_READ in
                ModulePermissions.requirementsFor(CollectionModuleId.HEALTH_CONNECT).map { it.permission },
        )
        listOf(
            CollectionModuleId.NOTIFICATION_ACTIVITY,
            CollectionModuleId.AUDIO_ACTIVITY,
            CollectionModuleId.AUDIO_CONTENT,
        ).forEach { id ->
            assertEquals(
                "$id must request notification-listener access because its production collector is listener-hosted",
                listOf(
                    PermissionRequirement(
                        ModulePermissions.BIND_NOTIFICATION_LISTENER_SERVICE,
                        PermissionKind.NOTIFICATION_LISTENER,
                    ),
                ),
                ModulePermissions.requirementsFor(id),
            )
        }
    }
}
