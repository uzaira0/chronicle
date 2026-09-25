package com.openlattice.chronicle.release

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** Source-level invariants for the release gates in app/build.gradle. */
class BuildGateContractTest {
    private val appGradle = File("build.gradle").readText()

    @Test
    fun runtimeGatesCoverEveryShippedFlavorIncludingOpen() {
        assertTrue(
            appGradle.contains(
                "def releaseRuntimeClasspaths = ['playReleaseRuntimeClasspath', 'amazonReleaseRuntimeClasspath', " +
                    "'researchReleaseRuntimeClasspath', 'openReleaseRuntimeClasspath']",
            ),
        )
        // verifyNoJacksonRuntime and generateRuntimeSbom both read the shared list.
        assertTrue(appGradle.contains("def forbidden = releaseRuntimeClasspaths"))
        assertTrue(appGradle.contains("runtimeComponents(releaseRuntimeClasspaths)"))
    }

    @Test
    fun researchArtifactBuildRefusesABlankProductionHost() {
        // Research inherits ALLOW_ANY_SERVER=false and allowlists only this host, so a blank
        // value produces an APK that can enroll nowhere.
        assertTrue(appGradle.contains("if (requiresResearchArtifact && chronicleProductionHost.isBlank())"))
        assertTrue(appGradle.contains("CHRONICLE_PRODUCTION_HOST (or -PchronicleProductionHost) is required for research builds"))
    }
}
