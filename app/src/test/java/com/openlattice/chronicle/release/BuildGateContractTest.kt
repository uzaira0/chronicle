package com.openlattice.chronicle.release

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** Source-level invariants for the release gates in app/build.gradle. */
class BuildGateContractTest {
    private val appGradle = File("build.gradle").readText()

    @Test
    fun sensorReceiptsKeepTheirReflectiveShapeAndEveryReleaseRunsTheWireVerifier() {
        val rules = File("proguard-rules.pro").readText()
        assertTrue(rules.contains("-keep class com.openlattice.chronicle.android.SensorUploadReceipt { *; }"))
        val gates = appGradle.substringAfter("['play', 'amazon', 'research', 'open'].each { flavor ->")
        assertTrue(gates.contains("verify\${capitalized}ReleaseWireDtoNames"))
        assertTrue(gates.contains("dependsOn \"minify\${capitalized}ReleaseWithR8\""))
        assertTrue(gates.contains("scripts/verify-wire-dto-names.sh"))
        assertTrue(gates.contains("outputs/mapping/\${flavor}Release/mapping.txt"))
        assertTrue(gates.contains("it.name == \"assemble\${capitalized}Release\" || it.name == \"bundle\${capitalized}Release\""))
        assertTrue(gates.contains("dependsOn(wireNamesTask)"))
    }

    @Test
    fun runtimeGatesCoverEveryShippedFlavorIncludingOpen() {
        assertTrue(
            appGradle.contains(
                "def releaseRuntimeClasspaths = ['playReleaseRuntimeClasspath', 'amazonReleaseRuntimeClasspath', " +
                    "'researchReleaseRuntimeClasspath', 'openReleaseRuntimeClasspath']",
            ),
        )
        // Runtime verification covers the shared list; the SBOM can select one candidate variant.
        assertTrue(appGradle.contains("def forbidden = releaseRuntimeClasspaths"))
        assertTrue(appGradle.contains("selectedVariant.orElse('all-release')"))
        assertTrue(appGradle.contains(" : releaseRuntimeClasspaths"))
        assertTrue(appGradle.contains("runtimeComponents(names)"))
    }

    @Test
    fun researchArtifactBuildRefusesABlankProductionHost() {
        // Research inherits ALLOW_ANY_SERVER=false and allowlists only this host, so a blank
        // value produces an APK that can enroll nowhere.
        assertTrue(appGradle.contains("if (requiresResearchArtifact && chronicleProductionHost.isBlank())"))
        assertTrue(appGradle.contains("CHRONICLE_PRODUCTION_HOST (or -PchronicleProductionHost) is required for research builds"))
    }
}
