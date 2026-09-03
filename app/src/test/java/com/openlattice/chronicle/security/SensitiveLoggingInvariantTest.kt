package com.openlattice.chronicle.security

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Source-level guard for Android logcat redaction. Runtime upload telemetry already drops
 * credential-bearing fields; this catches the simpler failure mode where a future edit logs
 * api keys, signing overrides, participant ids, device ids, or auth headers directly.
 */
class SensitiveLoggingInvariantTest {

    private companion object {
        val sensitiveTerms = listOf(
            "apiKey",
            "mobileSigningSecret",
            "MOBILE_SIGNING_SECRET",
            "participantId",
            "sourceDeviceId",
            "X-Api-Key",
            "Authorization",
            "deviceSecret",
        )

        fun buildRoot(): File {
            var dir: File? = File(".").absoluteFile
            repeat(6) {
                val base = dir
                if (base != null && File(base, "app/src/main/AndroidManifest.xml").isFile) return base
                dir = base?.parentFile
            }
            error("Could not locate the Android build root from cwd=${File(".").absolutePath}")
        }

        fun mainSources(root: File): List<File> =
            (root.listFiles { f -> f.isDirectory } ?: emptyArray())
                .map { File(it, "src/main") }
                .filter { it.isDirectory }
                .flatMap { dir -> dir.walkTopDown().filter { it.isFile && (it.extension == "kt" || it.extension == "java") } }

        fun isAllowed(line: String): Boolean =
            line.contains("Backfilled sourceDeviceId on \$backfilled server row(s)")
    }

    @Test
    fun logStatementsDoNotReferenceSecretBearingFields() {
        val offenders = mainSources(buildRoot()).flatMap { file ->
            file.readLines().mapIndexedNotNull { index, line ->
                val hasLogCall = line.contains("Log.") || line.contains("println(") || line.contains("printStackTrace(")
                val referencesSensitiveField = sensitiveTerms.any { line.contains(it) }
                if (hasLogCall && referencesSensitiveField && !isAllowed(line)) {
                    "${file.relativeTo(buildRoot()).path}:${index + 1}: ${line.trim()}"
                } else {
                    null
                }
            }
        }

        assertTrue(
            "Android log statements must not reference secret-bearing fields. Offenders:\n" +
                offenders.joinToString("\n"),
            offenders.isEmpty(),
        )
    }
}
