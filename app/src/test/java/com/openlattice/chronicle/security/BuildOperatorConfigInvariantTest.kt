package com.openlattice.chronicle.security

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class BuildOperatorConfigInvariantTest {
    @Test
    fun publicBuildKeepsLegacyOperatorSecretOutOfArtifactsAndLogs() {
        val buildFile = locateBuildFile()
        val text = buildFile.readText()

        assertTrue(text.contains("readGeneratedIosConfigValue('MOBILE_SIGNING_SECRET')"))
        assertTrue(text.contains("buildConfigField \"String\", \"MOBILE_SIGNING_SECRET\", '\"\"'"))
        assertTrue(text.contains("Transitional compatibility for controlled research deployments only."))
        assertFalse(text.contains("CHRONICLE_TESTPROD"))
        assertFalse(text.contains("ALLOW_TESTPROD_SERVER"))
        assertFalse(text.contains("mobileSigningSecretFingerprint"))
        assertFalse(text.contains("mobileSigningSecretSource"))
        assertFalse(text.contains("MOBILE_SIGNING_SECRET resolved from"))
        assertFalse(text.contains("\"MOBILE_SIGNING_SECRET=\$mobileSigningSecret\""))
    }

    @Test
    fun publicStoreBuildsAcceptParticipantSelectedPublicHttpsChronicleServers() {
        val buildText = locateBuildFile().readText()
        val enrollmentText = locateProjectFile(
            "app/src/main/java/com/openlattice/chronicle/Enrollment.kt"
        ).readText()

        val playBlock = buildText.substringAfter("play {").substringBefore("amazon {")
        val amazonBlock = buildText.substringAfter("amazon {").substringBefore("research {")
        assertTrue(playBlock.contains("buildConfigField \"boolean\", \"ALLOW_ANY_SERVER\", \"true\""))
        assertTrue(amazonBlock.contains("buildConfigField \"boolean\", \"ALLOW_ANY_SERVER\", \"true\""))
        assertTrue(playBlock.contains("buildConfigField \"String\", \"CHRONICLE_PRODUCTION_HOST\", '\"\"'"))
        assertTrue(amazonBlock.contains("buildConfigField \"String\", \"CHRONICLE_PRODUCTION_HOST\", '\"\"'"))
        assertFalse(enrollmentText.contains("BuildConfig.DISTRIBUTION_CHANNEL == \"PLAY\""))
        assertFalse(enrollmentText.contains("serverUrlTextLayout).visibility = View.GONE"))
    }

    @Test
    fun playAabVerificationBindsTheArtifactToAnExpectedUploadCertificate() {
        val verifier = locateProjectFile("scripts/verify-play-aab.sh").readText()

        assertTrue(verifier.contains("--expected-cert-sha256"))
        assertTrue(verifier.contains("PLAY_UPLOAD_CERT_SHA256"))
        assertTrue(verifier.contains("keytool -printcert -jarfile"))
        assertTrue(verifier.contains("signer-certificate.sha256"))
        assertTrue(verifier.contains("--allow-unpinned-cert"))
        assertTrue(verifier.contains(":app:bundlePlayRelease --rerun-tasks"))
        assertTrue(verifier.contains("refusing release verification without an expected signer certificate"))
    }

    @Test
    fun releaseEvidenceNeverRetainsTheEnrollmentCapability() {
        val gate = locateProjectFile("scripts/android-release-candidate-gate.sh").readText()

        assertTrue(gate.contains("enrollment_credential_retained=false"))
        assertTrue(gate.contains("enrollment_url_retained=false"))
        assertTrue(gate.contains("--enrollment-url-file"))
        assertTrue(gate.contains("! -O \"\$enrollment_url_file\""))
        assertTrue(gate.contains("file_mode\" != \"600"))
        assertTrue(gate.contains("printf '%s\\n' \"\$enrollment_url\" | adb"))
        assertTrue(gate.contains("unset enrollment_url"))
        assertTrue(gate.contains("redact_enrollment_code"))
        assertTrue(gate.contains("rg -aFq -- \"\$enrollment_code\" \"\$output_dir\""))
        assertTrue(gate.contains("enrollment_code_length=\"\${#enrollment_code}\""))
        assertTrue(gate.contains("enrollment_code_length < 32"))
        assertTrue(gate.contains("enrollment_code_length > 256"))
        assertFalse(gate.contains("{32,256}"))
        assertFalse(gate.contains("echo \"enrollment_url=\$enrollment_url\""))
        assertFalse(gate.contains("--enrollment-url <url>"))
        assertFalse(gate.contains("-d \"\$enrollment_url\""))
        assertFalse(gate.contains("enrollment_url_without_credential"))
        assertFalse(gate.contains("open-enrollment-url.txt"))
        assertFalse(gate.contains("enrollment-ui.xml"))
        assertFalse(gate.contains("enrollment-ui.png"))
        assertFalse(gate.contains("logcat-after-enrollment-url.txt"))
    }


    private fun locateBuildFile(): File {
        var directory: File? = File(".").absoluteFile
        repeat(6) {
            val candidate = directory?.resolve("app/build.gradle")
            if (candidate?.isFile == true) return candidate
            directory = directory?.parentFile
        }
        error("Could not locate app/build.gradle")
    }

    private fun locateProjectFile(path: String): File {
        var directory: File? = File(".").absoluteFile
        repeat(6) {
            val candidate = directory?.resolve(path)
            if (candidate?.isFile == true) return candidate
            directory = directory?.parentFile
        }
        error("Could not locate $path")
    }
}
