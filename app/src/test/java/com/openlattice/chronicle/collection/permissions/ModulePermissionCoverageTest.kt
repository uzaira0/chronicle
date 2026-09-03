package com.openlattice.chronicle.collection.permissions

import com.openlattice.chronicle.collection.CollectionModuleId
import com.openlattice.chronicle.collection.HealthConnectRecordType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Coverage invariant for the permission-gap class of bug: a module is consented and collectable, but
 * the OS permission it needs is never granted, so it silently produces nothing. That is exactly how
 * `health_connect` (no Health Connect grant flow) and the ACTIVITY_RECOGNITION modules
 * (activity_recognition / sleep / step-counter / significant-motion never requested) shipped inert.
 *
 * [ModulePermissions.REQUIREMENTS] is the single source of truth for what each module needs and how
 * it is granted. This test reads the real manifests and the real source and asserts that source of
 * truth is honored end-to-end:
 *  - every permission a module needs is actually DECLARED (as `<uses-permission>` or, for the
 *    service-bound special accesses, as a `<service android:permission=…>`), and
 *  - the RUNTIME and HEALTH_CONNECT kinds have a REQUEST path wired in source (a declared-but-never-
 *    requested permission is the gap we are guarding), and
 *  - the Health Connect permission set is in lock-step across the map, the request helper, and the
 *    manifest (declaring health permissions the app never reads is a Play-policy violation).
 *
 * It is source/manifest-scanning (like `CollectionGateCallSiteInvariantTest`) so the map and the
 * wiring cannot drift apart unnoticed.
 */
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

        fun mainSourceText(root: File, modules: List<String>): String =
            (modules.map { File(root, "$it/src/main") } + File(root, "app/src/googleServices"))
                .filter { it.isDirectory }
                .flatMap { it.walkTopDown().toList() }
                .filter { it.isFile && it.extension == "kt" }
                .joinToString("\n") { it.readText() }
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

        val source = File(
            buildRoot(),
            "app/src/googleServices/java/com/openlattice/chronicle/collection/device/AndroidHealthMetricSource.kt",
        ).readText()
        assertTrue(source.contains("HealthConnectScopeStore.of(appContext).read()"))
        HealthConnectRecordType.entries.forEach { type ->
            assertTrue("Android source must gate ${type.id}", source.contains("HealthConnectRecordType.${type.name}"))
        }
    }

    @Test
    fun amazonManifestDoesNotDeclareHealthConnectPermissions() {
        val manifest = File(buildRoot(), "app/src/amazon/AndroidManifest.xml").readText()
        assertTrue("Amazon must not declare Health Connect permissions", !manifest.contains("permission.health."))
    }

    @Test
    fun healthConnectIsResearchOnlyAndAbsentFromPlay() {
        val root = buildRoot()
        listOf("open", "research").forEach { flavor ->
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

        val playManifest = File(root, "app/src/play/AndroidManifest.xml").readText()
        assertTrue(!playManifest.contains("permission.health."))
        assertTrue(!playManifest.contains("com.google.android.apps.healthdata"))
        assertTrue(!playManifest.contains("HealthConnectRationaleActivity"))

        val rationale = File(
            root,
            "app/src/googleServices/java/com/openlattice/chronicle/HealthConnectRationaleActivity.kt",
        ).readText()
        assertTrue(rationale.contains("platform_privacy_policy_full"))
        assertTrue(rationale.contains("platform_privacy_policy_url"))
    }

    @Test
    fun runtimeAndHealthConnectKindsHaveARequestPathInSource() {
        val root = buildRoot()
        val src = mainSourceText(root, listOf("app", "collection-core", "collection-device"))

        assertTrue(
            "A module declares a RUNTIME permission but no RequestMultiplePermissions / RequestPermission " +
                "launcher exists in source — declared-but-never-requested is exactly the gap this guards.",
            ModulePermissions.REQUIREMENTS.values.flatten().none { it.kind == PermissionKind.RUNTIME } ||
                (src.contains("RequestMultiplePermissions") || src.contains("RequestPermission(")),
        )
        assertTrue(
            "health_connect needs the Health Connect grant flow but no createRequestPermissionResultContract " +
                "launcher exists in source — the module would read nothing, forever.",
            !ModulePermissions.needsHealthConnect(CollectionModuleId.entries) ||
                src.contains("createRequestPermissionResultContract"),
        )
        assertTrue(
            "a module needs notification-listener access but no ACTION_NOTIFICATION_LISTENER_SETTINGS " +
                "deep-link exists in source — the module would never get enabled.",
            !ModulePermissions.needsKind(CollectionModuleId.entries, PermissionKind.NOTIFICATION_LISTENER) ||
                src.contains("ACTION_NOTIFICATION_LISTENER_SETTINGS"),
        )
        assertTrue(
            "a module needs the accessibility service but no openAccessibilitySettings deep-link exists " +
                "in source — the module would never get enabled.",
            !ModulePermissions.needsKind(CollectionModuleId.entries, PermissionKind.ACCESSIBILITY) ||
                src.contains("openAccessibilitySettings"),
        )
    }

    @Test
    fun theRequestSurfaceIsDrivenByTheSourceOfTruth() {
        // The public Data Sharing tab uses the common permission authority plus the distribution
        // facade. Only research/open source may import the Health Connect request implementation.
        val fragment = File(buildRoot(), "app/src/main/java/com/openlattice/chronicle/ui/DataSharingFragment.kt")
        assertTrue("DataSharingFragment.kt not found at ${fragment.absolutePath}", fragment.isFile)
        val text = fragment.readText()
        assertTrue("DataSharingFragment must request via ModulePermissions", text.contains("ModulePermissions"))
        assertTrue(
            "DataSharingFragment must request distribution-owned optional permissions",
            text.contains("DistributionRestrictedRuntime"),
        )
        assertFalse(
            "Shared public source must not import Health Connect",
            text.contains("HealthConnectPermissions"),
        )
        assertTrue(
            "DataSharingFragment must request the device-supported optional permission set through the facade",
            text.contains("DistributionRestrictedRuntime.healthPermissionsToRequest"),
        )
        val researchFacade = File(
            buildRoot(),
            "app/src/googleServices/java/com/openlattice/chronicle/collection/DistributionRestrictedRuntime.kt",
        ).readText()
        assertTrue(researchFacade.contains("HealthConnectPermissions.permissionsToRequest"))
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
            CollectionModuleId.SENSOR_SIGNIFICANT_MOTION,
        ).forEach { id ->
            assertEquals(
                "$id must require ACTIVITY_RECOGNITION at runtime",
                setOf(ModulePermissions.ACTIVITY_RECOGNITION),
                runtimePerms(id),
            )
        }
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
